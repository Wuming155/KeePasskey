package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.sync.engine.SyncCacheEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 密码库列表页的**同步指示与下拉刷新编排**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 只承接「下拉手势 → `SyncCoordinator.syncNow()` → 状态/文案上浮」这一条链路，原实现逐字迁移。
 * 用户消息经 [onMessage] 回调上抛，由 ViewModel 统一写入 `userMessageFlow`（保持单一消息源）。
 */
internal class VaultListSyncController(
    private val syncCoordinator: SyncCoordinator,
    private val scope: CoroutineScope,
    private val strings: StringsProvider,
    private val onMessage: (UiMessage) -> Unit
) {

    private val syncStatusFlow = MutableStateFlow(VaultSyncStatus.SYNCED)
    private val isSyncingFlow = MutableStateFlow(false)
    private val lastSyncTimeMillisFlow = MutableStateFlow(0L)

    /** 云端同步状态（SYNCING / SYNCED / CONFLICT / OFFLINE） */
    val syncStatus: StateFlow<VaultSyncStatus> = syncStatusFlow

    /** 是否正在执行同步（驱动下拉指示区） */
    val isSyncing: StateFlow<Boolean> = isSyncingFlow

    /** 上次同步完成时间的展示文案；0 表示本会话尚未同步过 */
    val lastSyncTimeText: Flow<String> = lastSyncTimeMillisFlow.map { formatLastSyncTime(it) }

    /** 仅当会话配置了云同步时才自动触发一次（解锁进入列表页时由 ViewModel 调用） */
    fun isSyncConfigured(): Boolean = syncCoordinator.isSyncConfigured()

    /**
     * 下拉手势同步触发：真实执行 SyncCoordinator 全量同步（不再使用演示性假桩）。
     */
    fun triggerPullRefresh() {
        if (isSyncingFlow.value) return
        scope.launch {
            isSyncingFlow.value = true
            syncStatusFlow.value = VaultSyncStatus.SYNCING
            val outcome = syncCoordinator.syncNow()
            applySyncOutcome(outcome)
            surfaceSyncCacheEvents()
            isSyncingFlow.value = false
        }
    }

    /**
     * ICacheSupervisor 六事件上浮：把引擎层缓存监督事件转化为可读的用户提示，
     * 覆盖「云端已更新刷新本地」「保存失败留本地」两类最需要用户知情的事件。
     */
    private fun surfaceSyncCacheEvents() {
        val events = syncCoordinator.recentSyncEvents.value
        when {
            events.any { it is SyncCacheEvent.CouldntSaveToRemote } ->
                onMessage(UiMessage(R.string.vault_sync_saved_locally))
            events.any { it is SyncCacheEvent.UpdatedCachedFileOnLoad } ->
                onMessage(UiMessage(R.string.vault_sync_remote_updated))
            else -> Unit
        }
    }

    private fun applySyncOutcome(outcome: SyncOutcome) {
        when (outcome) {
            is SyncOutcome.UpToDate -> {
                syncStatusFlow.value = VaultSyncStatus.SYNCED
                lastSyncTimeMillisFlow.value = System.currentTimeMillis()
                onMessage(UiMessage(R.string.vault_sync_completed))
            }
            is SyncOutcome.UploadedLocal,
            is SyncOutcome.MergedAndUploaded -> {
                syncStatusFlow.value = VaultSyncStatus.SYNCED
                lastSyncTimeMillisFlow.value = System.currentTimeMillis()
                onMessage(UiMessage(R.string.vault_sync_uploaded))
            }
            is SyncOutcome.ConflictNeedsUser -> {
                syncStatusFlow.value = VaultSyncStatus.CONFLICT
                onMessage(UiMessage(R.string.sync_feedback_conflict))
            }
            is SyncOutcome.Offline -> {
                syncStatusFlow.value = VaultSyncStatus.OFFLINE
                onMessage(UiMessage(R.string.sync_feedback_offline))
            }
            is SyncOutcome.Error -> {
                syncStatusFlow.value = VaultSyncStatus.OFFLINE
                onMessage(UiMessage(R.string.sync_feedback_error, listOf(outcome.message)))
            }
        }
    }

    /**
     * 将时间戳格式化为相对日期文案（今天 / 昨天 / 具体日期）+ HH:mm；
     * 0 表示本会话尚未执行过同步
     */
    private fun formatLastSyncTime(millis: Long): String {
        if (millis <= 0L) return strings.get(R.string.sync_last_time_never)
        val dateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        val today = LocalDate.now()
        val datePrefix = when (dateTime.toLocalDate()) {
            today -> strings.get(R.string.time_today)
            today.minusDays(1) -> strings.get(R.string.time_yesterday)
            else -> dateTime.format(DateTimeFormatter.ofPattern(strings.get(R.string.date_pattern_month_day)))
        }
        return "$datePrefix ${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
}
