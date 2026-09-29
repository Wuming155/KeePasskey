package com.keepasskey.app.security

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
 */
@Singleton
class VaultFileDriftCoordinator @Inject constructor(
    private val baselineHolder: VaultFileBaselineHolder
) {

    /** 待用户处置的漂移提示（null = 无待决） */
    private val _pending = MutableStateFlow<VaultFileDriftPrompt?>(null)
    val pending: StateFlow<VaultFileDriftPrompt?> = _pending.asStateFlow()

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

    /** 唤起三选提示（幂等：已有待决时覆盖为最新） */
    fun requestPrompt(pathIdentifier: String?) {
        _pending.value = VaultFileDriftPrompt(
            pathIdentifier = pathIdentifier ?: baselineHolder.current()?.pathIdentifier ?: ""
        )
    }

    /** 用户完成处置或放弃后关闭提示 */
    fun dismiss() {
        _pending.value = null
    }

    /** 成功处置后刷新基线（保存成功 / 重载成功 / 合并成功后调用） */
    fun refreshBaselineAfterPersist(pathIdentifier: String, localFile: File?) {
        baselineHolder.capture(pathIdentifier, localFile)
        _pending.value = null
    }

    /** 锁定路径清空基线与待决 */
    fun clear() {
        baselineHolder.clear()
        _pending.value = null
    }
}

/** 待决漂移提示载荷（UI 消费） */
data class VaultFileDriftPrompt(
    val pathIdentifier: String
)
