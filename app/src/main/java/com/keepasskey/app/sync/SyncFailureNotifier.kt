package com.keepasskey.app.sync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.keepasskey.app.R
import com.keepasskey.app.notification.NotificationChannels
import com.keepasskey.app.notification.NotificationChannelSpec
import com.keepasskey.app.notification.NotificationIntents
import com.keepasskey.app.notification.NotificationPermissionPrompter
import com.keepasskey.core.log.AppLog
import com.keepasskey.sync.engine.SyncCacheEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步失败的可观测性判定（ISSUE-P3-298 ④；纯函数，JVM 可单测）。
 *
 * 先例：`NotificationGate`（ISSUE-P3-18）——通知「发不发」的决策单点化，
 * [SyncFailureNotifier] 只消费结论并在 Android 侧执行。
 */
object SyncFailureSignal {

    /**
     * 缓存监督六事件中属于「引擎级失败」的两类：远端写失败 / 远端读失败。
     * 其余四类（缓存更新、就地打开等）是正常同步足迹，不触发通知。
     */
    fun isEngineFailure(event: SyncCacheEvent): Boolean =
        event is SyncCacheEvent.CouldntSaveToRemote ||
            event is SyncCacheEvent.CouldntOpenFromRemote

    /**
     * 同步周期结果是否需要亮出失败通知：仅 `Error`。
     * `ConflictNeedsUser` 有前台冲突界面承接（后台静默保留本地副本是既有裁决）；
     * `VaultBindingMismatch` 有绑定确认对话框承接；其余是成功 / 离线态。
     */
    fun shouldNotifyOutcome(outcome: SyncOutcome): Boolean = outcome is SyncOutcome.Error
}

/**
 * 后台同步失败可见性控制器（ISSUE-P3-298 ④）。
 *
 * ## 缺陷形态（整改前）
 *
 * `SyncCoordinator.syncEvents` 全仓**零订阅者**，[SyncOutcome] 又只经返回值交付——
 * 周期任务 `PeriodicSyncWorker` 恒返回 `Result.success()`（有意不重试，防退避风暴），
 * 失败被整条吞掉：库可连续数周未同步成功而用户毫无感知。
 *
 * ## 行为契约
 *
 * - 订阅 [SyncCoordinator.lastOutcome]（每个同步周期收尾写入）：`Error` → 发/更新
 *   静默失败通知；其余结果（含手动同步成功）→ 撤下通知；
 * - 订阅 [SyncCoordinator.syncEvents]（AC② 要求的真实订阅者）：引擎级失败事件
 *   （`CouldntSaveToRemote` / `CouldntOpenFromRemote`）同样亮出通知；
 * - 通知内容为**固定通用文案**（不含库文件名 / 路径等用户数据），静默
 *   （`IMPORTANCE_LOW` + `setSilent`）、锁屏 `VISIBILITY_SECRET`、点击回主界面；
 * - 「可静音、可关」：通道级语义——`SYNC_FAILURE` 通道低重要度即静音，用户可随时在
 *   系统通知设置中关闭该通道（应用内不另设重复开关，避免双口径）；
 * - 运行期通知权限被回收（`SecurityException` / 未授权）时静默降级，绝不崩溃。
 *
 * 由 [com.keepasskey.app.MainApplication.onCreate] 冷启动点启动（幂等），
 * 覆盖周期同步 / 手动同步 / 改绑覆盖等全部 `syncNow` 来源。
 */
@Singleton
class SyncFailureNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncCoordinator: SyncCoordinator,
    private val permissionPrompter: NotificationPermissionPrompter
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false

    /** 当前是否已发出失败通知：避免每个同步周期重复 notify / 无谓 cancel */
    private var posted = false

    /** 幂等启动（重复调用无害；由 MainApplication 冷启动点调用一次） */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            launch {
                syncCoordinator.lastOutcome.collect { outcome ->
                    if (outcome != null && SyncFailureSignal.shouldNotifyOutcome(outcome)) {
                        post()
                    } else if (outcome != null) {
                        cancel()
                    }
                }
            }
            launch {
                syncCoordinator.syncEvents.collect { event ->
                    if (SyncFailureSignal.isEngineFailure(event)) {
                        post()
                    }
                }
            }
        }
    }

    private fun post() {
        if (!permissionPrompter.isGranted()) return
        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelSpec.SYNC_FAILURE.channelId
        )
            .setSmallIcon(NotificationChannels.SMALL_ICON_RES)
            .setContentTitle(context.getString(R.string.notification_sync_failure_title))
            .setContentText(context.getString(R.string.notification_sync_failure_text))
            .setContentIntent(NotificationIntents.openAppForSyncFailure(context))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(NotificationChannels.ID_SYNC_FAILURE, notification)
            posted = true
        } catch (t: SecurityException) {
            // 通知权限在运行期被系统回收：静默降级，绝不崩溃（与已解锁常驻通知同口径）
            AppLog.w(TAG, "同步失败通知发送被系统拒绝，静默降级", t)
            posted = false
        }
    }

    private fun cancel() {
        if (!posted) return
        posted = false
        try {
            NotificationManagerCompat.from(context).cancel(NotificationChannels.ID_SYNC_FAILURE)
        } catch (t: SecurityException) {
            AppLog.w(TAG, "同步失败通知撤销被系统拒绝，静默忽略", t)
        }
    }

    private companion object {
        const val TAG = "SyncFailureNotifier"
    }
}
