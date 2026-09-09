package com.keepasskey.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 周期性后台同步调度器（TASK-08 整改）。
 *
 * 设置项 `periodicBackgroundSyncEnabled`（默认 30 分钟）与 `wifiOnlySync` 此前为
 * 无消费方的空开关；本调度器把它们接入 WorkManager 唯一周期任务：
 * - 开启：按间隔（强制 ≥ [MIN_INTERVAL_MINUTES] 分钟，WorkManager 系统下限）注册
 *   周期任务，网络约束按「仅 Wi-Fi」映射为 UNMETERED / CONNECTED；
 * - 关闭：取消唯一任务；
 * - 间隔变更：以 [ExistingPeriodicWorkPolicy.UPDATE] 原子更新，不打断运行中的任务。
 *
 * 调度时机：应用冷启动（MainApplication 按持久化偏好恢复）与设置页三项开关变更。
 * 同步执行体为 [PeriodicSyncWorker]（内部经 SyncCoordinator 串行 mutex，与前台手动
 * 同步/冷启动同步天然互斥，无并发竞争）。
 */
@Singleton
class PeriodicSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val extendedSettingsStore: ExtendedSettingsStore
) {

    /** 应用冷启动：按持久化偏好恢复调度（默认关闭，无行为变化） */
    fun applySavedSchedule() {
        reschedule(
            enabled = extendedSettingsStore.load().periodicBackgroundSyncEnabled,
            intervalMinutes = extendedSettingsStore.load().periodicBackgroundSyncIntervalMinutes,
            wifiOnly = extendedSettingsStore.loadWifiOnlySync()
        )
    }

    /** 依据最新偏好重排唯一周期任务 */
    fun reschedule(enabled: Boolean, intervalMinutes: Int, wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
            clampIntervalMinutes(intervalMinutes), TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "periodic_vault_sync"

        /** WorkManager 周期任务系统下限：15 分钟 */
        const val MIN_INTERVAL_MINUTES = 15

        /** 间隔下限钳制（纯函数，供单元测试） */
        fun clampIntervalMinutes(minutes: Int): Long =
            minutes.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES.toLong())
    }
}
