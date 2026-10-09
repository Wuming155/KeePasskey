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
import kotlinx.coroutines.CancellationException
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
 * - 调用方另把两条流**合并为单个收集器**（见 [SyncFailureNotifier.signals]），处理**严格串行**；
 *   注意 `merge` 只保证**单条上游流内部**有序，**不保证跨上游的相对到达顺序** ⇒
 *   终态一致依赖「事件 `cause` 与结论 `classify` 同源」（同一同步周期内两者用的是同一个异常对象）。
 *
 * ## 幂等口径与复位条件（`ISSUE-P3-559` 登记；详见 `docs/architecture/已知工程限界.md` §40）
 *
 * `posted` 的语义是「**本次失败周期内已亮出过通知**」，**不是**「通知当前在屏」——
 * 用户在系统 UI 上划掉通知不会回调本类（`state` 无「通知被移除」的观测通道），
 * 故同一失败周期内不再重复亮出。**复位条件**＝任一次非失败结论经 [onOutcome] 把目标态
 * 置 `false`（成功 / 离线 / 冲突等皆然），下一失败周期即重新 [POST]。该口径为**有意防抖**。
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
internal sealed interface SyncFailureInput {
    data class CycleOutcome(val outcome: SyncOutcome) : SyncFailureInput
    data class EngineEvent(val event: SyncCacheEvent) : SyncFailureInput
}

/**
 * 同步失败信号的**元素级处理**（`ISSUE-P2-557` ①③）：状态机裁决 + 动作执行 + 异常隔离。
 *
 * 与 Android 侧（[SyncFailureNotifier] 的发 / 撤通知）解耦——执行体 [execute] 由调用方注入，
 * 故「单个元素裁决抛异常」这一失效形态可在 **JVM 单测**里直接复现，无需真机。
 *
 * ## 为什么需要单独一层
 *
 * 整改前 `signals().collect { … }` **整段无 `try/catch`**：`post()` 只捕 `SecurityException`，
 * 而 `permissionPrompter.isGranted()` / `NotificationIntents.openAppForSyncFailure(...)` 都在
 * try **之外** ⇒ 任何非 `SecurityException` 的 `Throwable` 逃出 `collect` ⇒ `launch` 协程
 * 终止 ⇒ 宿主 `guardedScope` 的 `CoroutineExceptionHandler` 只落日志、不重抛（其 KDoc 自陈
 * 「该任务静默失败」）⇒ 该组件的**唯一存在理由**（让同步失败可见）静默消失，且无重启通道。
 *
 * 现改为**元素级**隔离：单个信号处理失败只落脱敏日志并回退状态，收集器继续；
 * `CancellationException` 照常沿链重抛（与 `ISSUE-P3-555` 的收敛口径一致）。
 */
internal class SyncFailureSignalHandler(
    private val execute: (SyncFailureNotificationAction) -> Unit,
    private val state: SyncFailureNotificationState = SyncFailureNotificationState()
) {

    /** 当前是否已留有失败通知（供单测断言与诊断）。 */
    val isPosted: Boolean get() = state.isPosted

    /**
     * 处理单个信号。单个元素抛出的**非取消**异常被就地隔离：
     * 只落脱敏日志，且若失败动作是 [SyncFailureNotificationAction.POST] 则回退
     * [SyncFailureNotificationState.markPostFailed]（通知并未真正上屏，避免下一周期空撤
     * 与「幽灵已发」）。
     */
    fun onSignal(input: SyncFailureInput) {
        val action = when (input) {
            is SyncFailureInput.CycleOutcome -> state.onOutcome(input.outcome)
            is SyncFailureInput.EngineEvent ->
                if (SyncFailureSignal.isEngineFailure(input.event)) state.onEngineFailure()
                else return
        }
        try {
            execute(action)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (action == SyncFailureNotificationAction.POST) {
                state.markPostFailed()
            }
            AppLog.w(TAG, "同步失败通知动作执行异常，已隔离（收集继续）", t)
        }
    }

    private companion object {
        const val TAG = "SyncFailureNotifier"
    }
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
 * - **元素级异常隔离**（`ISSUE-P2-557`）：见 [SyncFailureSignalHandler]——单个信号处理失败
 *   不再终止收集器；
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

    /**
     * 幂等启动标志。`@Volatile`（`ISSUE-P2-557` ②）：`start()` 由冷启动点在
     * `Dispatchers.Default` 之外的调用方可能多线程触发，此前是非 `@Volatile` 裸 `var`。
     */
    @Volatile
    private var started = false

    /** 「已发出/已撤下」的唯一状态源（替代原先非 `@Volatile` 的裸 `posted`） */
    private val state = SyncFailureNotificationState()

    /** 元素级裁决 + 隔离（`ISSUE-P2-557` ①）；状态与 [state] 共用同一实例。 */
    private val handler = SyncFailureSignalHandler(execute = ::apply, state = state)

    /**
     * ISSUE-P2-548：两条流**合并为单个收集器**。
     *
     * 整改前它们是同一 scope 下的**两个兄弟协程**，而 `SyncCoordinator` 的
     * 「先 `publishSyncEvents()` 后写 `_lastOutcome`」只是**发射**顺序，
     * 不构成**执行**顺序 ⇒ 「同周期既 `post()` 又 `cancel()`、谁后跑全看线程调度」。
     *
     * 合并后只有一个收集协程，处理**严格串行**且无并发窗口——但 `merge` 只保证
     * **单条上游流内部**有序，**不保证跨上游的相对到达顺序**；终态一致依赖
     * 「事件 `cause` 与结论 `classify` 同源」（见 [SyncFailureNotificationState]）。
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
            signals().collect { input -> handler.onSignal(input) }
        }
    }

    /** 状态机结论 → Android 侧执行（仅由 [SyncFailureSignalHandler] 调用） */
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
