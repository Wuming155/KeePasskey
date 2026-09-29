package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import java.io.File

/**
 * ISSUE-P2-378：漂移后的重载 / 合并执行体（自 `RealVaultRepository.reloadOrMergeAfterDrift` 拆出）。
 *
 * 口径：
 * - 重载：克隆会话凭据后重新 open 磁盘版本（丢弃内存未落盘改动）；
 * - 合并：读磁盘 → 会话解析 → KdbxMerger 三方合并 → adoptDatabaseIfUnchanged → save；
 * - 任一步失败：保留内存树与脏标记，失败原因原样上浮（KeePassXC 811887e5）。
 */
internal class VaultFileDriftResolve(
    private val databaseSession: DatabaseSession,
    private val strings: StringsProvider,
    private val driftCoordinator: VaultFileDriftCoordinator?
) {

    suspend fun resolve(
        choice: ExternalModificationChoice
    ): KdbxResult<Unit> {
        val pathId = databaseSession.currentPathIdentifier.orEmpty()
        val localFile = databaseSession.currentFile
        return try {
            when (choice) {
                ExternalModificationChoice.ABANDON_SAVE -> KdbxResult.Success(Unit)

                ExternalModificationChoice.RELOAD_FROM_DISK ->
                    reloadFromDisk(pathId, localFile)

                ExternalModificationChoice.MERGE_AND_SAVE ->
                    mergeAndSave(pathId, localFile)
            }
        } catch (t: Throwable) {
            driftCoordinator?.requestPrompt(pathId)
            val msg = if (choice == ExternalModificationChoice.RELOAD_FROM_DISK) {
                strings.get(R.string.ext_mod_reload_failed, t.message ?: "")
            } else {
                strings.get(R.string.ext_mod_merge_failed, t.message ?: "")
            }
            KdbxResult.Failure(t, msg)
        }
    }

    private suspend fun reloadFromDisk(
        pathId: String,
        localFile: File?
    ): KdbxResult<Unit> {
        if (localFile == null || !localFile.exists()) {
            return KdbxResult.Failure(
                IllegalStateException("磁盘库文件不可读"),
                strings.get(R.string.ext_mod_reload_failed, "文件不可读")
            )
        }
        var pwdCopy: CharArray? = null
        var keyCopy: ByteArray? = null
        databaseSession.useCredentials { pwd, key ->
            pwdCopy = pwd?.copyOf()
            keyCopy = key?.copyOf()
        }
        val reload = try {
            databaseSession.open(localFile, pwdCopy, keyCopy, readOnly = false)
        } finally {
            pwdCopy?.fill('0')
            keyCopy?.fill(0)
        }
        if (reload is KdbxResult.Success) {
            driftCoordinator?.refreshBaselineAfterPersist(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
        return reload
    }

    private suspend fun mergeAndSave(
        pathId: String,
        localFile: File?
    ): KdbxResult<Unit> {
        if (localFile == null || !localFile.exists()) {
            return KdbxResult.Failure(
                IllegalStateException("磁盘库文件不可读"),
                strings.get(R.string.ext_mod_merge_failed, "文件不可读")
            )
        }
        val diskBytes = localFile.readBytes()
        val diskDb = databaseSession.parseExternalDatabase(diskBytes).getOrThrow()
        val localDb = databaseSession.databaseFlow.value
            ?: return KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                strings.get(R.string.ext_mod_merge_failed, "无活动数据库")
            )
        val baseLite = KdbxDatabaseLite(
            rootGroup = localDb.rootGroup,
            deletedObjects = localDb.deletedObjects,
            customIcons = localDb.customIcons
        )
        val localLite = KdbxDatabaseLite(
            rootGroup = localDb.rootGroup,
            deletedObjects = localDb.deletedObjects,
            customIcons = localDb.customIcons
        )
        val remoteLite = KdbxDatabaseLite(
            rootGroup = diskDb.rootGroup,
            deletedObjects = diskDb.deletedObjects,
            customIcons = diskDb.customIcons
        )
        val merged = KdbxMerger.mergeDatabases(
            base = baseLite,
            local = localLite,
            remote = remoteLite
        )
        val expected = localDb
        val adopted = databaseSession.adoptDatabaseIfUnchanged(
            expectedAtCycleStart = expected,
            replacement = expected.copy(
                rootGroup = merged.mergedRoot,
                deletedObjects = merged.mergedDeletedObjects,
                customIcons = merged.mergedCustomIcons
            )
        )
        if (!adopted) {
            return KdbxResult.Failure(
                IllegalStateException("合并窗口内会话树已变化"),
                strings.get(R.string.ext_mod_merge_failed, "合并窗口内会话树已变化")
            )
        }
        val saveResult = databaseSession.save()
        if (saveResult is KdbxResult.Success) {
            driftCoordinator?.refreshBaselineAfterPersist(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
        diskDb.clearSensitiveData()
        return saveResult
    }
}
