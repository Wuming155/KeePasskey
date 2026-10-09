package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaseline
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileMetadataProbe
import com.keepasskey.app.sync.eraseDiscardedDatabase
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.KdbxDatabaseLite
import com.keepasskey.sync.merge.KdbxMerger
import java.io.File
import java.io.IOException
import java.io.InputStream

/** ISSUE-P3-550：异常细节（含库文件本地路径）只进日志，UI 侧只留错误码映射。 */
private const val TAG = "VaultFileDriftResolve"

/**
 * ISSUE-P2-378：漂移后的重载 / 合并执行体（自 `RealVaultRepository.reloadOrMergeAfterDrift` 拆出）。
 *
 * 口径：
 * - 重载：克隆会话凭据后重新 open 磁盘版本（丢弃内存未落盘改动）；
 * - 合并：读磁盘 → 会话解析 → KdbxMerger 三方合并 → adoptDatabaseIfUnchanged → save；
 * - 任一步失败：保留内存树与脏标记，失败原因原样上浮（KeePassXC 811887e5）。
 *
 * `ISSUE-P3-447` AC②：本件同时覆盖**本地 `File` 与 SAF（`content://`）两通道**——
 * 此前两分支都硬要求 `localFile != null`，SAF 库一旦进入漂移处置就只能得到
 * 「磁盘库文件不可读」，即「检出漂移却无法重载 / 合并」的死路。现按通道取字节与重开流：
 * 本地直读文件，SAF 经 `ContentResolver`（与解锁 / 建库共用 [SafVaultCreation] 的写回实现），
 * 处置成功后的基线刷新同口径分流（本地直读属性 / SAF 取真实文档元数据）。
 */
internal class VaultFileDriftResolve(
    private val databaseSession: DatabaseSession,
    private val strings: StringsProvider,
    private val driftCoordinator: VaultFileDriftCoordinator?,
    /**
     * `ISSUE-P3-447` AC②：SAF 通道重载 / 合并所需的上下文。null（纯 JVM 单测未装配）时
     * SAF 通道按「磁盘库文件不可读」fail-closed——不得静默放行。
     */
    private val context: Context? = null
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
            // ISSUE-P3-550：`t.message` 携带**库文件本地路径**（漂移处置的异常常来自
            // 文件通道），只进日志；UI 侧统一走错误码映射（未归类 ⇒ `err_unknown`）
            AppLog.w(TAG, "漂移处置失败: choice=$choice", t)
            val msg = if (choice == ExternalModificationChoice.RELOAD_FROM_DISK) {
                strings.get(R.string.ext_mod_reload_failed, strings.get(R.string.err_unknown))
            } else {
                strings.get(R.string.ext_mod_merge_failed, strings.get(R.string.err_unknown))
            }
            KdbxResult.Failure(t, msg)
        }
    }

    private suspend fun reloadFromDisk(
        pathId: String,
        localFile: File?
    ): KdbxResult<Unit> {
        val saf = if (localFile == null) safChannel(pathId) else null
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
                databaseSession.openStream(
                    pathIdentifier = pathId,
                    inputStreamProvider = { saf!!.openInput() },
                    saveWriter = saf!!.saveWriter,
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
            refreshBaseline(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
        return reload
    }

    private suspend fun mergeAndSave(
        pathId: String,
        localFile: File?
    ): KdbxResult<Unit> {
        val diskBytes = readCurrentBytes(localFile, pathId)
            ?: return unreadable(ExternalModificationChoice.MERGE_AND_SAVE)
        val diskDb = databaseSession.parseExternalDatabase(diskBytes).getOrThrow()
        val localDb = databaseSession.databaseFlow.value
            ?: return KdbxResult.Failure(
                IllegalStateException("无活动数据库"),
                strings.get(R.string.ext_mod_merge_failed, strings.get(R.string.err_drift_no_active_database))
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
                strings.get(
                    R.string.ext_mod_merge_failed,
                    strings.get(R.string.err_drift_session_tree_changed)
                )
            )
        }
        val saveResult = databaseSession.save()
        if (saveResult is KdbxResult.Success) {
            refreshBaseline(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
        // ISSUE-P3-471：此处**必须**走身份集合判定，不得裸调 `diskDb.clearSensitiveData()`——
        // 合并器对「磁盘独有 / 磁盘胜出的条目」与「磁盘独有的图标」**复用原实例**，采用后这些
        // 实例同时可达于活动会话树（`SECURITY_RECHECK_2026-09.md` §9.6 #19 同型）；裸擦会把活动库
        // 仍在用的口令 / 图标字节清零。存活侧取**当前**会话树（`save()` 可能因历史修剪再换实例）。
        eraseDiscardedDatabase(diskDb, live = databaseSession.databaseFlow.value)
        return saveResult
    }

    /** 处置成功后的基线刷新：本地直读文件属性；SAF 取真实文档元数据（不可读即置空） */
    private fun refreshBaseline(pathId: String, localFile: File?) {
        val baseline: VaultFileBaseline? =
            if (localFile != null) VaultFileBaseline.fromFile(localFile)
            else VaultFileMetadataProbe.baselineFor(context, pathId)
        driftCoordinator?.refreshBaselineAfterPersist(baseline)
    }

    /**
     * 当前库的**可读字节**（本地 `File` 直读；SAF 经 `ContentResolver` 读入）。
     * 不可读返回 null，由调用方按 fail-closed 报「磁盘库文件不可读」。
     */
    private fun readCurrentBytes(localFile: File?, pathId: String): ByteArray? {
        if (localFile != null) return if (localFile.exists()) localFile.readBytes() else null
        val saf = safChannel(pathId) ?: return null
        return runCatching { saf.openInput().use { it.readBytes() } }.getOrNull()
    }

    /** SAF 文档句柄（读流 + 写回）；非 `content://` 或未装配上下文时返回 null */
    private fun safChannel(pathId: String): SafChannel? {
        val ctx = context ?: return null
        if (!VaultFileMetadataProbe.isSafPath(pathId)) return null
        val uri = runCatching { Uri.parse(pathId) }.getOrNull() ?: return null
        return SafChannel(ctx, uri)
    }

    private fun unreadable(choice: ExternalModificationChoice): KdbxResult.Failure =
        KdbxResult.Failure(
            IllegalStateException("磁盘库文件不可读"),
            strings.get(
                if (choice == ExternalModificationChoice.RELOAD_FROM_DISK) R.string.ext_mod_reload_failed
                else R.string.ext_mod_merge_failed,
                strings.get(R.string.err_drift_file_unreadable)
            )
        )

    /** SAF 文档句柄：读流与写回共用同一 Uri 与上下文（写回实现与解锁 / 建库共用同一份） */
    private class SafChannel(private val context: Context, private val uri: Uri) {

        fun openInput(): InputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开数据库文件流")

        val saveWriter: suspend (ByteArray) -> Unit
            get() = SafVaultCreation.saveWriter(context, uri, uri.lastPathSegment.orEmpty())
    }
}
