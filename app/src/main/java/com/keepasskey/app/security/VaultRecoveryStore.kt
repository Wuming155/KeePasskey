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

/** 标准 KDBX 密文恢复材料；载荷先 fsync，索引原子提交后才可发现。 */
@Singleton
class VaultRecoveryStore @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val debugLog: DebugLogBuffer? = null
) {
    enum class Purpose { LOCK_FAILURE, CRASH_DRAFT }

    data class Entry(
        val vaultId: String,
        val vaultName: String,
        val pathIdentifier: String,
        val createdAtEpochMs: Long,
        val payloadFileName: String,
        val payloadLength: Long,
        val payloadSha256: String,
        val purpose: Purpose = Purpose.LOCK_FAILURE
    )

    /** 阶段化产物仅拥有磁盘密文；未提交时 close 删除孤儿载荷。 */
    inner class Staged internal constructor(val entry: Entry, internal val ordinal: Long) : AutoCloseable {
        internal var committed = false
        override fun close() {
            synchronized(this@VaultRecoveryStore) {
                if (!committed) baseDir?.let { File(it, entry.payloadFileName).delete() }
            }
        }
    }

    @VisibleForTesting internal var baseDirOverride: File? = null
    private val baseDir: File? get() = baseDirOverride ?: context?.filesDir?.let { File(it, DIR_NAME) }
    private var nextOrdinal = 0L
    private val latestStage = mutableMapOf<String, Long>()

    /** 借用密文，严禁修改或清零；只写独立载荷，不发布索引。 */
    suspend fun stage(
        vaultId: String, vaultName: String, pathIdentifier: String, kdbxBytes: ByteArray,
        purpose: Purpose = Purpose.LOCK_FAILURE
    ): Staged? {
        val key = keyPrefix(vaultId, purpose)
        val ordinal = synchronized(this) { (++nextOrdinal).also { latestStage[key] = it } }
        var pending: Staged? = null
        try {
            withContext(Dispatchers.IO) {
                val dir = baseDir ?: return@withContext
                if (vaultId.isBlank() || kdbxBytes.isEmpty()) return@withContext
                val createdAt = System.currentTimeMillis()
                val entry = Entry(vaultId, vaultName, pathIdentifier, createdAt,
                    "$key.$createdAt-${UUID.randomUUID()}$PAYLOAD_SUFFIX", kdbxBytes.size.toLong(),
                    sha256Hex(kdbxBytes), purpose)
                val staged = Staged(entry, ordinal)
                pending = staged
                if (!dir.exists()) dir.mkdirs()
                SecureFileWrite.restrictToOwnerOnly(dir, isDirectory = true)
                writePayload(File(dir, entry.payloadFileName), kdbxBytes)
            }
            return pending.also { pending = null }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            debugLog?.warn(TAG, "恢复载荷阶段写入失败: ${error.javaClass.simpleName}")
            return null
        } finally {
            pending?.close()
        }
    }

    /** 短本地提交；调用方在会话票据锁内调用。失败不改变上一份有效索引。 */
    @Synchronized fun commit(staged: Staged): Boolean {
        val dir = baseDir ?: return false
        val entry = staged.entry
        val key = keyPrefix(entry.vaultId, entry.purpose)
        if (latestStage[key] != staged.ordinal || staged.committed) return false
        return try {
            writeMetaAtomically(File(dir, "$key$META_SUFFIX"), entry)
            staged.committed = true
            pruneOlderPayloads(dir, key, entry.payloadFileName)
            true
        } catch (error: Exception) {
            debugLog?.warn(TAG, "恢复索引提交失败: ${error.javaClass.simpleName}")
            false
        }
    }

    suspend fun write(
        vaultId: String, vaultName: String, pathIdentifier: String, kdbxBytes: ByteArray,
        purpose: Purpose = Purpose.LOCK_FAILURE
    ): Boolean {
        val staged = stage(vaultId, vaultName, pathIdentifier, kdbxBytes, purpose) ?: return false
        return try { withContext(Dispatchers.IO) { commit(staged) } } finally { staged.close() }
    }

    @Synchronized fun list(): List<Entry> = baseDir
        ?.listFiles { file -> file.isFile && file.name.endsWith(META_SUFFIX) }
        ?.mapNotNull(::parseMeta).orEmpty()

    /** 返回独占密文副本，调用方负责清零；取消交接未交付时在此清理。 */
    suspend fun readPayload(entry: Entry): ByteArray? {
        var pending: ByteArray? = null
        try {
            withContext(Dispatchers.IO) {
                val dir = baseDir ?: return@withContext
                if (!safePayloadName(entry.payloadFileName)) return@withContext
                val file = File(dir, entry.payloadFileName)
                if (!file.isFile) return@withContext
                val bytes = file.readBytes()
                pending = bytes
                if (bytes.size.toLong() != entry.payloadLength || sha256Hex(bytes) != entry.payloadSha256) {
                    bytes.fill(0)
                    pending = null
                }
            }
            return pending.also { pending = null }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            debugLog?.warn(TAG, "恢复副本读取失败: ${error.javaClass.simpleName}")
            return null
        } finally { pending?.fill(0) }
    }

    /** 仅删除完全匹配的目的及版本；旧保存回执不能删除较新草稿。 */
    @Synchronized fun delete(entry: Entry) {
        val dir = baseDir ?: return
        dir.listFiles { file -> file.name.endsWith(META_SUFFIX) }?.forEach { meta ->
            if (parseMeta(meta) == entry) meta.delete()
        }
        if (safePayloadName(entry.payloadFileName)) File(dir, entry.payloadFileName).delete()
    }

    /** 凭据轮换后删除该库全部目的与版本，包括旧格式索引。 */
    @Synchronized fun deleteByVaultId(vaultId: String?) {
        if (vaultId.isNullOrBlank()) return
        val dir = baseDir ?: return
        Purpose.entries.forEach { purpose ->
            val key = keyPrefix(vaultId, purpose)
            latestStage[key] = ++nextOrdinal
            File(dir, "$key$META_SUFFIX").delete()
            pruneOlderPayloads(dir, key, null)
        }
        val legacyKey = sha256Hex(vaultId.toByteArray(Charsets.UTF_8))
        File(dir, "$legacyKey$META_SUFFIX").delete()
        pruneOlderPayloads(dir, legacyKey, null)
    }

    fun hasAny(): Boolean = list().isNotEmpty()

    private fun writePayload(file: File, bytes: ByteArray) {
        val tmp = SecureFileWrite.uniqueTmpFor(file)
        try {
            SecureFileWrite.openOwnerOnlyOutput(tmp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            SecureFileWrite.moveAtomically(tmp, file)
        } finally { tmp.delete() }
    }

    private fun writeMetaAtomically(file: File, entry: Entry) {
        val props = Properties().apply {
            setProperty("vaultId", entry.vaultId)
            setProperty("vaultName", entry.vaultName)
            setProperty("pathIdentifier", entry.pathIdentifier)
            setProperty("createdAtEpochMs", entry.createdAtEpochMs.toString())
            setProperty("payloadFile", entry.payloadFileName)
            setProperty("payloadLength", entry.payloadLength.toString())
            setProperty("payloadSha256", entry.payloadSha256)
            setProperty("purpose", entry.purpose.name)
            setProperty("formatVersion", FORMAT_VERSION)
        }
        val tmp = SecureFileWrite.uniqueTmpFor(file)
        try {
            SecureFileWrite.openOwnerOnlyOutput(tmp).use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                props.store(writer, null)
                writer.flush()
                output.flush()
                output.fd.sync()
            }
            SecureFileWrite.moveAtomically(tmp, file)
        } finally { tmp.delete() }
    }

    private fun parseMeta(file: File): Entry? = try {
        val props = Properties()
        file.reader(Charsets.UTF_8).use { props.load(it) }
        val version = props.getProperty("formatVersion", "1")
        val purpose = if (version == "1") Purpose.LOCK_FAILURE else
            Purpose.entries.firstOrNull { it.name == props.getProperty("purpose") }
        val vaultId = props.getProperty("vaultId")
        val payload = props.getProperty("payloadFile")
        if (version !in setOf("1", FORMAT_VERSION) || purpose == null || vaultId.isNullOrBlank() ||
            payload.isNullOrBlank() || !safePayloadName(payload)) null else Entry(
            vaultId, props.getProperty("vaultName") ?: vaultId,
            props.getProperty("pathIdentifier") ?: vaultId,
            props.getProperty("createdAtEpochMs")?.toLongOrNull() ?: 0L, payload,
            props.getProperty("payloadLength")?.toLongOrNull() ?: -1L,
            props.getProperty("payloadSha256").orEmpty(), purpose)
    } catch (error: Exception) {
        debugLog?.warn(TAG, "恢复索引读取失败: ${error.javaClass.simpleName}")
        null
    }

    private fun pruneOlderPayloads(dir: File, key: String, keep: String?) {
        dir.listFiles { file -> file.isFile && file.name.startsWith("$key.") &&
            file.name.endsWith(PAYLOAD_SUFFIX) && file.name != keep }?.forEach { it.delete() }
    }

    private fun safePayloadName(name: String) = name.endsWith(PAYLOAD_SUFFIX) &&
        !name.contains('/') && !name.contains('\\') && File(name).name == name
    private fun keyPrefix(vaultId: String, purpose: Purpose) =
        sha256Hex(vaultId.toByteArray(Charsets.UTF_8)) + "." + purpose.name
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "VaultRecoveryStore"
        const val DIR_NAME = "recovery"
        const val META_SUFFIX = ".krem"
        const val PAYLOAD_SUFFIX = ".kre"
        const val FORMAT_VERSION = "2"
    }
}
