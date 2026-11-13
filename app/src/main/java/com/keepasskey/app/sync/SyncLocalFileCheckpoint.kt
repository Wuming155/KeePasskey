package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.database.session.StableDatabaseFileDigest
import com.keepasskey.sync.engine.SyncCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已成功同步的本地密文版本证明，跨锁库及进程重启保留。
 *
 * 只保存摘要，不保存密码、条目或整库快照。键按库路径隔离，值绑定云端目标、远端路径、
 * 本地写入目标及密文摘要；目标变化、文件重写或记录缺失时一律回退合并，不能据此丢弃本地编辑。
 * 记录与同步凭据共用偏好文件，撤销全部或单库配置时同步清除。
 *
 * 调用者仅在已收敛后，使用会话提交锁内捕获且绑定已确认树实例的回执登记；
 * 待同步、冲突及失败不得登记。SAF 使用会话读取或写盘确认的密文基线摘要。
 */
@Singleton
class SyncLocalFileCheckpoint @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(SyncCredentialsStore.PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun isUnchangedSinceSync(
        remotePath: String,
        targetIdentity: String?,
        currentRevision: StableDatabaseFileDigest?
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = targetIdentity ?: return@withLock false
            val revision = currentRevision ?: return@withLock false
            prefs.getString(keyFor(revision), null) == proofFor(remotePath, target, revision)
        }
    }

    suspend fun record(
        remotePath: String,
        targetIdentity: String?,
        revision: StableDatabaseFileDigest?
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = targetIdentity ?: return@withLock
            revision ?: return@withLock
            prefs.edit().putString(keyFor(revision), proofFor(remotePath, target, revision)).apply()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(SyncCredentialKeys.LOCAL_SYNC_CHECKPOINT + "_") }
                .forEach(editor::remove)
            editor.apply()
        }
    }

    private fun keyFor(revision: StableDatabaseFileDigest): String =
        SyncCredentialKeys.scoped(SyncCredentialKeys.LOCAL_SYNC_CHECKPOINT, revision.pathIdentifier)

    private fun proofFor(remotePath: String, targetIdentity: String, revision: StableDatabaseFileDigest): String =
        SyncCache.sha256Hex(
            listOf(remotePath, targetIdentity, revision.pathIdentifier, revision.filePath, revision.sha256)
                .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        )
}

/** 仅与远端已收敛的结论允许记录本地密文版本证明。 */
internal fun SyncOutcome.isInSyncWithRemote(): Boolean = when (this) {
    is SyncOutcome.UpToDate,
    is SyncOutcome.UploadedLocal,
    is SyncOutcome.MergedAndUploaded -> true

    is SyncOutcome.ConflictNeedsUser,
    is SyncOutcome.Offline,
    is SyncOutcome.Error,
    is SyncOutcome.VaultBindingMismatch,
    is SyncOutcome.LocalPendingChanges -> false
}
