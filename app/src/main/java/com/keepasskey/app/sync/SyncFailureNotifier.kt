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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.keepasskey.app.coroutines.guardedScope

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
     *
     * ISSUE-P2-548：**真·网络不可达不算失败**——`SyncException.NetworkError` 起因的
     * 「连不上」是设计内降级（网络恢复即自愈、用户无需任何操作），若在此亮通知，
     * 真实断网时每个同步周期都会闪一次通知（AC② 明令禁止）。
     * 鉴权 / 协议 / 5xx 起因仍属失败（用户必须改凭据或等服务端恢复）。
     */
    fun isEngineFailure(event: SyncCacheEvent): Boolean = when (event) {
        is SyncCacheEvent.CouldntSaveToRemote -> !isBenignUnreachable(event.cause)
        is SyncCacheEvent.CouldntOpenFromRemote -> !isBenignUnreachable(event.cause)
        else -> false
    }

    /** 「设计内降级」判据：只认 [com.keepasskey.sync.model.SyncException.NetworkError]。 */
    private fun isBenignUnreachable(cause: Throwable?): Boolean =
        cause is com.keepasskey.sync.model.SyncException.NetworkError

    /**
     * 同步周期结果是否需要亮出失败通知：仅 `Error`。
     * `ConflictNeedsUser` 有前台冲突界面承接（后台静默保留本地副本是既有裁决）；
     * `VaultBindingMismatch` 有绑定确认对话框承接；其余是成功 / 离线态。
     *
     * 口径前提（ISSUE-P2-548）：`Offline` 现在**只**由 `NetworkError` 产生（见
     * [com.keepasskey.app.sync.remoteFailureOutcome] 的单点归类），故「Offline 不通知」
     * 不再误伤鉴权 / 协议失败——整改前那两类失败被归成 `Offline`，正是本条缺陷的根。
     */
    fun shouldNotifyOutcome(outcome: SyncOutcome): Boolean = outcome is SyncOutcome.Error
}

/** 通知动作的终态：发 / 撤 / 无需动作（已处于目标态）。 */
enum class SyncFailureNotificationAction { POST, CANCEL, NONE }

/**
 * 失败通知的**状态机**（ISSUE-P2-548；纯 Kotlin，JVM 可单测）。
 *
 * ## 缺陷形态（整改前）
 *
 * `posted` 是普通 `var`（非 `@Volatile`），写于 `post()`、读于 `cancel()`，而两个订阅者
 * 是同一 scope 下的**兄弟协程**（`Dispatchers.Default` 多线程池）⇒ 跨线程**无 happens-before**，
 * 且同一同步周期内既 `post()`（引擎失败事件）又 `cancel()`（`Offline` 结论）⇒
 * 「鉴权失败的通知被自己的结论撤掉」真实可能但**不确定发生**（数据竞态）。
 *
 * ## 整改
 *
 * - 「发 / 撤」决策与 `posted` 收进**单个对象**并以 `@Synchronized` 串行化 ⇒ 无丢失更新；
 * - 幂等：重复失败只发一次 [SyncFailureNotificationAction.POST]，已在目标态则 `NONE`
 *   （杜绝每个同步周期重复 `notify` 与无谓 `cancel`）；
 * - 调用方另把两条流**合并为单个收集器**（见 [SyncFailureNotifier.signals]），
 *   使「先事件后结论」的发射顺序**构成**执行顺序保证。
 */
class SyncFailureNotificationState {

    @Volatile
    private var posted = false

    /** 当前是否已留有失败通知（供单测断言与诊断）。 */
    val isPosted: Boolean get() = posted

    /** 引擎级失败事件：目标态 = 已发。 */
    @Synchronized
    fun onEngineFailure(): SyncFailureNotificationAction = transitionTo(posted = true)

    /**
     * 同步周期结论：目标态由 [SyncFailureSignal.shouldNotifyOutcome] 决定。
     *
     * 与 [onEngineFailure] 共用同一把锁 ⇒ 同周期内「事件 → 结论」无论谁先到达，
     * 最终态**只由结论裁决**，事件不会把已被结论撤销的通知再拉起来。
     */
    @Synchronized
    fun onOutcome(outcome: SyncOutcome): SyncFailureNotificationAction =
        transitionTo(posted = SyncFailureSignal.shouldNotifyOutcome(outcome))

    /** 通知实际下发失败（权限被系统回收等）：回退为「未发」，避免后续空撤。 */
    @Synchronized
    fun markPostFailed() {
        posted = false
    }

    private fun transitionTo(posted: Boolean): SyncFailureNotificationAction {
        val action = when {
            posted && !this.posted -> SyncFailureNotificationAction.POST
            !posted && this.posted -> SyncFailureNotificationAction.CANCEL
            else -> SyncFailureNotificationAction.NONE
        }
        this.posted = posted
        return action
    }
}

/**
 * 合并后的单一输入（ISSUE-P2-548）：把「同步周期结论」与「引擎事件」变成**一条流**，
 * 使裁决只可能串行发生——两个并发收集器是原竞态的结构性根因。
 */
private sealed interface SyncFailureInput {
    data class CycleOutcome(val outcome: SyncOutcome) : SyncFailureInput
    data class EngineEvent(val event: SyncCacheEvent) : SyncFailureInput
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
 *   （`CouldntSaveToRemote` / `CouldntOpenFromRemote`，**不含**真·网络不可达）同样亮出通知；
 * - **同周期裁决收口**（ISSUE-P2-548）：两条流 `merge` 为**单个收集器**（见 [signals]），
 *   「发 / 撤」与 `posted` 状态收进 [SyncFailureNotificationState]（`@Synchronized`）——
 *   整改前两个订阅者是兄弟协程 + 非 `@Volatile` 标志 ⇒ 鉴权失败的通知会被同周期的
 *   `Offline` 结论撤掉（且是否发生不确定）；现处理严格串行，终态唯一；
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

    private val scope = guardedScope(Dispatchers.Default, "SyncFailureNotifier")

    private var started = false

    /** 「已发出/已撤下」的唯一状态源（替代原先非 `@Volatile` 的裸 `posted`） */
    private val state = SyncFailureNotificationState()

    /**
     * ISSUE-P2-548：两条流**合并为单个收集器**。
     *
     * 整改前它们是同一 scope 下的**两个兄弟协程**，而 `SyncCoordinator` 的
     * 「先 `publishSyncEvents()` 后写 `_lastOutcome`」只是**发射**顺序，
     * 不构成**执行**顺序 ⇒ 「同周期既 `post()` 又 `cancel()`、谁后跑全看线程调度」。
     *
     * 合并后只有一个收集协程：`merge` 按发射次序交付，处理**严格串行**且无并发窗口；
     * 事件与结论的裁决再一并收进 [SyncFailureNotificationState]，终态唯一。
     */
    private fun signals(): Flow<SyncFailureInput> = merge(
        syncCoordinator.lastOutcome.filterNotNull().map { SyncFailureInput.CycleOutcome(it) },
        syncCoordinator.syncEvents.map { SyncFailureInput.EngineEvent(it) }
    )

    /** 幂等启动（重复调用无害；由 MainApplication 冷启动点调用一次） */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            signals().collect { input ->
                when (input) {
                    is SyncFailureInput.CycleOutcome -> apply(state.onOutcome(input.outcome))
                    is SyncFailureInput.EngineEvent ->
                        if (SyncFailureSignal.isEngineFailure(input.event)) apply(state.onEngineFailure())
                }
            }
        }
    }

    /** 状态机结论 → Android 侧执行（仅由 [signals] 合并后的**唯一**收集器调用） */
    private fun apply(action: SyncFailureNotificationAction) {
        when (action) {
            SyncFailureNotificationAction.POST -> post()
            SyncFailureNotificationAction.CANCEL -> cancel()
            SyncFailureNotificationAction.NONE -> Unit
        }
    }

    private fun post() {
        if (!permissionPrompter.isGranted()) {
            // 未授权：通知不会上屏 ⇒ 状态回退为「未发」，避免后续周期空撤一次
            state.markPostFailed()
            return
        }
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
        } catch (t: SecurityException) {
            // 通知权限在运行期被系统回收：静默降级，绝不崩溃（与已解锁常驻通知同口径）
            AppLog.w(TAG, "同步失败通知发送被系统拒绝，静默降级", t)
            // 通知并未真正上屏 ⇒ 状态回退为「未发」，避免下次周期空撤一次
            state.markPostFailed()
        }
    }

    private fun cancel() {
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
