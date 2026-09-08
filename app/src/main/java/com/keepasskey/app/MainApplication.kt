package com.keepasskey.app

import android.app.Application
import com.keepasskey.app.sync.PeriodicSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {

    @Inject
    lateinit var periodicSyncScheduler: PeriodicSyncScheduler

    override fun onCreate() {
        super.onCreate()
        // TASK-08 整改：冷启动按持久化偏好恢复周期后台同步调度
        // （默认关闭，未开启时行为与既往完全一致）
        periodicSyncScheduler.applySavedSchedule()
    }
}
