package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import java.io.File

/**
 * 漂移处置的「以磁盘为准」重载通道（`ISSUE-P2-645`：自 `VaultFileDriftResolve` 拆分，纯搬运）。
 *
 * 覆盖本地 `File` 与 SAF（`content://`）两通道：本地直读文件，SAF 经同一 `openStream` 通道
 * （与解锁 / 建库共用写回实现）。凭据副本在本方法内复制并在 `finally` 清零，
 * 不跨调用保留。
 */
internal class VaultFileDriftReloader(
    private val databaseSession: DatabaseSession,
    private val strings: StringsProvider,
    private val driftCoordinator: VaultFileDriftCoordinator?,
    private val sourceAccess: VaultFileDriftSourceAccess,
    private val onBaselineRefreshed: (String, File?) -> Unit
) {

    /** 磁盘库文件不可读的统一失败（重载 / 合并各自的文案入口）。 */
    fun unreadable(choice: ExternalModificationChoice): KdbxResult.Failure =
        KdbxResult.Failure(
            IllegalStateException("磁盘库文件不可读"),
            strings.get(
                if (choice == ExternalModificationChoice.RELOAD_FROM_DISK) R.string.ext_mod_reload_failed
                else R.string.ext_mod_merge_failed,
                strings.get(R.string.err_drift_file_unreadable)
            )
        )

    /** 重载：以磁盘版本替换会话树；失败即重建提示，内存改动由调用方保留。 */
    suspend fun reload(pathId: String, localFile: File?): KdbxResult<Unit> {
        val saf = if (localFile == null) sourceAccess.safChannel(pathId) else null
        if (localFile == null && saf == null) return unreadable(ExternalModificationChoice.RELOAD_FROM_DISK)
        if (localFile != null && !localFile.exists()) {
            return unreadable(ExternalModificationChoice.RELOAD_FROM_DISK)
        }
        var pwdCopy: CharArray? = null
        var keyCopy: ByteArray? = null
        databaseSession.useCredentials { pwd, key ->
            pwdCopy = pwd?.copyOf()
            keyCopy = key?.copyOf()
        }
        val reload = try {
            if (localFile != null) {
                databaseSession.open(localFile, pwdCopy, keyCopy, readOnly = false)
            } else {
                // SAF：与解锁 / 建库同一条 openStream 通道（写回仍走同一 saveWriter 实现）
                // 句柄非空由上方早退分支保证（localFile == null ⇒ saf != null），此处显式取非空值
                val safHandle = saf
                    ?: return unreadable(ExternalModificationChoice.RELOAD_FROM_DISK)
                databaseSession.openStream(
                    pathIdentifier = pathId,
                    inputStreamProvider = { safHandle.openInput() },
                    saveWriter = safHandle.saveWriter,
                    passwordChars = pwdCopy,
                    keyFileData = keyCopy,
                    readOnly = false
                )
            }
        } finally {
            pwdCopy?.fill('0')
            keyCopy?.fill(0)
        }
        if (reload is KdbxResult.Success) {
            onBaselineRefreshed(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
        return reload
    }
}
