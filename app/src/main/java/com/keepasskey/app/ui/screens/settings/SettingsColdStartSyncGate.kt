package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 冷启动一次性自动同步闸门（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出，纯结构性拆分）。
 *
 * 进程生命周期内仅触发一次「设置允许则自动同步」：软件被彻底杀死重启时标志复位，从而再次触发。
 * 标志刻意保持 **process-static**（伴生对象），与拆出前的 `SettingsViewModel.companion` 语义完全一致
 * ——ViewModel 实例重建既不会重复触发，也不会漏触发。
 */
internal class SettingsColdStartSyncGate(
    private val settingsRepository: SettingsRepository,
    private val onTriggerSync: () -> Unit,
    private val scope: CoroutineScope
) {

    fun checkAndTrigger() {
        if (hasCheckedColdStartSync) return
        hasCheckedColdStartSync = true
        scope.launch {
            try {
                if (settingsRepository.getSettings().first().syncOnColdStart) {
                    onTriggerSync()
                }
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        // 标记当前应用进程生命周期内是否已执行过冷启动同步检测
        @Volatile
        private var hasCheckedColdStartSync = false
    }
}
