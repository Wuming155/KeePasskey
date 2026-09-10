package com.keepasskey.app.security

import android.app.Activity
import android.view.WindowManager
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 窗口防截屏与多任务侧漏守卫 (FLAG_SECURE) —— 密码管理器**强制**语义（ISSUE-P2-09 / ZT-14）。
 *
 * 裁决内核见纯函数 [FlagSecurePolicy]（临时豁免模型，JVM 可测）：
 * - 会话 CLOSED / LOCKED：无条件强制遮蔽（主密码输入 / Recents 预览绝不泄露）；
 * - 会话解锁且用户开关开启：强制遮蔽；
 * - 会话解锁、用户开关关闭：**默认仍强制遮蔽**；仅当 UI 完成显式风险确认并调用
 *   [requestTemporaryExemption] 授予的临时豁免尚未到期时才短暂解除；
 * - 豁免为内存态（进程重启即失效），会话锁定立即清除，到期自动恢复强制遮蔽。
 *
 * 同时施加**窗口级遮挡触摸过滤**（`setFilterTouchesWhenObscured`），
 * 阻止被其它窗口遮挡时的触摸注入（点击劫持）。
 *
 * UI 交互（关闭开关时弹出风险确认对话框、倒计时展示）由设置页承载：
 * 确认后调用 [requestTemporaryExemption]，取消则不应关闭开关。
 */
@Singleton
class FlagSecureGuard @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val databaseSession: DatabaseSession
) {

    /** 测试注入点：默认系统时钟（豁免窗口判定用） */
    @Volatile
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    private val exemptionUntilMs = MutableStateFlow<Long?>(null)

    /** 临时豁免截止时间戳（null 表示无豁免），供设置页展示倒计时 */
    val temporaryExemptionUntilMs: StateFlow<Long?> = exemptionUntilMs.asStateFlow()

    private val exemptionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exemptionExpiryJob: Job? = null

    /**
     * 将守卫与目标 Activity 窗口及协程生命周期绑定。
     *
     * 首帧收口：attach 于 onCreate 调用，先**无条件强制遮蔽**一次，再进入 Flow 订阅按策略修正——
     * 彻底消除「onCreate → 首次 Flow 发射」间 Recents 预览截获明文的空窗（fail-closed）。
     */
    fun attach(activity: Activity, coroutineScope: CoroutineScope) {
        // 首帧强制遮蔽（保守方向）；若用户已授予有效临时豁免，随后由 Flow 解除
        applyFlagSecure(activity, true)
        // 窗口级遮挡触摸过滤（点击劫持防护）
        applyObscuredTouchFilter(activity)

        // 会话重新锁定即清除临时豁免（锁定态无条件强制遮蔽）
        coroutineScope.launch {
            databaseSession.state.collect { state ->
                if (isSessionLocked(state)) clearExemption()
            }
        }

        coroutineScope.launch {
            combine(
                settingsRepository.getSettings().map { it.flagSecureEnabled },
                databaseSession.state.map { isSessionLocked(it) },
                exemptionUntilMs
            ) { userEnabled, sessionLocked, exemptionUntil ->
                FlagSecurePolicy.shouldApplySecure(
                    sessionLocked = sessionLocked,
                    userEnabled = userEnabled,
                    exemptionUntilMs = exemptionUntil,
                    nowMs = clockMs()
                )
            }
                .distinctUntilChanged()
                .collect { enabled -> applyFlagSecure(activity, enabled) }
        }
    }

    /**
     * 用户在设置页完成「明确风险确认」后调用：申请临时解除遮蔽窗口。
     *
     * @return true=豁免已授予（窗口 [FlagSecurePolicy.TEMPORARY_EXEMPTION_WINDOW_MS]）；
     *   false=会话未解锁（锁定态无条件强制遮蔽，拒绝授予）
     */
    fun requestTemporaryExemption(): Boolean {
        if (isSessionLocked(databaseSession.state.value)) return false
        exemptionUntilMs.value = clockMs() + FlagSecurePolicy.TEMPORARY_EXEMPTION_WINDOW_MS
        exemptionExpiryJob?.cancel()
        exemptionExpiryJob = exemptionScope.launch {
            delay(FlagSecurePolicy.TEMPORARY_EXEMPTION_WINDOW_MS)
            exemptionUntilMs.value = null
            exemptionExpiryJob = null
        }
        return true
    }

    /** 主动撤销临时豁免（立即恢复强制遮蔽） */
    fun revokeTemporaryExemption() {
        clearExemption()
    }

    private fun clearExemption() {
        exemptionExpiryJob?.cancel()
        exemptionExpiryJob = null
        exemptionUntilMs.value = null
    }

    /**
     * 直接对目标窗口设置或清除 FLAG_SECURE
     */
    fun applyFlagSecure(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * 窗口级遮挡触摸过滤：窗口被其它窗口部分/完全遮挡时丢弃触摸事件（点击劫持防护）。
     *
     * 正确入口是 **View 层** [android.view.View.setFilterTouchesWhenObscured]（compileSdk 37
     * 的 android.jar 中 `android.view.Window` 并**无**该方法）——对 `decorView` 开启后，
     * 其整棵视图子树在遮挡态下统一丢弃触摸。
     */
    fun applyObscuredTouchFilter(activity: Activity) {
        activity.window.decorView.filterTouchesWhenObscured = true
    }

    /**
     * 会话未解锁（从未打开 / 已锁定）即视为需要强制遮蔽
     */
    private fun isSessionLocked(state: DatabaseSession.SessionState): Boolean =
        state == DatabaseSession.SessionState.CLOSED || state == DatabaseSession.SessionState.LOCKED
}
