package com.keepasskey.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 周期性后台同步执行体（TASK-08 整改）。
 *
 * 通过 Hilt EntryPoint 获取应用级 [SyncCoordinator] 单例执行一次同步周期：
 * - 与前台手动同步/冷启动同步共享同一 mutex，天然串行互斥；
 * - 遇 `ConflictNeedsUser`（条目级冲突待用户决策）静默保留本地安全副本，
 *   绝不在后台代做取舍；`Error` 亦不重试——下个周期会自然重试，
 *   避免退避重试风暴打爆弱网环境；
 * - 后台周期同步的凭据复用 [SyncCoordinator] 既有封印/借用擦除链路，无新增明文驻留。
 */
class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val coordinator = EntryPointAccessors.fromApplication(
            applicationContext, PeriodicSyncEntryPoint::class.java
        ).syncCoordinator()

        return try {
            when (val outcome = coordinator.syncNow()) {
                is SyncOutcome.ConflictNeedsUser ->
                    // 冲突必须由用户在冲突解决界面决策，后台仅保留本地副本等待前台收敛
                    Result.success()
                else -> Result.success()
            }
        } catch (t: Throwable) {
            // 周期任务返回 success 而非 retry：失败场景下个周期自然重试，避免退避风暴
            Result.success()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PeriodicSyncEntryPoint {
        fun syncCoordinator(): SyncCoordinator
    }
}
