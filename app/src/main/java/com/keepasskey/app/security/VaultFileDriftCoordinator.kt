package com.keepasskey.app.security

import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P2-378：外部修改漂移的用户三选编排（策略层判定已落，本类负责「何时提示 / 如何处置」）。
 *
 * 口径（批次留痕）：
 * - 两时点：保存前（`RealVaultRepository.persistSession`）与回到前台（`AutoLockManager.onStart` 同路径）；
 * - 漂移即 fail-closed 中止覆盖，不得静默整树覆盖；
 * - 用户三选：放弃保存 / 重载磁盘 / 合并后保存（走既有 KdbxMerger）；
 * - 重载 / 合并失败不得丢改动：保持脏标记并把失败原因上浮，禁止「失败即丢内存树」。
 *
 * `ISSUE-P1-631`：合并以**真实共同基线**（最后确认持久化版本）执行；合并发现条目级冲突时
 * 不静默取一侧——[mergeConflict] 承接待裁决提示（与三选提示[互斥]呈现），裁决结果
 * （以本地 / 以磁盘 / 双方保留）驱动同一合并实现重算后提交。
 */
@Singleton
class VaultFileDriftCoordinator @Inject constructor(
    private val baselineHolder: VaultFileBaselineHolder,
    private val databaseSession: DatabaseSession
) {

    /** 待用户处置的漂移提示（null = 无待决） */
    private val _pending = MutableStateFlow<VaultFileDriftPrompt?>(null)
    val pending: StateFlow<VaultFileDriftPrompt?> = _pending.asStateFlow()

    /** `ISSUE-P1-631`：待用户裁决的合并冲突提示（null = 无待决；与 [pending] 互斥） */
    private val _mergeConflict = MutableStateFlow<VaultFileDriftMergeConflict?>(null)
    val mergeConflict: StateFlow<VaultFileDriftMergeConflict?> = _mergeConflict.asStateFlow()

    init {
        // ISSUE-P1-631：会话终止（锁定 / 关闭 / 换库前置释放）时清空待决提示——
        // 裁决已随会话作废，不得跨库残留。回调在会话互斥锁内同步触发，仅做置空（契约见
        // SessionLockObserver：禁止网络 / 大量 IO，禁止再调会话 API）。
        databaseSession.addLockObserver(
            SessionLockObserver {
                _pending.value = null
                _mergeConflict.value = null
            }
        )
    }

    /**
     * 检查当前文件是否相对基线漂移。
     *
     * @param pathIdentifier 会话当前路径标识
     * @param localFile 本地 File 通道（SAF 传 null，由调用方另供元数据）
     * @param current 基线替代元数据（SAF / DocumentFile）；null 时从 [localFile] 自读
     * @return true = 存在漂移，调用方须中止覆盖并唤起三选
     */
    fun checkDrift(
        pathIdentifier: String?,
        localFile: File?,
        current: VaultFileBaseline? = null
    ): Boolean {
        val base = baselineHolder.current() ?: return false
        val path = pathIdentifier ?: base.pathIdentifier
        val now = current ?: baselineHolder.currentFromLocalFile(localFile)
        return VaultFileDriftPolicy.shouldAbortSave(
            baseline = base.copy(pathIdentifier = path),
            current = now
        )
    }

    /**
     * SAF（`content://`）通道的保存前漂移检测（`ISSUE-P3-447` AC②）。
     *
     * 与 [checkDrift] 的差别只在「元数据不可读」的处置：本地 `File` 通道下
     * `current == null` 表示**文件已消失** ⇒ 判漂移（宁可提示）；SAF 通道下
     * `current == null` 通常只表示**提供方不暴露元数据** ⇒ 按既有口径「宁可不提示」
     * 放行，绝不据此误报为「被外部修改」（2026-10-02 止血的教训）。
     */
    fun checkSafDrift(pathIdentifier: String, current: VaultFileBaseline?): Boolean {
        if (current == null) return false
        val base = baselineHolder.current() ?: return false
        return VaultFileDriftPolicy.shouldAbortSave(
            baseline = base.copy(pathIdentifier = pathIdentifier),
            current = current
        )
    }

    /**
     * 唤起三选提示（幂等：已有待决时覆盖为最新）。
     *
     * `ISSUE-P1-631`：三选与合并冲突裁决互斥——唤起三选即作废未完成的裁决提示
     * （裁决可随时由合并重算重建，不丢失任何数据；反之两个对话框叠放才是错误状态）。
     */
    fun requestPrompt(pathIdentifier: String?) {
        _pending.value = VaultFileDriftPrompt(
            pathIdentifier = pathIdentifier ?: baselineHolder.current()?.pathIdentifier ?: ""
        )
        _mergeConflict.value = null
    }

    /**
     * `ISSUE-P1-631`：唤起合并冲突裁决提示（幂等：覆盖为最新）。
     * [titles] 为待裁决条目的展示用标题（已按上限截断），[conflictCount] 为真实冲突总数。
     */
    fun requestMergeConflictPrompt(
        pathIdentifier: String,
        conflictCount: Int,
        titles: List<String>
    ) {
        _pending.value = null
        _mergeConflict.value = VaultFileDriftMergeConflict(
            pathIdentifier = pathIdentifier,
            conflictCount = conflictCount,
            entryTitles = titles
        )
    }

    /** `ISSUE-P1-631`：关闭合并冲突裁决提示（用户已裁决、取消或转入其它处置） */
    fun clearMergeConflictPrompt() {
        _mergeConflict.value = null
    }

    /** 用户完成处置或放弃后关闭提示 */
    fun dismiss() {
        // ISSUE-P1-631：只清三选提示——合并冲突裁决提示由 resolve 的终结路径显式清理，
        // 避免「处置调用返回后由 UI 统一 dismiss」误杀刚由合并置起的待裁决提示
        _pending.value = null
    }

    /**
     * 成功处置后刷新基线（保存成功 / 重载成功 / 合并成功后调用）。
     *
     * `ISSUE-P3-447` AC② 起入参改为**调用方构造好的基线**：本地 `File` 走
     * [VaultFileBaseline.fromFile]，SAF 走 `VaultFileMetadataProbe.baselineFor`
     * （两者元数据来源不同）。传 `null` 表示元数据当前不可读 ⇒ 基线置空、漂移防护
     * 暂时降级为「宁可不提示」（不得落占位假值）。
     */
    fun refreshBaselineAfterPersist(baseline: VaultFileBaseline?) {
        baselineHolder.capture(baseline)
        _pending.value = null
    }

    /** 锁定路径清空基线与待决 */
    fun clear() {
        baselineHolder.clear()
        _pending.value = null
        _mergeConflict.value = null
    }
}

/** 待决漂移提示载荷（UI 消费） */
data class VaultFileDriftPrompt(
    val pathIdentifier: String
)

/**
 * `ISSUE-P1-631`：待裁决的漂移合并冲突载荷（UI 消费）。
 *
 * [entryTitles] 仅承载**展示面**标题（同步冲突界面同口径）；不承载任何字段值 / 明文。
 */
data class VaultFileDriftMergeConflict(
    val pathIdentifier: String,
    val conflictCount: Int,
    val entryTitles: List<String>
)
