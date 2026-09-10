package com.keepasskey.app

import android.app.Application
import com.keepasskey.app.notification.NotificationChannels
import com.keepasskey.app.notification.UnlockedNotificationController
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.security.RuntimeIntegrityDetector
import com.keepasskey.app.sync.PeriodicSyncScheduler
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

    override fun onCreate() {
        super.onCreate()
        // ISSUE-P1-10 (ZT-10)：统一日志包装器调试开关——debug 构建开放 v/d 与完整异常堆栈，
        // release 保持关闭（AppLog.e/w 自动脱敏，R8 另行剥离 v/d 调用点）
        AppLog.debugEnabled = BuildConfig.DEBUG
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
}
