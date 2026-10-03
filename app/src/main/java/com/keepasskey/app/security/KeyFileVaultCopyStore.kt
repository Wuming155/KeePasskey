package com.keepasskey.app.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.data.logger.DebugLogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 密钥文件的**应用私有目录收编副本**（ISSUE-P3-448，§411 实装；§411 热修复重构）。
 *
 * 语义（用户裁决）：「导入密钥文件」= 把解锁时所选（记忆）的密钥文件内容复制进应用私有目录，
 * 此后解锁链**优先消费本副本**——不再依赖 SAF 持久化读授权（授权失效不再阻断解锁），
 * 对比「记住密钥文件位置」（仅记 SAF Uri）的增强收编。
 *
 * **加密架构（§411 热修复，ANR 实测驱动）**：真机 ANR 取证（M332BF）表明，把 700 KB 密钥文件
 * 整载荷直接经 Keystore（StrongBox）GCM 加密会在 KeyMint binder 上阻塞主线程数十秒
 * （`slow binder: app → keystore2 → strongbox-nxp`，§408 已有同型教训：**芯片只处理小数据段**）。
 * 现改为**信封加密**：
 * - 每次 save 生成随机 32 B 数据加密密钥（DEK），Keystore 密钥（[KEY_ALIAS]，`requireUserAuth=false`，
 *   安全取舍与 `SyncCredentialSealer` 同判据）只封印这 32 B（小载荷，秒内）；
 * - 700 KB 级载荷由 DEK 在**软件层** AES-256-GCM 加密（`javax.crypto` 本地运算，不经 KeyMint）；
 * - 文件布局：`[12B sealIv][2B sealCtLen][sealCt][12B dataIv][dataCt]`；`dataCt` 明文为
 *   `[2B 显示名长][显示名 UTF-8][密钥文件字节]`（显示名随载荷加密，孤儿副本仍可回显）。
 * - 读写全程 `ByteArray` 且整体移至 `Dispatchers.IO`（Keystore 与文件 IO 绝不上主线程）；
 *   写入为临时文件 + 原子改名（工程规则「原子写盘」）。
 *
 * 清除时机（AC②）：解绑 / 换绑（`SettingsMasterKeyChangeController`）、偏好关闭、
 * 解锁成功但未使用密钥文件（`KeyFileSessionCoordinator`）、删除库（`DatabasePickerViewModel`）。
 */
@Singleton
class KeyFileVaultCopyStore @Inject constructor(
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val context: Context?,
    // 允许为 null 仅用于单测手动构造；生产 DI 注入
    private val keystoreManager: KeystoreManager?,
    private val debugLog: DebugLogBuffer? = null
) {

    /** 收编副本（解密产物）：字节（调用方用毕清零）+ 非敏感显示名 */
    data class StoredCopy(val bytes: ByteArray, val displayName: String)

    /** 单测注入的落盘根目录覆盖（生产恒 null → `filesDir/keyfiles`）。Hilt 不接受 `File?` 构造依赖。 */
    @VisibleForTesting
    internal var baseDirOverride: File? = null

    /** 测试注入的 DEK 封印钩子（JVM 无 Keystore）：`DEK → (iv, 密文)`。生产恒 null。 */
    @VisibleForTesting
    internal var sealHook: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null

    /** 测试注入的 DEK 解封钩子：`(iv, 密文) → DEK`。生产恒 null。 */
    @VisibleForTesting
    internal var unsealHook: ((ByteArray, ByteArray) -> ByteArray)? = null

    private val baseDir: File?
        get() = baseDirOverride
            ?: context?.filesDir?.let { File(it, DIR_NAME) }

    private val random = SecureRandom()

    /** 收编副本：生成一次性 DEK → Keystore 封 DEK → 软件层加密载荷 → 原子写盘。失败显式留痕并返回 false。 */
    suspend fun save(databaseId: String, bytes: ByteArray, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (databaseId.isBlank() || bytes.isEmpty()) return@withContext false
            val dir = baseDir ?: return@withContext false
            val payload = withDisplayName(displayName, bytes)
            // DEK 清零收口在最外层 finally：加密封装全程（含软件层 doFinal）都要求 DEK 存活
            val dek = ByteArray(DEK_LENGTH_BYTES).also { random.nextBytes(it) }
            try {
                // Keystore 只封这 32 B（§408 教训：芯片不碰大载荷）
                val (sealIv, sealCt) = sealHook?.invoke(dek) ?: sealWithKeystore(dek)
                    ?: return@withContext false
                val dataIv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }
                val dataCt = softAesGcm(dek, dataIv, payload)
                if (!dir.exists()) dir.mkdirs()
                val target = fileFor(dir, databaseId)
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { os ->
                    os.write(sealIv)
                    writeShort(os, sealCt.size)
                    os.write(sealCt)
                    os.write(dataIv)
                    os.write(dataCt)
                    os.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    debugLog?.warn(TAG, "副本原子改名失败")
                    return@withContext false
                }
                true
            } catch (e: Exception) {
                debugLog?.warn(TAG, "副本写入失败: ${e.javaClass.simpleName}")
                false
            } finally {
                dek.fill(0)
                payload.fill(0)
            }
        }

    /** 读取并解密封印副本；缺失 / 损坏返回 null（损坏就地删除，避免反复失败）。 */
    suspend fun load(databaseId: String): StoredCopy? = withContext(Dispatchers.IO) {
        if (databaseId.isBlank()) return@withContext null
        val dir = baseDir ?: return@withContext null
        val target = fileFor(dir, databaseId)
        if (!target.exists()) return@withContext null
        try {
            val raw = target.readBytes()
            var offset = 0
            fun take(n: Int): ByteArray {
                if (raw.size < offset + n) throw IllegalStateException("副本截断")
                return raw.copyOfRange(offset, offset + n).also { offset += n }
            }
            val sealIv = take(IV_LENGTH_BYTES)
            val sealLen = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            offset += 2
            val sealCt = take(sealLen)
            val dataIv = take(IV_LENGTH_BYTES)
            val dataCt = take(raw.size - offset)
            // Keystore 只解封 32 B DEK（小载荷）；700 KB 级载荷走软件层解密
            val dek = unsealHook?.invoke(sealIv, sealCt) ?: unsealWithKeystore(sealIv, sealCt)
                ?: return@withContext corrupt(target)
            val payload = try {
                softAesGcm(dek, dataIv, dataCt, decrypt = true)
            } finally {
                dek.fill(0)
            }
            val copy = parsePayload(payload)
            payload.fill(0)
            copy
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本读取失败: ${e.javaClass.simpleName}")
            corrupt(target)
        }
    }

    /** 清除指定库的收编副本（幂等）。 */
    fun clear(databaseId: String) {
        if (databaseId.isBlank()) return
        val dir = baseDir ?: return
        try {
            fileFor(dir, databaseId).delete()
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本清除失败: ${e.javaClass.simpleName}")
        }
    }

    /**
     * §433（ISSUE-P3-448 走查续）：副本文件的**绝对路径**（文件不存在 / 通道缺失 ⇒ null）。
     *
     * 供解锁页呈现「本次加载来源」：路径本身不含密钥材料（文件名是库 id 的 SHA-256 摘要），
     * 但属应用内部布局信息，UI 侧默认以**中间省略**呈现、由用户显式展开看全。
     */
    fun copyPathFor(databaseId: String): String? {
        if (databaseId.isBlank()) return null
        val dir = baseDir ?: return null
        val target = fileFor(dir, databaseId)
        return if (target.exists()) target.absolutePath else null
    }

    /** 清除全部收编副本（凭据全清 / 数据迁移等场景）。 */
    fun clearAll() {
        val dir = baseDir ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本全清失败: ${e.javaClass.simpleName}")
        }
    }

    // ===== 内部实现 =====

    /** Keystore 封 DEK（requireUserAuth = false 的安全取舍声明见类 KDoc；仅 32 B 小载荷） */
    private fun sealWithKeystore(dek: ByteArray): Pair<ByteArray, ByteArray>? {
        val km = keystoreManager ?: run {
            debugLog?.warn(TAG, "Keystore 通道缺失，副本未写入")
            return null
        }
        val key = km.getOrCreateKey(KEY_ALIAS, requireUserAuth = false)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Pair(cipher.iv, cipher.doFinal(dek))
    }

    private fun unsealWithKeystore(iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        val km = keystoreManager ?: return null
        val key = km.getOrCreateKey(KEY_ALIAS, requireUserAuth = false)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * **软件层** AES-256-GCM（`javax.crypto` 本地运算，不进 KeyMint/StrongBox）：
     * encrypt = true 时 plaintext=载荷；false 时入参为密文返回明文。
     */
    private fun softAesGcm(dek: ByteArray, iv: ByteArray, data: ByteArray, decrypt: Boolean = false): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
            SecretKeySpec(dek, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(data)
    }

    /** 明文载荷组装：`[2 字节名字节长][显示名 UTF-8][密钥文件字节]` */
    private fun withDisplayName(displayName: String, bytes: ByteArray): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(USHORT_MAX)
        val payload = ByteArray(NAME_LEN_PREFIX + nameLen + bytes.size)
        payload[0] = ((nameLen shr 8) and 0xFF).toByte()
        payload[1] = (nameLen and 0xFF).toByte()
        nameBytes.copyInto(payload, NAME_LEN_PREFIX)
        bytes.copyInto(payload, NAME_LEN_PREFIX + nameLen)
        return payload
    }

    private fun parsePayload(payload: ByteArray): StoredCopy? {
        if (payload.size < NAME_LEN_PREFIX) return null
        val nameLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (payload.size < NAME_LEN_PREFIX + nameLen) return null
        val displayName = String(payload, NAME_LEN_PREFIX, nameLen, Charsets.UTF_8)
        val bytes = payload.copyOfRange(NAME_LEN_PREFIX + nameLen, payload.size)
        return StoredCopy(bytes, displayName)
    }

    /** 损坏处置：就地删除（下次解锁回落 SAF 记忆通道），绝不反复失败 */
    private fun corrupt(target: File): StoredCopy? {
        debugLog?.warn(TAG, "副本损坏，就地删除")
        try {
            target.delete()
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * 副本文件名（§411 真机修复）：库 id 对 SAF 库是 `content://` URI——**含 `/` 斜杠**，
     * 直接拼接文件名会形成不存在的多级子路径（真机 `FileNotFoundException`，副本功能全灭）。
     * 统一对 dbId 做 SHA-256 十六进制摘要编码（同库恒同文件名，save/load/clear 三侧一致）。
     */
    private fun fileFor(dir: File, databaseId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(databaseId.toByteArray(Charsets.UTF_8))
            .toHexString()
        return File(dir, "$digest$FILE_SUFFIX")
    }

    private fun writeShort(os: java.io.OutputStream, value: Int) {
        os.write((value shr 8) and 0xFF)
        os.write(value and 0xFF)
    }

    private companion object {
        const val TAG = "KeyFileVaultCopy"
        const val DIR_NAME = "keyfiles"
        const val FILE_SUFFIX = ".kfc"
        const val KEY_ALIAS = "com.keepasskey.keyfile_copy_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
        const val DEK_LENGTH_BYTES = 32
        const val NAME_LEN_PREFIX = 2
        const val USHORT_MAX = 0xFFFF
    }
}
