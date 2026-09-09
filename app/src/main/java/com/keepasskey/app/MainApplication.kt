package com.keepasskey.app

import android.app.Application
import com.keepasskey.app.security.AutoLockManager
import com.keepasskey.app.sync.PeriodicSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var periodicSyncScheduler: PeriodicSyncScheduler

    @Inject
    lateinit var autoLockManager: AutoLockManager

    override fun onCreate() {
        super.onCreate()
        // ISSUE-P0-01 (ZT-01)：自动锁定守护下沉至进程级唯一冷启动点——
        // 应用存在 AutofillUnlockActivity / CredentialUnlockActivity 两条不经 MainActivity
        // 的独立冷启动入口，守护（ProcessLifecycleOwner + 熄屏广播）必须在进程创建时注册，
        // 保证任意入口冷启动后熄屏熔断与后台超时锁定均全程生效（幂等守卫保留）。
        autoLockManager.initialize()
        // TASK-08 整改：冷启动按持久化偏好恢复周期后台同步调度
        // （默认关闭，未开启时行为与既往完全一致）
        periodicSyncScheduler.applySavedSchedule()
    }
}
