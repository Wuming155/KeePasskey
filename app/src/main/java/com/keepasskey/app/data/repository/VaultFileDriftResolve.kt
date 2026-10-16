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
import com.keepasskey.app.util.StreamLimitExceededException
import com.keepasskey.app.util.readBoundedBytes
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

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
 *
 * `ISSUE-P1-631`：合并的共同基线取**最后确认持久化版本**（`DatabaseSession.persistedBaselineSnapshot`，
 * 绑定库身份与持久化修订）——不得把当前可变树同时充作 base 与 local（那会把本地新增条目
 * 同化为「远端已删除」、把本地编辑让位给磁盘旧值，静默丢失修改）。基线缺不可信 / 缺失时
 * **不得静默做删除裁决**：按空底版双方并集语义合并（单侧新建保留、同 UUID 条目字段级合并、
 * 同字段分叉进入冲突清单）。合并发现同一字段两侧都有修改时**不静默取一侧**：置待裁决提示
 * （`VaultFileDriftCoordinator.mergeConflict`），由用户以「本地 / 磁盘 / 双方保留」三选裁决，
 * 裁决结果驱动同一合并实现重算后，在会话修订校验（实例身份 + 基线修订，同一临界区）通过后提交。
 */
internal class VaultFileDriftResolve(
    private val databaseSession: DatabaseSession,
    private val strings: StringsProvider,
    private val driftCoordinator: VaultFileDriftCoordinator?,
    /**
     * `ISSUE-P3-447` AC②：SAF 通道重载 / 合并所需的上下文。null（纯 JVM 单测未装配）时
     * SAF 通道按「磁盘库文件不可读」fail-closed——不得静默放行。
     */
    private val context: Context? = null,
    /**
     * `ISSUE-P3-617`：源文件读取上限；默认生产上限 [MAX_DRIFT_READ_BYTES]，
     * 单测注入更小值即可低成本覆盖超限分支（与 `ImportJson.Limits` 同构）。
     */
    private val maxDriftReadBytes: Long = MAX_DRIFT_READ_BYTES
) {

    /** `ISSUE-P1-631`：真实基线解析与冲突裁决支持（实现见 [VaultFileDriftMergeSupport]） */
    private val mergeSupport = VaultFileDriftMergeSupport(databaseSession, strings, driftCoordinator)

    suspend fun resolve(
        choice: ExternalModificationChoice
    ): KdbxResult<Unit> {
        val pathId = databaseSession.currentPathIdentifier.orEmpty()
        val localFile = databaseSession.currentFile
        return try {
            when (choice) {
                ExternalModificationChoice.ABANDON_SAVE -> {
                    driftCoordinator?.clearMergeConflictPrompt()
                    KdbxResult.Success(Unit)
                }

                ExternalModificationChoice.RELOAD_FROM_DISK -> {
                    driftCoordinator?.clearMergeConflictPrompt()
                    reloadFromDisk(pathId, localFile)
                }

                ExternalModificationChoice.MERGE_AND_SAVE ->
                    mergeAndSave(pathId, localFile, choice.mergePolicy)

                ExternalModificationChoice.MERGE_KEEP_LOCAL,
                ExternalModificationChoice.MERGE_KEEP_DISK,
                ExternalModificationChoice.MERGE_KEEP_BOTH -> {
                    // 裁决已受理：先关提示（重算期间不残留对话框）；失败路径由 requestPrompt 归还三选
                    driftCoordinator?.clearMergeConflictPrompt()
                    mergeAndSave(pathId, localFile, choice.mergePolicy)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            driftCoordinator?.requestPrompt(pathId)
            // ISSUE-P3-550：`t.message` 携带**库文件本地路径**（漂移处置的异常常来自
            // 文件通道），只进日志；UI 侧统一走错误码映射（未归类 ⇒ `err_unknown`）
            AppLog.w(DRIFT_LOG_TAG, "漂移处置失败: choice=$choice", t)
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
            refreshBaseline(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
        return reload
    }

    private suspend fun mergeAndSave(
        pathId: String,
        localFile: File?,
        policy: DriftMergeConflictPolicy?
    ): KdbxResult<Unit> {
        val diskBytes = readCurrentBytes(localFile, pathId)
            ?: return unreadable(ExternalModificationChoice.MERGE_AND_SAVE)
        val diskDb = databaseSession.parseExternalDatabase(diskBytes).getOrThrow()
        var baseDb: KdbxDatabase? = null
        try {
            val localDb = databaseSession.databaseFlow.value
                ?: return KdbxResult.Failure(
                    IllegalStateException("无活动数据库"),
                    strings.get(R.string.ext_mod_merge_failed, strings.get(R.string.err_drift_no_active_database))
                )
            // ISSUE-P1-631：真实共同基线（最后确认持久化版本）；不可信 / 缺失 ⇒ 空底版并集语义。
            // 解析出的基线树由本方法独占（合并器不会把 base 节点复用进产物），随 diskDb 一并回收。
            val baseline = mergeSupport.resolveTrustedBaseline(pathId)
            baseDb = baseline?.database
            val merged = KdbxMerger.mergeDatabases(
                base = mergeSupport.baselineLiteOf(baseDb),
                local = localDb.toMergeLite(),
                remote = diskDb.toMergeLite()
            )
            val mergedRoot = mergeSupport.resolveConflictsBeforeAdopt(merged, policy, pathId)
                ?: return KdbxResult.Failure(
                    IllegalStateException("漂移合并存在待裁决冲突"),
                    strings.get(R.string.ext_mod_merge_conflict_pending, merged.conflicts.size)
                )
            return adoptAndPersist(localDb, merged, mergedRoot, baseline?.revision, pathId, localFile)
        } finally {
            // ISSUE-P3-471：此处**必须**走身份集合判定，不得裸调 `diskDb.clearSensitiveData()`——
            // 合并器对「磁盘独有 / 磁盘胜出的条目」与「磁盘独有的图标」**复用原实例**，采用后这些
            // 实例同时可达于活动会话树（`SECURITY_RECHECK_2026-09.md` §9.6 #19 同型）；裸擦会把活动库
            // 仍在用的口令 / 图标字节清零。finally 同时覆盖解析后的早退、取消和异常；存活侧取**当前**
            // 会话树（`save()` 可能因历史修剪再换实例），其 root/history attachment sources 也参与 spill 判定。
            //
            // `ISSUE-P1-631`：基线解析树同口径——合并器**不会**把 base 节点复用进产物
            // （存活节点只源自 local / remote 或二者的 copy），故基线树必然在存活侧之外；
            // 仍走身份集合判定，使其附件 spill key 按同一契约释放。
            val live = databaseSession.databaseFlow.value
            baseDb?.let { eraseDiscardedDatabase(it, live) }
            eraseDiscardedDatabase(diskDb, live)
        }
    }

    /**
     * `ISSUE-P1-631`：提交侧——同一临界区校验（会话树实例身份 + 持久化基线修订）通过后
     * 采用合并产物并落盘；成功刷新文件元数据基线，失败如实上浮并归还三选提示。
     */
    private suspend fun adoptAndPersist(
        localDb: KdbxDatabase,
        merged: MergeResult,
        mergedRoot: KdbxGroup,
        baselineRevision: Long?,
        pathId: String,
        localFile: File?
    ): KdbxResult<Unit> {
        val adopted = databaseSession.adoptDatabaseIfUnchanged(
            expectedAtCycleStart = localDb,
            replacement = localDb.copy(
                rootGroup = mergedRoot,
                deletedObjects = merged.mergedDeletedObjects,
                customIcons = merged.mergedCustomIcons
            ),
            // 基线修订与实例身份在同一临界区校验：窗口内确认写盘 / 会话变化 ⇒ fail-closed
            expectedPersistedBaselineRevision = baselineRevision
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
            driftCoordinator?.clearMergeConflictPrompt()
            refreshBaseline(pathId, localFile)
        } else {
            driftCoordinator?.requestPrompt(pathId)
        }
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
     *
     * `ISSUE-P3-617`：两处整流 `readBytes()` 改为带硬上限的分块读取——未知或错误申报长度
     * 可绕过读取前检查，超大输入会在解析限制生效前分配完整 `ByteArray`（OOM/ANR）。
     * 本地文件先按 `length()` 快拒（精确长度已知，不建流）；SAF 流（含未知长度）一律由
     * [readBoundedBytes] 累计封顶，超限按不可读同口径返回 null（`resolve` 侧报合并 / 重载失败，
     * 不抛 OOM）。上限见 [MAX_DRIFT_READ_BYTES]。
     */
    private fun readCurrentBytes(localFile: File?, pathId: String): ByteArray? {
        if (localFile != null) {
            if (!localFile.exists()) return null
            // 快拒：本地文件长度精确已知，超限根本不读进内存
            if (localFile.length() > maxDriftReadBytes) {
                AppLog.w(DRIFT_LOG_TAG, "漂移源文件超出读取上限（>${maxDriftReadBytes} 字节），拒绝合并")
                return null
            }
            return runCatching { localFile.inputStream().use { it.readBoundedBytes(maxDriftReadBytes) } }
                .onFailure { AppLog.w(DRIFT_LOG_TAG, "漂移源文件读取失败: ${it.javaClass.simpleName}") }
                .getOrNull()
        }
        val saf = safChannel(pathId) ?: return null
        return runCatching { saf.openInput().use { it.readBoundedBytes(maxDriftReadBytes) } }
            .onFailure {
                // 超限与 IO 失败同按不可读处置（日志只记类型与上限，不记路径——ISSUE-P3-550）
                if (it is StreamLimitExceededException) {
                    AppLog.w(DRIFT_LOG_TAG, "漂移源 SAF 流超出读取上限（>${maxDriftReadBytes} 字节），拒绝合并")
                } else {
                    AppLog.w(DRIFT_LOG_TAG, "漂移源 SAF 流读取失败: ${it.javaClass.simpleName}")
                }
            }
            .getOrNull()
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

    internal companion object {
        /**
         * 漂移源文件读取上限：160 MiB（`ISSUE-P3-617`）。
         *
         * 取值推导（禁字面量拍脑袋）：`KdbxFile` 解析侧以 128 MiB 解压输出守卫封顶任何可解析库
         * （`MAX_DECOMPRESSED_PAYLOAD_BYTES`，database 模块内部常量，此处按值引用）；
         * 密文体 ≈ 明文体（不压缩时）或更小（GZip 时）+ HMAC 块帧与头部开销（< 1%），
         * 故任何可被解析的库其源文件必远小于 160 MiB——超限即合并 / 重载失败 fail-closed，
         * 不再无界分配。与同步下载上限（`SyncDownloadLimits.MAX_DOWNLOAD_BYTES = 128 MiB`，
         * 约束**远端**响应体）不冲突：本上限约束本地 / SAF 源文件的**物化**，合法库两侧恒通过。
         */
        const val MAX_DRIFT_READ_BYTES: Long = 160L * 1024 * 1024
    }
}
