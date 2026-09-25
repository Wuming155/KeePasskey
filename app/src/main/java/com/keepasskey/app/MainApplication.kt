package com.keepasskey.app

import android.app.Application
import androidx.work.Configuration
import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.app.notification.NotificationChannels
import com.keepasskey.app.notification.UnlockedNotificationController
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCacheEvictor
import com.keepasskey.app.sync.SyncFailureNotifier
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    /**
     * WorkManager **按需初始化**配置（ISSUE-P1-238，2026-09-21 真机实测后整改）。
     *
     * 本应用此前的 `AndroidManifest` 未干预 `androidx.startup` 的
     * `androidx.work.WorkManagerInitializer`，于是每次冷启动都会在
     * **ContentProvider 阶段（早于 [onCreate]）**构造 `WorkManagerImpl`：打开
     * WorkManager 自己的 Room 数据库、建立 `SystemJobScheduler` 并执行
     * `ForceStopRunnable` 对账。真机实测该段占 **0.41 s**（同轮 `Start proc` →
     * 凭据提供者应答预算仅约 3.0 s，见 `ISSUE-P1-238` 的实测表）。
     *
     * 而 WorkManager 在冷启动路径上**没有任何时序依赖**——唯一的冷启动调用方
     * [PeriodicSyncScheduler.applySavedSchedule] 早已被移入后台作用域（`ISSUE-P1-237`）。
     * 故改为：清单里摘除该 initializer，本类实现 [Configuration.Provider] 提供与
     * 「默认配置」等价的配置，`WorkManager.getInstance()` 首次被真正调用时才初始化
     * （即那条后台协程），冷启动主线程不再为它付费。
     *
     * **不得**改为直接 `WorkManager.initialize()`：`getInstance()` 在未初始化且应用
     * 未实现本接口时会抛 `IllegalStateException`，按需初始化必须由本接口承担。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    @Inject
    lateinit var periodicSyncScheduler: PeriodicSyncScheduler

    @Inject
    lateinit var autoLockManager: AutoLockManager

    @Inject
    lateinit var unlockedNotificationController: UnlockedNotificationController

    // ISSUE-P3-298 ④：后台同步失败可见性——订阅 SyncCoordinator.lastOutcome / syncEvents，
    // 失败亮静默通知、恢复即撤，替代此前「周期任务 Result.success() 吞掉全部失败」的零感知状态。
    @Inject
    lateinit var syncFailureNotifier: SyncFailureNotifier

    // F-13（P1）：附件明文缓存的冷启动清理入口。注入既有 @Singleton 单例（不新建并行实现），
    // 与 DatabaseModule 注册的同一实例（锁定观察者）共用一份 `cacheDir/attachments` 目录。
    @Inject
    lateinit var fileBinaryStore: FileBinaryStore

    // ISSUE-P2-51：剪贴板敏感值冷启动对账入口（同一 @Singleton，亦在 DatabaseModule 注册为锁定观察者）
    @Inject
    lateinit var clipboardSecurityManager: ClipboardSecurityManager

    // ISSUE-P3-116：同步缓存销毁器（`cacheDir/sync` 的 KDBX 密文快照）。
    // 与 fileBinaryStore 一起构成「彻底退出应用」前的易失缓存清理面。
    @Inject
    lateinit var syncCacheEvictor: SyncCacheEvictor

    /**
     * 「彻底退出应用」前的**易失缓存清理**（ISSUE-P3-116）。
     *
     * 背景：该入口此前只做 `finishAffinity + exitProcess`，用户以为「退出即不留痕」，
     * 而磁盘上仍留着两类可被离线读取的内容——`cacheDir/attachments` 的**附件解密明文**
     * （F-13 已确认的绕过口令读取路径）与 `cacheDir/sync` 的**完整 KDBX 密文快照**
     * （可离线无限期爆破主密码）。二者原本只在下一次**冷启动**或**会话锁定**时清理。
     *
     * 语义边界（如实声明）：
     * - 只清**缓存目录**；`<库>.kdbx.bak`（滚动备份，属用户数据而非缓存）与
     *   防回滚状态（`filesDir/rollback`）**不在**清理面内，其口径见
     *   `docs/architecture/已知工程限界.md` §1.2；
     * - 清理为 best-effort：任一实现内部失败只落脱敏日志，**不**阻断退出；
     * - 与反取证无关：落盘清理仍是 unlink-only（`docs/architecture/已知工程限界.md` §1.5），
     *   **不得**据此断言「退出即不可恢复」。
     *
     * @return 两个清理面均成功时为 true（仅用于日志与单测观察，调用方无须据此决策）
     */
    fun purgeVolatileCachesBeforeExit(): Boolean {
        val attachmentsCleared = runCatching {
            fileBinaryStore.clear()
            true
        }.getOrElse { t ->
            AppLog.w(TAG, "退出前附件缓存清理失败: ${t.javaClass.simpleName}")
            false
        }
        // SyncCacheEvictor 内部已保证不自抛（失败返回 false 并落日志）
        val syncCleared = runCatching { syncCacheEvictor.evictAll() }.getOrElse { false }
        AppLog.i(TAG, "退出前易失缓存清理: attachments=$attachmentsCleared, sync=$syncCleared")
        return attachmentsCleared && syncCleared
    }

    override fun onCreate() {
        super.onCreate()
        // ISSUE-P1-10 (ZT-10)：统一日志包装器调试开关——debug 构建开放 v/d 与完整异常堆栈，
        // release 保持关闭（AppLog.e/w 自动脱敏，R8 另行剥离 v/d 调用点）
        AppLog.debugEnabled = BuildConfig.DEBUG
        // F-13（P1）：附件落盘目录（cacheDir/attachments）存放的是**附件解密后的明文**，
        // 仅靠 SessionLockObserver.onSessionLocked() 清理存在已确认缺口：进程被 kill /
        // force-stop / OOM 回收时该回调不执行，明文会跨进程存活到下一次锁定，
        // 构成目前唯一**已确认**的「无需口令即可读取库内容」路径（F-13）。
        //
        // 时序前提：本调用位于 Application.onCreate 起始段，此刻**尚无任何会话被打开**
        // （解锁页 / 自动填充 / 凭据提供者等入口都晚于 Application.onCreate），
        // 缓存目录内不存在仍被会话持有的条目，因此可安全整体清空。
        // 契约：清理失败只在 FileBinaryStore 内部记脱敏日志（clear() 不外抛），绝不阻断冷启动。
        fileBinaryStore.clear()
        // ISSUE-P2-51：剪贴板冷启动对账——若上一次进程在敏感值驻留窗口内被 kill / force-stop，
        // 自动擦除协程未执行，此处按跨进程布尔待清标记 fail-safe 清空（无明文 / 摘要留存）。
        // ISSUE-P3-84：同时注册熄屏广播与「切到后台即清空」观察者（主线程冷启动点，幂等）。
        clipboardSecurityManager.initialize()
        clipboardSecurityManager.reconcileOnColdStart()
        // ISSUE-P0-01 (ZT-01)：自动锁定守护下沉至进程级唯一冷启动点——
        // 应用存在 AutofillUnlockActivity / CredentialUnlockActivity 两条不经 MainActivity
        // 的独立冷启动入口，守护（ProcessLifecycleOwner + 熄屏广播）必须在进程创建时注册，
        // 保证任意入口冷启动后熄屏熔断与后台超时锁定均全程生效（幂等守卫保留）。
        autoLockManager.initialize()
        // TASK-08 整改：冷启动按持久化偏好恢复周期后台同步调度
        // （默认关闭，未开启时行为与既往完全一致）
        //
        // ISSUE-P1-237（2026-09-21 真机定位）：**不得同步执行**——本调用会拉起 WorkManager
        // （其自身需要打开自己的数据库与 executor），是 `Application.onCreate` 主线程上最重的
        // 一段；而「按偏好恢复周期调度」**没有任何冷启动时序依赖**（不解锁、不下发数据、
        // 不参与任何门控判定）。
        //
        // 实测代价（同机多次）：原同步调用下，系统绑定凭据提供者时进程从 `Start proc` 到
        // `onBeginCreateCredentialRequest` 首行耗时 2.49 ~ 3.05 s，而系统给 provider 的应答
        // 预算约 3.0 s —— 已实测出现 `Remote provider response timed out`，请求被直接丢弃，
        // 用户视角即「点了保存/继续没反应」。移至后台线程后该段不再占用冷启动关键路径。
        coldStartBackgroundScope.launch { periodicSyncScheduler.applySavedSchedule() }
        // ISSUE-P3-18：通知通道必须在任何 notify 之前建立（Android 8+ 向不存在的通道发送通知
        // 会被系统静默丢弃）；进程唯一冷启动点幂等建立，覆盖全部冷启动入口。
        NotificationChannels.ensureCreated(this)
        // ISSUE-P3-18：已解锁常驻通知控制器——观察 DatabaseSession 会话态，
        // 解锁（OPENED/DIRTY）即发、锁定/关闭即撤，受 showUnlockedNotification 偏好与通知权限双闸门约束。
        unlockedNotificationController.start()
        // ISSUE-P3-298 ④：同步失败可见性订阅器（进程级唯一冷启动点启动，幂等；
        // 内部自带通知权限闸门，未授权即静默降级，不参与任何门控判定）
        syncFailureNotifier.start()
    }

    /**
     * 冷启动**非关键路径**工作作用域（ISSUE-P1-237）。
     *
     * 只承载「没有冷启动时序依赖、但对时延敏感」的收尾工作：这些工作若留在
     * [onCreate] 主线程上，会把本进程的所有系统回调（凭据提供者 / 自动填充）推迟到
     * 系统应答预算之外。**不得**把任何参与门控判定或清理对账的工作放进本作用域
     * ——那类工作必须在冷启动点同步完成（见 [ColdStartAttachmentPurgeWiringTest]）。
     *
     * 作用域与进程同生命周期，无需取消：进程终止即随之回收。
     */
    private val coldStartBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "MainApplication"
    }
}
