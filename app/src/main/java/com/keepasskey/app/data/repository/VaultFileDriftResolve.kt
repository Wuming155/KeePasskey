package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaseline
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.security.VaultFileMetadataProbe
import com.keepasskey.app.sync.withMergedMeta
import com.keepasskey.app.sync.eraseDiscardedDatabase
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.util.StreamLimitExceededException
import com.keepasskey.app.util.readBoundedBytes
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.PreparedSerializedSave
import com.keepasskey.sync.merge.KdbxMerger
import com.keepasskey.sync.merge.MergeResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    private val maxDriftReadBytes: Long = MAX_DRIFT_READ_BYTES,
    /**
     * `ISSUE-P1-631`（并发 barrier 验收）：合并产物**采用之前**的确定性同步点。
     * 生产恒为 null（不引入任何调度 / 延迟）；并发验收以 latch 注入，在受控时点暂停旧任务、
     * 完成换库或二次外部改写后放行，验证 adopt / commit 必然被实例身份、基线修订或
     * 读取版本钉扎拦截——不以概率性 sleep 制造竞态。
     */
    private val mergeCommitGate: (suspend () -> Unit)? = null,
    /**
     * `ISSUE-P1-631`（生命周期验收）：合并流程入口门——用户点击已受理、**重读磁盘前**。
     * 生产恒为 null；仅设备侧生命周期竞态验收注入（见 `DriftMergePhaseGates`）。
     */
    private val mergeStartGate: (suspend () -> Unit)? = null,
    /**
     * `ISSUE-P1-631`（生命周期验收）：提交临界区已进入（`NonCancellable` 区内、元数据复核前）。
     * 生产恒为 null；仅设备侧生命周期竞态验收注入，用于精确验证「临界区开始后宿主销毁，
     * 落盘仍完成」——与 [mergeCommitGate] 留在区外的既有设计不冲突（那是同一钩子，本钩子在区内）。
     */
    private val commitEnteredGate: (suspend () -> Unit)? = null
) {

    /** `ISSUE-P1-631`：磁盘源访问（读字节 / SAF 文档句柄；实现见 [VaultFileDriftSourceAccess]） */
    private val sourceAccess = VaultFileDriftSourceAccess(context, maxDriftReadBytes)

    /** `ISSUE-P1-631`：真实基线解析与冲突裁决支持（实现见 [VaultFileDriftMergeSupport]） */
    private val mergeSupport = VaultFileDriftMergeSupport(databaseSession, strings, driftCoordinator)

    /** `ISSUE-P2-645`：磁盘重载通道（本地 File / SAF 双通道，见 [VaultFileDriftReloader]） */
    private val reloader = VaultFileDriftReloader(
        databaseSession = databaseSession,
        strings = strings,
        driftCoordinator = driftCoordinator,
        sourceAccess = sourceAccess,
        onBaselineRefreshed = ::refreshBaseline
    )

    suspend fun resolve(
        choice: ExternalModificationChoice
    ): KdbxResult<Unit> {
        val pathId = databaseSession.currentPathIdentifier.orEmpty()
        val localFile = databaseSession.currentFile
        // ISSUE-P1-631（裁决归属）：处置只对**最近一次被提示的那个库**生效。用户点击与执行
        // 之间可能发生换库（换库会清空待决提示），若把「以磁盘为准」这类决策套用到用户从未
        // 裁决过的新库上，等同于对该库静默做取舍 ⇒ fail-closed 拒绝并清掉残留提示。
        if (staleChoiceOwner(driftCoordinator, pathId) != null) {
            driftCoordinator?.clearMergeConflictPrompt()
            driftCoordinator?.dismiss()
            AppLog.w(DRIFT_LOG_TAG, "漂移处置已失效：选择属于其它库（库已切换），拒绝执行")
            return KdbxResult.Failure(
                IllegalStateException("漂移处置选择已失效（库已切换）"),
                strings.get(
                    if (choice == ExternalModificationChoice.RELOAD_FROM_DISK) {
                        R.string.ext_mod_reload_failed
                    } else {
                        R.string.ext_mod_merge_failed
                    },
                    strings.get(R.string.err_drift_choice_stale)
                )
            )
        }
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
                    // `ISSUE-P1-631`（生命周期整改，原「受理即先清提示」已废除）：裁决是数据层
                    // 持久化事务，UI 状态**不得领先持久化事实**——重算 + 落盘完成前冲突提示保持
                    // 置起（按钮经 `choiceSubmissionInProgress` 禁用），失败 / 取消时用户仍能
                    // 看到待裁决上下文并重试；成功路径由 [adoptAndPersist] 显式清提示。
                    // 整改前「先清提示」配合组合作用域提交，被设备用例实测复现为：
                    // 宿主销毁 ⇒ 在途提交被取消 ⇒ 「选择已被 UI 消费、提示全清、文件未变」。
                    mergeAndSave(pathId, localFile, choice.mergePolicy)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // `ISSUE-P1-631`（生命周期整改）：裁决（KEEP_*）失败时**保留**冲突提示供重试——
            // 受理不再先清提示，此处不得以 requestPrompt 把仍有效的裁决上下文覆盖成三选；
            // 会话已在窗口内切换时提示会由锁定观察者清空，同样无需（也不应）重建。
            if (choice.mergePolicy == null) {
                driftCoordinator?.requestPrompt(pathId)
            }
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

    private suspend fun reloadFromDisk(pathId: String, localFile: File?): KdbxResult<Unit> =
        reloader.reload(pathId, localFile)

    private suspend fun mergeAndSave(
        pathId: String,
        localFile: File?,
        policy: DriftMergeConflictPolicy?
    ): KdbxResult<Unit> {
        // `ISSUE-P1-631`（生命周期验收）：合并入口确定性暂停点（生产恒 null，零开销）。
        // **仅对裁决重算（policy != null）生效**：生命周期验收针对的是 KEEP_* 提交；
        // 若对首击三选合并同样生效，验收用例自己的「进入裁决」前置就会卡死在自己的门上。
        if (policy != null) mergeStartGate?.invoke()
        val diskBytes = sourceAccess.readCurrentBytes(localFile, pathId)
            ?: return reloader.unreadable(ExternalModificationChoice.MERGE_AND_SAVE)
        // ISSUE-P1-631（读取版本钉扎）：记录**读取时点**的通道身份、内容摘要与元数据。
        // 解析 / 合并是长窗口，窗口内磁盘可能被再次外部改写、会话也可能被换库——提交前复核，
        // 把过期产物 fail-closed 拦下，绝不把用旧版本算出的结果写回覆盖新版本。
        // 摘要直接取**本次用于合并的那份字节**（不重读：重读会引入「钉扎的是另一份字节」的错位）。
        val readFingerprint = VaultFileReadPin.capture(
            context = context,
            pathId = pathId,
            localFile = localFile,
            content = diskBytes,
            // 复核与读取**同一实现**（同一上限 / 同一失败语义）：另写读法会在超限与异常边界上分叉
            reReadContent = { sourceAccess.readCurrentBytes(localFile, pathId) }
        )
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
            return adoptAndPersist(localDb, merged, mergedRoot, baseline?.revision, pathId, localFile, readFingerprint, policy)
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
     * `ISSUE-P1-631`：提交侧——复核「读取版本钉扎」后，在**同一次会话临界区**内完成
     * 「校验实例身份 + 持久化基线修订 → 采用合并产物 → 落盘」（`adoptAndSaveIfUnchanged`）。
     *
     * **`NonCancellable` 边界**：只包「钉扎复核 → 采用 → 落盘」这一极小临界区——读盘、解析、
     * 三方合并、裁决与 `mergeCommitGate` 均在**区外**（取消照常生效）。钉扎复核由区外移入区内，
     * 是因为「进 `withContext` 前的调度切换」会给「窗口内再次外部改写」留缝。
     * 采纳与落盘合成一次取锁，消除「adopt 后释放锁 → 换库 → save 写到后来会话」的窗口。
     * 区内的写盘使**取消被推迟到写回返回**（无安全中断中点，有意取舍，与 `SessionOpener.createNew`
     * 同口径）；临界区跑完后取消才传播，此调用可能以 `CancellationException` 收场，
     * 但磁盘与内存已同处合并后状态，提示按成败清除 / 重建，不产生分叉。
     */
    private suspend fun adoptAndPersist(
        localDb: KdbxDatabase,
        merged: MergeResult,
        mergedRoot: KdbxGroup,
        baselineRevision: Long?,
        pathId: String,
        localFile: File?,
        readFingerprint: VaultFileReadPin,
        policy: DriftMergeConflictPolicy?
    ): KdbxResult<Unit> {
        // ISSUE-P1-631：并发 barrier 验收的受控暂停点（生产恒 null，不引入任何调度）。
        // 刻意留在 NonCancellable **之外**——取消应能在「尚未开始提交」时正常生效。
        mergeCommitGate?.invoke()
        // 内容级复核（区外，**可取消**）：按同通道重读当前字节并比对 SHA-256。
        // 放在区外是有意取舍——重读最大可达读取上限（160 MiB），放进不可取消区会把「用户退出 /
        // 取消」拖到重读结束；代价是与采纳之间留下极短窗口，该窗口由区内的元数据复核兜底。
        if (!readFingerprint.isStillCurrent(
                context,
                databaseSession.currentPathIdentifier.orEmpty(),
                databaseSession.currentFile
            )
        ) {
            restorePromptsAfterCommitFailure(policy, pathId)
            AppLog.w(DRIFT_LOG_TAG, "读取后磁盘版本已变化（或已换库），放弃本次合并提交")
            return KdbxResult.Failure(
                IllegalStateException("读取后磁盘版本已变化"),
                strings.get(
                    R.string.ext_mod_merge_failed,
                    strings.get(R.string.err_drift_disk_changed_since_read)
                )
            )
        }
        // `ISSUE-P1-631`（生命周期收口）：**可取消预备段**——修剪 + 保存侧 KDF/Argon2 + 序列化
        // 全部在取消区内完成（真机秒级 CPU 不得包进 NonCancellable）；产物为密文 + 修剪候选根，
        // 会话状态零触碰。失败 / 取消 ⇒ 密文清零、会话无痕。
        val replacement = localDb.copy(
            rootGroup = mergedRoot,
            deletedObjects = merged.mergedDeletedObjects,
            customIcons = merged.mergedCustomIcons
        ).withMergedMeta(mergeSupport.applyMetaConflictPolicy(merged, policy))
        val prepared = when (val p = databaseSession.prepareSerializedSave(replacement)) {
            is KdbxResult.Success -> p.data
            is KdbxResult.Failure -> {
                restorePromptsAfterCommitFailure(policy, pathId)
                return KdbxResult.Failure(p.error, strings.get(R.string.ext_mod_merge_failed, strings.get(R.string.err_unknown)), p.code)
            }
        }
        return commitPreparedMerge(
            localDb = localDb,
            replacement = replacement,
            prepared = prepared,
            baselineRevision = baselineRevision,
            pathId = pathId,
            localFile = localFile,
            readFingerprint = readFingerprint,
            policy = policy
        )
    }

    /**
     * 提交临界区（`ISSUE-P1-631`）：`NonCancellable` 内的元数据复核 → 采用 → 落盘，失败按
     * 「会话已变 / 落盘失败」分型如实上报。边界论证见 [adoptAndPersist] 的 KDoc。
     */
    private suspend fun commitPreparedMerge(
        localDb: KdbxDatabase,
        replacement: KdbxDatabase,
        prepared: PreparedSerializedSave,
        baselineRevision: Long?,
        pathId: String,
        localFile: File?,
        readFingerprint: VaultFileReadPin,
        policy: DriftMergeConflictPolicy?
    ): KdbxResult<Unit> {
        try {
            return withContext(NonCancellable) {
                // `ISSUE-P1-631`（生命周期验收）：提交临界区已进入的确定性暂停点（生产恒 null；
                // 同 mergeStartGate，仅对裁决重算生效）。
                if (policy != null) commitEnteredGate?.invoke()
                // 区内第一道（无 IO、零成本）：元数据复核兜住「内容级复核 → 采纳」之间那极短窗口
                if (!readFingerprint.isMetadataStillCurrent(
                        context,
                        databaseSession.currentPathIdentifier.orEmpty(),
                        databaseSession.currentFile
                    )
                ) {
                    restorePromptsAfterCommitFailure(policy, pathId)
                    AppLog.w(DRIFT_LOG_TAG, "提交前元数据复核未通过，放弃本次合并提交")
                    return@withContext KdbxResult.Failure(
                        IllegalStateException("提交前磁盘元数据已变化"),
                        strings.get(
                            R.string.ext_mod_merge_failed,
                            strings.get(R.string.err_drift_disk_changed_since_read)
                        )
                    )
                }
                // 最小提交临界区：校验实例身份 + 持久化基线修订 → 采用候选 → 写预备密文 → 基线
                // （同一临界区校验：窗口内确认写盘 / 会话变化 ⇒ fail-closed；预备与提交之间
                // 会话发生任何确认变更同样 fail-closed，预备密文整份作废清零）
                val persist = databaseSession.commitPreparedSave(
                    expectedAtCycleStart = localDb,
                    candidate = replacement,
                    prepared = prepared,
                    // 基线修订与实例身份在同一临界区校验
                    expectedPersistedBaselineRevision = baselineRevision
                )
            if (persist is KdbxResult.Success) {
                driftCoordinator?.clearMergeConflictPrompt()
                refreshBaseline(pathId, localFile)
                return@withContext persist
            }
            restorePromptsAfterCommitFailure(policy, pathId)
            // 校验未通过（实例身份 / 基线修订 / 只读 / 无活动库）与落盘失败要区分如实上报：
            // 前者是「窗口内会话变了」，后者是「写盘失败」，混成一条会把用户引向错误的处置
            if (persist is KdbxResult.Failure && persist.code == KdbxError.SAVE_SESSION_CHANGED) {
                KdbxResult.Failure(
                    persist.error,
                    strings.get(
                        R.string.ext_mod_merge_failed,
                        strings.get(R.string.err_drift_session_tree_changed)
                    ),
                    persist.code
                )
            } else {
                persist
            }
            }
        } finally {
            // 预备密文即整库密文：提交消耗 / 失败 / 取消任何退出路径都必须清零
            // （commitPreparedSave 内部已兜底，此处覆盖未进入提交段的路径）
            prepared.wipe()
        }
    }

    /**
     * `ISSUE-P1-631`（生命周期整改）：提交失败后的提示归还。
     *
     * 三选路径（[policy] == null）：失败即重建三选提示（原行为）。
     * 裁决路径（KEEP_*）：**保留**置起中的冲突提示供用户重试——受理已不再先清提示
     * （见 [resolve] 的 KEEP_* 分支），此处若 requestPrompt 会把仍有效的裁决上下文
     * 覆盖成三选，等于把「失败」伪装成「回到起点」，UI 状态同样不得领先持久化事实。
     * 窗口内会话已切换时，提示由锁定观察者清空且不应重建（裁决随会话作废，既有语义）。
     */
    private fun restorePromptsAfterCommitFailure(policy: DriftMergeConflictPolicy?, pathId: String) {
        if (policy == null) driftCoordinator?.requestPrompt(pathId)
    }

    /** 处置成功后的基线刷新：本地直读文件属性；SAF 取真实文档元数据（不可读即置空） */
    private fun refreshBaseline(pathId: String, localFile: File?) {
        val baseline: VaultFileBaseline? =
            if (localFile != null) VaultFileBaseline.fromFile(localFile)
            else VaultFileMetadataProbe.baselineFor(context, pathId)
        driftCoordinator?.refreshBaselineAfterPersist(baseline)
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
