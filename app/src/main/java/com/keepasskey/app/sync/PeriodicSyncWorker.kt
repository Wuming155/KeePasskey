package com.keepasskey.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * 周期性后台同步执行体（TASK-08 整改）。
 *
 * 通过 Hilt EntryPoint 获取应用级 [SyncCoordinator] 单例执行周期工作：
 * - 开库时执行完整同步；锁库/冷启时只请求远端元数据，变化需要解锁后再合并；
 * - Worker 输出区分完整同步、待解锁、鉴权和网络结果；WorkManager success 仅表示本次调度已处理，
 *   不代表同步完成；
 * - 失败不立即重试，下个周期自然重试，避免弱网环境出现退避重试风暴；
 * - 后台周期同步的凭据复用 [SyncCoordinator] 既有封印/借用擦除链路，无新增明文驻留。
 *
 * ISSUE-P3-298 ④：`Result.success()` **不再吞掉可观测性**——每个同步周期的结果由
 * 完整同步结果由 [SyncCoordinator.syncNow] 写入 `lastOutcome` 流，经 [SyncFailureNotifier]（冷启动订阅）
 * 在失败时发出静默通知、恢复时撤下；本 Worker 的「不重试」裁决保持不变，
 * 变化的只是失败从「零感知」变为「通道级静默可见」。
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
            Result.success(coordinator.runPeriodicSync().toWorkData())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // 周期任务不重试；输出错误类别供 WorkManager 观察，避免把异常消息中的服务器数据写入状态。
            Result.success(
                Data.Builder()
                    .putString(OUTPUT_STATUS, STATUS_WORKER_ERROR)
                    .putString(OUTPUT_REASON, t.javaClass.simpleName)
                    .build()
            )
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PeriodicSyncEntryPoint {
        fun syncCoordinator(): SyncCoordinator
    }

    companion object {
        const val OUTPUT_STATUS = "periodic_sync_status"
        const val OUTPUT_REASON = "periodic_sync_reason"
        const val OUTPUT_SYNC_OUTCOME = "periodic_sync_outcome"

        const val STATUS_NOT_CONFIGURED = "not_configured"
        const val STATUS_METADATA_UNCHANGED = "metadata_unchanged"
        const val STATUS_NEEDS_UNLOCK = "needs_unlock"
        const val STATUS_AUTHENTICATION_REQUIRED = "authentication_required"
        const val STATUS_TEMPORARY_NETWORK_FAILURE = "temporary_network_failure"
        const val STATUS_CONFIGURATION_INVALID = "configuration_invalid"
        const val STATUS_REMOTE_REJECTED = "remote_rejected"
        const val STATUS_FULL_SYNC = "full_sync"
        const val STATUS_WORKER_ERROR = "worker_error"
    }
}


/** Worker 的调度成功不代表同步完成；此输出保留实际同步或探测结果。 */
internal fun PeriodicSyncWorkResult.toWorkData(): Data {
    val data = Data.Builder()
    when (this) {
        PeriodicSyncWorkResult.NotConfigured -> data.putString(
            PeriodicSyncWorker.OUTPUT_STATUS,
            PeriodicSyncWorker.STATUS_NOT_CONFIGURED
        )
        PeriodicSyncWorkResult.MetadataUnchanged -> data.putString(
            PeriodicSyncWorker.OUTPUT_STATUS,
            PeriodicSyncWorker.STATUS_METADATA_UNCHANGED
        )
        is PeriodicSyncWorkResult.NeedsUnlock -> {
            data.putString(PeriodicSyncWorker.OUTPUT_STATUS, PeriodicSyncWorker.STATUS_NEEDS_UNLOCK)
            data.putString(PeriodicSyncWorker.OUTPUT_REASON, reason.name)
        }
        PeriodicSyncWorkResult.AuthenticationRequired -> data.putString(
            PeriodicSyncWorker.OUTPUT_STATUS,
            PeriodicSyncWorker.STATUS_AUTHENTICATION_REQUIRED
        )
        PeriodicSyncWorkResult.TemporaryNetworkFailure -> data.putString(
            PeriodicSyncWorker.OUTPUT_STATUS,
            PeriodicSyncWorker.STATUS_TEMPORARY_NETWORK_FAILURE
        )
        PeriodicSyncWorkResult.ConfigurationInvalid -> data.putString(
            PeriodicSyncWorker.OUTPUT_STATUS,
            PeriodicSyncWorker.STATUS_CONFIGURATION_INVALID
        )
        is PeriodicSyncWorkResult.RemoteRejected -> {
            data.putString(PeriodicSyncWorker.OUTPUT_STATUS, PeriodicSyncWorker.STATUS_REMOTE_REJECTED)
            data.putString(PeriodicSyncWorker.OUTPUT_REASON, errorType)
        }
        is PeriodicSyncWorkResult.FullSync -> {
            data.putString(PeriodicSyncWorker.OUTPUT_STATUS, PeriodicSyncWorker.STATUS_FULL_SYNC)
            data.putString(PeriodicSyncWorker.OUTPUT_SYNC_OUTCOME, outcome::class.simpleName)
        }
    }
    return data.build()
}
