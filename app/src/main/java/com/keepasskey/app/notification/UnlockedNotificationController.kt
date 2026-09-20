package com.keepasskey.app.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.core.log.AppLog
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「密码库已解锁」常驻通知控制器（ISSUE-P3-18 验收标准 3：`showUnlockedNotification` 真实生效）。
 *
 * 行为契约：
 * - 观察 [DatabaseSession.state]，库「已解密可用」（OPENED / DIRTY）且偏好开启且权限可用
 *   → 发一条常驻通知（`setOngoing(true)` 用户不可清除、点击回到 [com.keepasskey.app.MainActivity]）；
 * - 锁定 / 关闭 / 偏好关闭 / 权限被系统回收 → 立即撤销该通知；
 * - 启动时先无条件撤销一次，清理「上个进程被杀死时残留、但内存会话已不存在」的失真通知；
 * - 由 [com.keepasskey.app.MainApplication.onCreate] 在进程冷启动点启动（幂等），
 *   覆盖 Autofill / Credential 等不经 MainActivity 的冷启动入口。
 *
 * 通知内容（验收标准 4）：标题与正文均为固定通用文案，不含条目名、用户名、密码、库文件名等
 * 任何用户数据；`VISIBILITY_SECRET` 保证锁屏不展示内容。
 *
 * 协程：自持受控 Application 级 [scope]（SupervisorJob + Default），禁止裸 GlobalScope。
 */
@Singleton
class UnlockedNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseSession: DatabaseSession,
    private val settingsStore: ExtendedSettingsStore,
    private val permissionPrompter: NotificationPermissionPrompter,
    private val autoLockManager: com.keepasskey.app.security.AutoLockManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false

    /** 当前是否已发出常驻通知：避免每个轮询周期重复 notify / 撤销 */
    private var posted = false

    /** 上一次已发布通知关联的倒计时截止点 */
    private var lastDeadline: Long? = null

    /** 上一次求得的「目标状态」，仅在变化时记录诊断日志（避免轮询刷屏） */
    private var lastDesired: Boolean? = null

    /** 幂等启动（重复调用无害；由 MainApplication 冷启动点调用一次） */
    fun start() {
        if (started) return
        started = true
        // 进程重启后系统仍可能保留上一次会话的常驻通知（通知归系统所有，不随进程消亡），
        // 而此刻内存会话必为 CLOSED。先无条件撤销一次，杜绝「库其实已锁定、通知却仍显示
        // 已解锁」的失真状态；未发过通知时 cancel 为幂等空操作。
        cancel()
        scope.launch {
            // merge 的下游收集是单协程串行语义：refresh() 不会被并发调用，posted 无需额外同步
            merge(
                databaseSession.state.map { },
                preferenceChanges(),
                autoLockManager.lockDeadline.map { }
            ).collect { refresh() }
        }
    }

    /**
     * 偏好变更触发源（ISSUE-P3-152：**由 2 秒轮询改为进程级快照订阅**）。
     *
     * 原实现每 2 秒调一次 `settingsStore.load()`——即逐 key 读取约 50 项 `SharedPreferences`
     * 并构造整个 `ExtendedSettings` 对象，仅用于刷新本通知的目标态；
     * 解锁期间长期驻留，属「用户看不见也在耗电」的常态成本。
     *
     * 收敛手段：`ExtendedSettingsStore` 自 ISSUE-P2-21 起已持有**进程级唯一内存权威快照**
     * `settings: StateFlow<ExtendedSettings>`，且设置页的每个写入点都走
     * `publish(...) + save(...)`（[com.keepasskey.app.ui.screens.settings.SettingsExtendedPreferencesController]）⇒
     * 订阅该快照既**零 IO**（不再触碰 SharedPreferences）又**比轮询更快**（变更即达，不必等下一个周期）。
     * 原先「同步 API 无 Flow 通道，故须轮询」的理由**已不成立**（该快照早于本条存在）。
     */
    private fun preferenceChanges(): Flow<Unit> = settingsStore.settings.map { }

    private fun refresh() {
        val prefEnabled = settingsStore.settings.value.showUnlockedNotification
        val permissionGranted = permissionPrompter.isGranted()
        val desired = NotificationGate.shouldPostUnlockedNotification(
            prefEnabled = prefEnabled,
            permissionGranted = permissionGranted,
            sessionState = databaseSession.state.value
        )
        if (lastDesired != desired) {
            // 目标状态变化才记录（解锁/锁定/开关切换/权限变更），同态重入不产生日志；
            // 内容仅含布尔量，绝不含库文件名、条目等任何用户数据
            AppLog.i(
                TAG,
                "已解锁常驻通知目标状态变更：目标=$desired（偏好=$prefEnabled，权限=$permissionGranted）"
            )
            lastDesired = desired
        }
        val currentDeadline = autoLockManager.lockDeadline.value
        val deadlineChanged = posted && lastDeadline != currentDeadline
        when {
            desired && (!posted || deadlineChanged) -> {
                lastDeadline = currentDeadline
                post(currentDeadline)
            }
            !desired && posted -> cancel()
            else -> Unit
        }
    }

    private fun post(deadline: Long?) {
        val builder = NotificationCompat.Builder(
            context,
            NotificationChannelSpec.UNLOCKED_STATUS.channelId
        )
            .setSmallIcon(NotificationChannels.SMALL_ICON_RES)
            .setContentTitle(context.getString(R.string.notification_unlocked_title))
            .setContentIntent(NotificationIntents.openAppForUnlockedStatus(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // 锁屏仅显示「内容已隐藏」：库处于解锁态本身即敏感状态，不应在锁屏暴露
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)

        val now = System.currentTimeMillis()
        if (deadline != null && deadline > now) {
            builder.setContentText(context.getString(R.string.notification_unlocked_text))
                .setWhen(deadline)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            builder.setContentText(context.getString(R.string.notification_unlocked_text_idle))
                .setShowWhen(false)
                .setUsesChronometer(false)
        }

        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context)
                .notify(NotificationChannels.ID_UNLOCKED_STATUS, notification)
            posted = true
        } catch (t: SecurityException) {
            // 权限在运行期被回收（用户在系统设置中关闭通知）：静默降级，绝不崩溃
            AppLog.w(TAG, "已解锁常驻通知发送被系统拒绝，静默降级", t)
            posted = false
        }
    }

    private fun cancel() {
        posted = false
        lastDeadline = null
        try {
            NotificationManagerCompat.from(context).cancel(NotificationChannels.ID_UNLOCKED_STATUS)
        } catch (t: SecurityException) {
            AppLog.w(TAG, "已解锁常驻通知撤销被系统拒绝，静默忽略", t)
        }
    }

    private companion object {
        const val TAG = "UnlockedNotification"
    }
}
