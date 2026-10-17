package com.keepasskey.app.sync

import android.content.Context
import android.net.Uri
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.network.SyncDownloadLimits
import com.keepasskey.sync.model.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 锁态远端探测的协议无关支撑（`ISSUE-P2-645`：自 `SyncCoordinator` 拆分）。
 *
 * 只放**无状态**件：目标身份摘要、默认远端文件名、密文摘要、失败分型与待决探测状态的写入。
 * 编排（何时探测、何时上传）在 [SyncLockedProbeRunner] / [SyncLockedUploader]。
 */
internal object SyncProbeSupport {

    const val CONTENT_URI_PREFIX = "content://"

    /** SAF 冷启动无法还原库名时的哨兵远端文件名：不会发送请求，命中即按安全失败处理。 */
    const val UNKNOWN_DEFAULT_REMOTE_FILE_NAME = "__keepasskey_unknown_remote_name__"

    private const val CIPHER_DIGEST_BUFFER_BYTES = 64 * 1024
    private val CIPHER_SNAPSHOT_MAX_BYTES = SyncDownloadLimits.MAX_DOWNLOAD_BYTES

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500

    private val AUTH_STATUS_CODES = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)
    private val TRANSIENT_STATUS_CODES = setOf(HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS)

    /** 探测失败分型（只按可重试性/身份性归类，不回传服务器响应正文）。 */
    enum class LockedProbeFailure { AUTH, TEMPORARY, MISSING, REJECTED }

    /** 「库身份 + 目标身份」的稳定摘要：ETag 只可与建立它时的同一目标比较。 */
    fun targetIdentity(vaultIdentity: String, targetIdentity: String): String {
        val bytes = "$vaultIdentity\u0000$targetIdentity".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    /** 远端默认文件名：优先当前打开的文件名，其次库名，再次本地路径的末段。 */
    fun defaultFileName(databaseSession: DatabaseSession, vaultIdentity: String): String {
        databaseSession.currentFile?.name?.takeIf(String::isNotBlank)?.let { return it }
        databaseSession.databaseFlow.value?.databaseName?.takeIf(String::isNotBlank)?.let { return "$it.kdbx" }
        if (!vaultIdentity.startsWith(CONTENT_URI_PREFIX)) {
            File(vaultIdentity).name.takeIf(String::isNotBlank)?.let { return it }
        }
        // 锁定时无法从 SAF URI 还原 KDBX 名称。该哨兵路径不会发送请求；依赖此回退名时按安全失败处理。
        return UNKNOWN_DEFAULT_REMOTE_FILE_NAME
    }

    fun isUnresolvedDefaultPath(remotePath: String, vaultIdentity: String): Boolean {
        val normalized = remotePath.trim().trimStart('/')
        if (normalized == UNKNOWN_DEFAULT_REMOTE_FILE_NAME) return true
        // SAF 冷启动 URI 无法还原 KDBX 文件名。Provider 默认路径即使语法有效也可能指向错误文件，
        // 因此只有明确配置的非默认路径才允许探测。
        return vaultIdentity.startsWith(CONTENT_URI_PREFIX) &&
            normalized == WebDavDefaults.DEFAULT_REMOTE_PATH
    }

    fun classify(failure: Throwable): LockedProbeFailure = when {
        failure is SyncException.AuthenticationError -> LockedProbeFailure.AUTH
        failure is SyncException.ProtocolError && failure.statusCode in AUTH_STATUS_CODES -> LockedProbeFailure.AUTH
        failure is SyncException.ProtocolError &&
            (failure.statusCode in TRANSIENT_STATUS_CODES || failure.statusCode >= HTTP_SERVER_ERROR) ->
            LockedProbeFailure.TEMPORARY
        failure is SyncException.NetworkError || failure is IOException -> LockedProbeFailure.TEMPORARY
        failure is SyncException.FileNotFound -> LockedProbeFailure.MISSING
        else -> LockedProbeFailure.REJECTED
    }

    /** 当前磁盘密文的 SHA-256（超上限或读失败即 null：调用方按「不可判定」安全失败）。 */
    suspend fun cipherDigest(context: Context, vaultIdentity: String): String? = withContext(Dispatchers.IO) {
        val input = openSnapshotStream(context, vaultIdentity) ?: return@withContext null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CIPHER_DIGEST_BUFFER_BYTES)
            var total = 0L
            input.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > CIPHER_SNAPSHOT_MAX_BYTES) return@withContext null
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun openSnapshotStream(context: Context, vaultIdentity: String): InputStream? = try {
        if (vaultIdentity.startsWith(CONTENT_URI_PREFIX)) {
            context.contentResolver.openInputStream(Uri.parse(vaultIdentity))
        } else {
            File(vaultIdentity).takeIf { it.isFile }?.inputStream()
        }
    } catch (_: Exception) {
        null
    }

    /** 写入「需要解锁」的探测记录，并把写入失败如实降级为 `PROBE_STATE_WRITE_FAILED`。 */
    fun writePendingState(
        store: PeriodicSyncProbeStore,
        vaultIdentity: String,
        targetIdentity: String,
        observedEtag: String,
        reason: PeriodicSyncNeedsUnlockReason
    ): PeriodicSyncWorkResult {
        val previous = (store.read(vaultIdentity) as? PeriodicSyncProbeRead.Stored)?.record
            ?.takeIf { it.targetIdentity == targetIdentity }
        val written = store.write(
            vaultIdentity,
            PeriodicSyncProbeRecord(
                targetIdentity = targetIdentity,
                observedEtag = observedEtag,
                needsUnlock = true,
                syncedContentDigest = previous?.syncedContentDigest,
                rollbackVaultScope = previous?.rollbackVaultScope
            )
        )
        return PeriodicSyncWorkResult.NeedsUnlock(
            if (written) reason else PeriodicSyncNeedsUnlockReason.PROBE_STATE_WRITE_FAILED
        )
    }
}
