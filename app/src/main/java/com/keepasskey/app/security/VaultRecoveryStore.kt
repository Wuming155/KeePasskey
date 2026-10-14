package com.keepasskey.app.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.security.SecureFileWrite
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自动锁定「未落盘修改」的独立加密恢复副本存储（`ISSUE-P1-592`）。
 *
 * 背景（AUD-04）：`AutoLockSessionGuard.triggerLock` 发现会话为 DIRTY 时会先补存，补存失败
 * 只记日志仍锁库并擦除内存树 —— 该版本（已进入会话的修改）随锁定永久丢失。本类承接**失败后
 * 的独立保全通道**：把「当前会话以复合凭据重新序列化出的标准 KDBX 密文」写进**应用私有非缓存
 * 目录**，供下次冷启动发现与恢复。
 *
 * ## 存储布局（`filesDir/recovery`，目录 0700 / 文件 0600，不依赖 umask）
 * - `<key>.<gen>.kre`：**载荷**——`key` 是库身份（[pathIdentifier]）的 SHA-256 摘要，`gen` 为
 *   「时间戳-UUID」代次；文件内容即会话导出的**标准 KDBX 密文**（`DatabaseSession.exportToBytes`），
 *   与正式库同一复合凭据加密，**主密码与密钥文件绝不随副本落盘**。
 * - `<key>.krem`：**非敏感旁路索引**（[Entry]，UTF-8 Properties）——只放库身份 / 显示名 /
 *   代次文件名 / 载荷字节长度 / 载荷 SHA-256，**不承载任何恢复语义**；恢复明文语义一律从
 *   「成功解密的载荷」读取（步骤 3 口径）。
 *
 * ## 提交顺序（步骤 3：临时写入、fsync、原子替换；保留上一份有效副本直到新副本确认）
 * 1. 新代次载荷：唯一临时名 → 写满 → `flush` + `fd.sync` → 0600 → 原子改名（[SecureFileWrite]）；
 * 2. 索引：临时写入 + fsync + 原子改名 —— **提交点**，其后才指向新代次；
 * 3. 清理该库更早代次载荷（此刻新副本已确认）。
 *   中断于 ①/②：索引仍指向旧代次且旧载荷未删 → **上一有效副本保留**；索引改名原子 ⇒ 不会出现
 *   半截副本被采纳。载荷字节与索引长度 / SHA-256 不符即判「损坏」，[readPayload] 返回 null
 *   （**不自动采纳**）。
 */
@Singleton
class VaultRecoveryStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单测（配合 [baseDirOverride]）；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val context: Context?,
    private val debugLog: DebugLogBuffer? = null
) {

    /**
     * 一份恢复副本的非敏感旁路索引（只用于发现；语义内容以解密产物为准）。
     * 构造函数 `internal` 仅为单测构造，生产只经 [write] / [list] 流转。
     */
    data class Entry(
        val vaultId: String,
        val vaultName: String,
        val pathIdentifier: String,
        val createdAtEpochMs: Long,
        val payloadFileName: String,
        val payloadLength: Long,
        val payloadSha256: String
    )

    /** 单测注入的落盘根目录覆盖（生产恒 null → `filesDir/recovery`）。 */
    @VisibleForTesting
    internal var baseDirOverride: File? = null

    private val baseDir: File?
        get() = baseDirOverride ?: context?.filesDir?.let { File(it, DIR_NAME) }

    /**
     * 写入一份恢复副本。返回 true 表示索引已提交（副本可供下次冷启动发现）。
     * 序列化由调用方完成（Default），本方法只负责落盘（IO）。
     */
    suspend fun write(
        vaultId: String,
        vaultName: String,
        pathIdentifier: String,
        kdbxBytes: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        if (vaultId.isBlank() || kdbxBytes.isEmpty()) return@withContext false
        val dir = baseDir ?: return@withContext false
        val key = keyPrefix(vaultId)
        val createdAt = System.currentTimeMillis()
        val payloadName = "$key.$createdAt-${UUID.randomUUID()}$PAYLOAD_SUFFIX"
        val payloadFile = File(dir, payloadName)
        val metaFile = File(dir, "$key$META_SUFFIX")
        try {
            if (!dir.exists()) dir.mkdirs()
            SecureFileWrite.restrictToOwnerOnly(dir, isDirectory = true)

            if (!writePayload(payloadFile, kdbxBytes)) return@withContext false

            val entry = Entry(
                vaultId = vaultId,
                vaultName = vaultName,
                pathIdentifier = pathIdentifier,
                createdAtEpochMs = createdAt,
                payloadFileName = payloadName,
                payloadLength = kdbxBytes.size.toLong(),
                payloadSha256 = sha256Hex(kdbxBytes)
            )
            writeMetaAtomically(metaFile, entry)

            // 新副本已在索引中确认：清理该库更早代次载荷（保留上一份直到此刻）
            pruneOlderPayloads(dir, key, payloadName)
            true
        } catch (e: Exception) { // cancel-n/a: 保护段为阻塞式文件 IO（无挂起点）
            debugLog?.warn(TAG, "恢复副本写入失败: ${e.javaClass.simpleName}")
            false
        }
    }

    /** 全部待处理的恢复副本（读索引；索引缺失 / 无法解析的条目跳过）。 */
    fun list(): List<Entry> {
        val dir = baseDir ?: return emptyList()
        val metas = dir.listFiles { file -> file.isFile && file.name.endsWith(META_SUFFIX) }
            ?: return emptyList()
        return metas.mapNotNull { parseMeta(it) }
    }

    /**
     * 读取并校验载荷字节（调用方用毕清零）。索引与载荷的**长度 / SHA-256** 任一不符即判损坏，
     * 返回 null（不自动采纳）——绝不让半截 / 被改的副本冒充可恢复版本。
     */
    suspend fun readPayload(entry: Entry): ByteArray? = withContext(Dispatchers.IO) {
        val dir = baseDir ?: return@withContext null
        val file = File(dir, entry.payloadFileName)
        if (!file.exists()) return@withContext null
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) { // cancel-n/a: 保护段为阻塞式文件读取（无挂起点）
            debugLog?.warn(TAG, "恢复副本读取失败: ${e.javaClass.simpleName}")
            return@withContext null
        }
        val lengthOk = bytes.size.toLong() == entry.payloadLength
        val digestOk = sha256Hex(bytes) == entry.payloadSha256
        if (!lengthOk || !digestOk) {
            bytes.fill(0)
            debugLog?.warn(TAG, "恢复副本完整性校验失败（不自动采纳）")
            return@withContext null
        }
        bytes
    }

    /** 删除一份恢复副本（索引 + 该库全部代次载荷）；幂等。 */
    fun delete(entry: Entry) = deleteByVaultId(entry.vaultId)

    /**
     * 按库身份删除该库全部恢复副本（索引 + 全部代次载荷）；幂等。
     * 凭据轮换后旧密文副本（可被旧口令解开）必须失效——见 `ISSUE-P1-592` 步骤 6。
     */
    fun deleteByVaultId(vaultId: String?) {
        if (vaultId.isNullOrBlank()) return
        val dir = baseDir ?: return
        val key = keyPrefix(vaultId)
        runCatching { File(dir, "$key$META_SUFFIX").delete() }
            .onFailure { debugLog?.warn(TAG, "恢复副本索引删除失败: ${it.javaClass.simpleName}") }
        pruneOlderPayloads(dir, key, keepPayloadName = null)
    }

    /** 是否至少存在一份待处理副本（冷启动发现的廉价探测）。 */
    fun hasAny(): Boolean = list().isNotEmpty()

    // ===== 内部实现 =====

    private fun writePayload(payloadFile: File, bytes: ByteArray): Boolean {
        val tmp = SecureFileWrite.uniqueTmpFor(payloadFile)
        return try {
            SecureFileWrite.openOwnerOnlyOutput(tmp).use { os ->
                os.write(bytes)
                os.flush()
                os.fd.sync()
            }
            SecureFileWrite.moveAtomically(tmp, payloadFile)
            true
        } catch (e: IOException) {
            tmp.delete()
            debugLog?.warn(TAG, "恢复副本载荷写入失败: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun writeMetaAtomically(metaFile: File, entry: Entry) {
        val props = Properties().apply {
            setProperty(KEY_VAULT_ID, entry.vaultId)
            setProperty(KEY_VAULT_NAME, entry.vaultName)
            setProperty(KEY_PATH_IDENTIFIER, entry.pathIdentifier)
            setProperty(KEY_CREATED_AT, entry.createdAtEpochMs.toString())
            setProperty(KEY_PAYLOAD_FILE, entry.payloadFileName)
            setProperty(KEY_PAYLOAD_LENGTH, entry.payloadLength.toString())
            setProperty(KEY_PAYLOAD_SHA256, entry.payloadSha256)
            setProperty(KEY_FORMAT_VERSION, FORMAT_VERSION)
        }
        val tmp = SecureFileWrite.uniqueTmpFor(metaFile)
        try {
            SecureFileWrite.openOwnerOnlyOutput(tmp).use { os ->
                // 不 close writer（会连带关闭 os，令后续 sync 抛 "Stream Closed"）；显式 flush 后 sync
                val writer = OutputStreamWriter(os, Charsets.UTF_8)
                props.store(writer, null)
                writer.flush()
                os.flush()
                os.fd.sync()
            }
            SecureFileWrite.moveAtomically(tmp, metaFile)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun parseMeta(file: File): Entry? = try {
        val props = Properties()
        file.reader(Charsets.UTF_8).use { props.load(it) }
        val vaultId = props.getProperty(KEY_VAULT_ID)
        val payloadFile = props.getProperty(KEY_PAYLOAD_FILE)
        if (vaultId.isNullOrBlank() || payloadFile.isNullOrBlank()) {
            null
        } else {
            Entry(
                vaultId = vaultId,
                vaultName = props.getProperty(KEY_VAULT_NAME) ?: vaultId,
                pathIdentifier = props.getProperty(KEY_PATH_IDENTIFIER) ?: vaultId,
                createdAtEpochMs = props.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: 0L,
                payloadFileName = payloadFile,
                payloadLength = props.getProperty(KEY_PAYLOAD_LENGTH)?.toLongOrNull() ?: -1L,
                payloadSha256 = props.getProperty(KEY_PAYLOAD_SHA256).orEmpty()
            )
        }
    } catch (e: Exception) { // cancel-n/a: 保护段为阻塞式文件读取（无挂起点）
        debugLog?.warn(TAG, "恢复副本索引解析失败: ${e.javaClass.simpleName}")
        null
    }

    private fun pruneOlderPayloads(dir: File, key: String, keepPayloadName: String?) {
        val prefix = "$key."
        dir.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && file.name.endsWith(PAYLOAD_SUFFIX) &&
                file.name != keepPayloadName
        }?.forEach { runCatching { it.delete() } }
    }

    /** 库身份 → 文件名前缀（库身份可能是含 `/` 的 `content://` URI，须摘要编码）。 */
    private fun keyPrefix(vaultId: String): String = sha256Hex(vaultId.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance(SHA_256).digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "VaultRecoveryStore"
        const val DIR_NAME = "recovery"
        const val META_SUFFIX = ".krem"
        const val PAYLOAD_SUFFIX = ".kre"
        const val FORMAT_VERSION = "1"
        const val SHA_256 = "SHA-256"

        const val KEY_VAULT_ID = "vaultId"
        const val KEY_VAULT_NAME = "vaultName"
        const val KEY_PATH_IDENTIFIER = "pathIdentifier"
        const val KEY_CREATED_AT = "createdAtEpochMs"
        const val KEY_PAYLOAD_FILE = "payloadFile"
        const val KEY_PAYLOAD_LENGTH = "payloadLength"
        const val KEY_PAYLOAD_SHA256 = "payloadSha256"
        const val KEY_FORMAT_VERSION = "formatVersion"
    }
}
