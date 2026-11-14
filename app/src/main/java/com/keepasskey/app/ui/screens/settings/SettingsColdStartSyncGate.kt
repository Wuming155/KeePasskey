package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 冷启动一次性自动同步闸门（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出，纯结构性拆分）。
 *
 * 进程生命周期内仅触发一次「设置允许则自动同步」：软件被彻底杀死重启时标志复位，从而再次触发。
 * 标志刻意保持 **process-static**（伴生对象），与拆出前的 `SettingsViewModel.companion` 语义完全一致
 * ——ViewModel 实例重建既不会重复触发，也不会漏触发。
 *
 * 注意：仅在设置页 ViewModel 创建时检查；库列表解锁路径的自动同步另见
 * `VaultListViewModel`（`autoSyncEnabled && isSyncConfigured`）。
 */
internal class SettingsColdStartSyncGate(
    private val settingsRepository: SettingsRepository,
    private val onTriggerSync: () -> Unit,
    private val scope: CoroutineScope,
    /** 判定「已配置云同步」；false 则不触发（避免无凭据空跑） */
    private val isSyncConfigured: () -> Boolean = { true }
) {
    fun checkAndTrigger() {
        if (hasCheckedColdStartSync) return
        hasCheckedColdStartSync = true
        scope.launch {
            try {
                if (!isSyncConfigured()) return@launch
                if (settingsRepository.getSettings().first().syncOnColdStart) {
                    onTriggerSync()
                }
            } catch (t: Exception) {
                if (t is CancellationException) throw t
                // 冷启动同步检测失败此前完全静默：用户既没触发同步，日志里也没有记录。
                AppLog.w(TAG, "冷启动同步检测失败: ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 单测用：复位进程级标志。
     *
     * 标志刻意是 `process-static`（生产语义要求进程内只触发一次），代价是**同一 JVM 内的多个
     * 用例共享它**：先跑的用例消费掉「唯一一次」后，后续用例的 `checkAndTrigger()` 直接返回，
     * 留痕断言拿到空列表——按类单独跑绿、整包跑红。故提供本复位口，由用例在 `@Before` 调用。
     */
    internal fun resetForTest() {
        hasCheckedColdStartSync = false
    }

    private companion object {
        private const val TAG = "ColdStartSync"

        // 标记当前应用进程生命周期内是否已执行过冷启动同步检测
        @Volatile
        private var hasCheckedColdStartSync = false
    }
}
