package com.keepasskey.app.sync

import com.keepasskey.database.session.AtomicFileWriter
import com.keepasskey.sync.network.SyncDownloadLimits
import com.keepasskey.sync.engine.SyncCache
import java.io.File
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** 需要锁态条件上传的 KDBX 密文快照与其持久化提交意图。 */
internal data class PendingEncryptedUpload(
    val targetIdentity: String,
    val remotePath: String,
    val expectedEtag: String,
    val contentDigest: String,
    val rollbackVaultScope: String,
    val sizeBytes: Long,
    val payloadFileName: String
)

/**
 * 按活动库身份保存一个有界的 KDBX 密文待传快照。
 *
 * 载荷保存在应用私有 filesDir，内容仍是 KDBX 密文；清除或更换同步配置时由调用方删除。
 * 元数据不包含凭据，目标路径等字符串用 Base64 行编码避免分隔符歧义。所有文件替换都走
 * [AtomicFileWriter]，进程中断时只能看到旧快照、完整新快照或校验失败的队列。
 */
internal class PeriodicSyncPendingUploadStore(private val directory: File) {

    fun stage(
        vaultIdentity: String,
        targetIdentity: String,
        remotePath: String,
        expectedEtag: String,
        rollbackVaultScope: String,
        input: InputStream
    ): PendingEncryptedUpload? {
        if (vaultIdentity.isBlank() || !isDigest(targetIdentity) || remotePath.isBlank() ||
            expectedEtag.isBlank() || !isRollbackScope(rollbackVaultScope)
        ) {
            input.close()
            return null
        }
        if (!directory.exists() && !directory.mkdirs()) {
            input.close()
            return null
        }

        val previous = read(vaultIdentity)
        val payload = stagedPayloadFile(vaultIdentity)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            AtomicFileWriter.writeAtomic(payload, createBackup = false) { output ->
                val digestOutput = DigestOutputStream(output, digest)
                size = SyncDownloadLimits.copyBounded(
                    input = input,
                    declaredLength = -1,
                    maxBytes = MAX_PENDING_UPLOAD_BYTES,
                    label = "周期待传快照",
                    sink = digestOutput
                )
            }
            check(size > 0L) { "空的 KDBX 快照不能加入待传队列" }
            val record = PendingEncryptedUpload(
                targetIdentity = targetIdentity,
                remotePath = remotePath,
                expectedEtag = expectedEtag,
                contentDigest = digest.digest().toHex(),
                rollbackVaultScope = rollbackVaultScope,
                sizeBytes = size,
                payloadFileName = payload.name
            )
            writeMetadata(vaultIdentity, record)
            previous?.payloadFileName?.takeIf { it != record.payloadFileName }
                ?.let { File(directory, it).delete() }
            return record
        } catch (_: Exception) {
            runCatching { input.close() }
            payload.delete()
            return null
        }
    }

    fun read(vaultIdentity: String): PendingEncryptedUpload? {
        val metadata = metadataFile(vaultIdentity)
        if (!metadata.isFile) return null
        return try {
            val values = metadata.readLines(Charsets.UTF_8).mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
            if (values[KEY_VERSION] != VERSION) return null
            val targetIdentity = decode(values[KEY_TARGET]) ?: return null
            val remotePath = decode(values[KEY_PATH]) ?: return null
            val expectedEtag = decode(values[KEY_ETAG]) ?: return null
            val digest = values[KEY_DIGEST] ?: return null
            val rollbackScope = values[KEY_ROLLBACK_SCOPE] ?: return null
            val size = values[KEY_SIZE]?.toLongOrNull() ?: return null
            val payloadName = values[KEY_PAYLOAD] ?: return null
            if (!payloadName.startsWith("${fileKey(vaultIdentity)}.") ||
                !payloadName.endsWith(PAYLOAD_SUFFIX)
            ) {
                return null
            }
            val payload = File(directory, payloadName)
            if (!isDigest(targetIdentity) || remotePath.isBlank() || expectedEtag.isBlank() ||
                !isDigest(digest) || !isRollbackScope(rollbackScope) ||
                size !in 1..MAX_PENDING_UPLOAD_BYTES || payload.length() != size
            ) {
                return null
            }
            PendingEncryptedUpload(targetIdentity, remotePath, expectedEtag, digest, rollbackScope, size, payloadName)
        } catch (_: Exception) {
            null
        }
    }

    fun hasEntry(vaultIdentity: String): Boolean {
        val prefix = "${fileKey(vaultIdentity)}."
        return metadataFile(vaultIdentity).exists() ||
            directory.listFiles().orEmpty().any { it.name.startsWith(prefix) && it.name.endsWith(PAYLOAD_SUFFIX) }
    }

    fun readPayload(vaultIdentity: String, record: PendingEncryptedUpload): ByteArray? {
        val payload = File(directory, record.payloadFileName)
        if (!payload.isFile || payload.length() != record.sizeBytes || record.sizeBytes > Int.MAX_VALUE) return null
        return try {
            val bytes = payload.readBytes()
            if (SyncCache.sha256Hex(bytes) == record.contentDigest) bytes else {
                bytes.fill(0)
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(vaultIdentity: String): Boolean {
        val metadata = metadataFile(vaultIdentity)
        val keyPrefix = "${fileKey(vaultIdentity)}."
        val payloadsRemoved = directory.listFiles().orEmpty()
            .filter { it.name.startsWith(keyPrefix) && it.name.endsWith(PAYLOAD_SUFFIX) }
            .all { !it.exists() || it.delete() }
        val metadataRemoved = !metadata.exists() || metadata.delete()
        return payloadsRemoved && metadataRemoved
    }

    fun clearAll(): Boolean {
        if (!directory.exists()) return true
        val files = directory.listFiles() ?: return false
        return files.filter { it.name.endsWith(PAYLOAD_SUFFIX) || it.name.endsWith(METADATA_SUFFIX) }
            .all { !it.exists() || it.delete() }
    }

    private fun writeMetadata(vaultIdentity: String, record: PendingEncryptedUpload) {
        val lines = listOf(
            "$KEY_VERSION=$VERSION",
            "$KEY_TARGET=${encode(record.targetIdentity)}",
            "$KEY_PATH=${encode(record.remotePath)}",
            "$KEY_ETAG=${encode(record.expectedEtag)}",
            "$KEY_DIGEST=${record.contentDigest}",
            "$KEY_ROLLBACK_SCOPE=${record.rollbackVaultScope}",
            "$KEY_SIZE=${record.sizeBytes}",
            "$KEY_PAYLOAD=${record.payloadFileName}"
        )
        AtomicFileWriter.writeAtomic(metadataFile(vaultIdentity), createBackup = false) { output ->
            output.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    private fun stagedPayloadFile(vaultIdentity: String): File =
        File(directory, "${fileKey(vaultIdentity)}.${UUID.randomUUID()}$PAYLOAD_SUFFIX")
    private fun metadataFile(vaultIdentity: String): File = File(directory, fileKey(vaultIdentity) + METADATA_SUFFIX)

    private fun fileKey(vaultIdentity: String): String =
        SyncCache.sha256Hex(vaultIdentity.toByteArray(Charsets.UTF_8))

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String?): String? = value?.let {
        runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
    }

    private fun isDigest(value: String): Boolean = DIGEST_PATTERN.matches(value)
    private fun isRollbackScope(value: String): Boolean = ROLLBACK_SCOPE_PATTERN.matches(value)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        internal const val DIRECTORY_NAME = "periodic_sync_pending"
        const val VERSION = "1"
        const val KEY_VERSION = "version"
        const val KEY_TARGET = "target"
        const val KEY_PATH = "path"
        const val KEY_ETAG = "etag"
        const val KEY_DIGEST = "digest"
        const val KEY_ROLLBACK_SCOPE = "rollback_scope"
        const val KEY_SIZE = "size"
        const val KEY_PAYLOAD = "payload"
        const val PAYLOAD_SUFFIX = ".kdbx"
        const val METADATA_SUFFIX = ".meta"
        const val MAX_PENDING_UPLOAD_BYTES = SyncDownloadLimits.MAX_DOWNLOAD_BYTES
        val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
        val ROLLBACK_SCOPE_PATTERN = Regex("[0-9A-Fa-f]{32}")
    }
}
