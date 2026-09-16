package com.keepasskey.app

import android.app.Application
import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.app.notification.NotificationChannels
import com.keepasskey.app.notification.UnlockedNotificationController
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.security.RuntimeIntegrityDetector
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCacheEvictor
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var periodicSyncScheduler: PeriodicSyncScheduler

    @Inject
    lateinit var autoLockManager: AutoLockManager

    @Inject
    lateinit var runtimeIntegrityDetector: RuntimeIntegrityDetector

    @Inject
    lateinit var unlockedNotificationController: UnlockedNotificationController

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
        // ISSUE-P2-08 (ZT-13)：运行环境完整性探测在进程唯一冷启动点显式启动（幂等）——
        // 组件 init 块已自动启动一次，此处显式接线保证任意冷启动入口都完成初始化；
        // 探测结果经 RuntimeIntegrityGate 暴露给敏感通道（生物识别快速解锁 / 自动填充）做 fail-closed 裁决。
        runtimeIntegrityDetector.start()
        // TASK-08 整改：冷启动按持久化偏好恢复周期后台同步调度
        // （默认关闭，未开启时行为与既往完全一致）
        periodicSyncScheduler.applySavedSchedule()
        // ISSUE-P3-18：通知通道必须在任何 notify 之前建立（Android 8+ 向不存在的通道发送通知
        // 会被系统静默丢弃）；进程唯一冷启动点幂等建立，覆盖全部冷启动入口。
        NotificationChannels.ensureCreated(this)
        // ISSUE-P3-18：已解锁常驻通知控制器——观察 DatabaseSession 会话态，
        // 解锁（OPENED/DIRTY）即发、锁定/关闭即撤，受 showUnlockedNotification 偏好与通知权限双闸门约束。
        unlockedNotificationController.start()
    }

    private companion object {
        const val TAG = "MainApplication"
    }
}
