package com.keepasskey.app.ui.screens.database

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「从来源打开已有库」的**提交互斥**（`ISSUE-P3-582` AC③）。
 *
 * 自 `DatabasePickerViewModel` 拆出（与 `DatabasePickerRemoteBrowseController` /
 * `DatabasePickerCloudAccountController` 同范式的薄控制器；`§391` 行数分档闸门）：
 * 原实现为裸 `viewModelScope.launch` 无 busy 守卫，对话框确认按钮的 `enabled` 只是 UI 层，
 * 挡不住重帧内的二次触发——**云端下载是秒级网络操作**，在途窗口内连点会并发导入。
 *
 * 守卫口径与 [DatabasePickerViewModel.createDatabase] 同源：`_busy` 在**协程之外同步置位**
 * （挡同帧双击），在任何结果路径（成功 / 失败 / 提前返回 / 取消）的 `finally` 复位；
 * 在途期间的重复 [submit] 被直接拒绝。`busy` 投影进 `DatabasePickerUiState.isLoading`，
 * 使页面按钮与进度条在导入窗口内同步 busy。
 *
 * @param scope 宿主 ViewModel 的作用域（生产恒 `viewModelScope`，随页面销毁一并取消）
 * @param importer 既有登记流程（本地 / 云端两条路径共用同一出口）
 */
internal class DatabasePickerImportController(
    private val scope: CoroutineScope,
    private val importer: DatabasePickerOpenVaultImporter
) {

    private val _busy = MutableStateFlow(false)

    /** 导入在途（busy 守卫真相源；投影为 `DatabasePickerUiState.isLoading`） */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 提交导入；在途期间重复提交被**同步拒绝**（不启动新协程、不触发第二次下载 / 登记）。
     */
    fun submit(submission: OpenVaultSubmission) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                when (submission) {
                    is OpenVaultSubmission.Local -> importer.importLocal(submission)
                    is OpenVaultSubmission.Cloud -> importer.importCloud(submission.request)
                }
            } finally {
                // 任何结果路径（成功 / 失败 / 提前返回 / 取消）都回落
                _busy.value = false
            }
        }
    }
}
