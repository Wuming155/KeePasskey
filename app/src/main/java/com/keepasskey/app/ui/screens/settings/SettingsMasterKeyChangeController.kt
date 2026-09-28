package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 更换主密钥任务的局部状态（ISSUE-P2-354 AC③）。
 *
 * 由本控制器以 `StateFlow` 持有、经 `SettingsUiStateProjection` 并入 [SettingsUiState]——
 * busy 与回执都活在 ViewModel 域（不随组合销毁），对话框重开时也能读到「任务进行中」
 * 而不至于放行重复提交。（声明置于同包独立文件：`SettingsUiState.kt` 行数分档闸门。）
 */
internal data class MasterKeyChangeTaskState(
    val isChanging: Boolean = false,
    val feedback: UiMessage? = null
)

/**
 * 更换主密钥任务执行体（`ISSUE-P2-354 AC③` 自 `SettingsViewModel.changeMasterPassword` 下沉；
 * 行数分档闸门 tier1≤500 倒逼同批下沉，守卫与擦除语义逐字未改）。
 *
 * - **作用域**：全库 Argon2 重派生 + 重加密是临界写，任务挂注入的 [scope]
 *   （生产即 `SettingsViewModel.viewModelScope`）——不再跑在 UI 层 `rememberCoroutineScope` 上，
 *   切 Tab / 组合销毁都不会取消（底栏 `saveState` 下 ViewModel 存活）；
 * - **忙守卫**：[MasterKeyChangeTaskState.isChanging] 为 true 时并发第二次**直接拒绝**，
 *   且入参数组照样清零——数组所有权自 `submit` 调用起移交本类，忙 / 闲、成败、异常路径
 *   都由本类的 `finally` 负责擦除；
 * - **回执**：结果经 [state] 的 `feedback` 下发（`SettingsContent` 既有 Snackbar 路径展示后
 *   由 `clearFeedback` 清除），对话框据 busy 回落自行关闭。
 */
internal class SettingsMasterKeyChangeController(
    private val repository: VaultRepository,
    private val scope: CoroutineScope
) {

    private val mutableState = MutableStateFlow(MasterKeyChangeTaskState())

    /** busy + 结果反馈（投影层订阅它并入 [SettingsUiState]）。 */
    val state: StateFlow<MasterKeyChangeTaskState> = mutableState.asStateFlow()

    /** 提交新主口令（[newPasswordChars] 所有权移交本方法，见类 KDoc 的擦除契约）。 */
    fun submit(newPasswordChars: CharArray) {
        if (mutableState.value.isChanging) {
            newPasswordChars.fill('0')
            return
        }
        mutableState.update { it.copy(isChanging = true) }
        scope.launch {
            try {
                val result = repository.changeMasterPassword(newPasswordChars)
                mutableState.update {
                    it.copy(
                        feedback = when (result) {
                            is KdbxResult.Success<*> -> UiMessage(R.string.set_master_key_updated)
                            is KdbxResult.Failure -> UiMessage(R.string.op_failed, listOf(result.message))
                        }
                    )
                }
            } finally {
                newPasswordChars.fill('0')
                mutableState.update { it.copy(isChanging = false) }
            }
        }
    }

    /** 回执经 Snackbar 展示后清除（一次性消息语义）。 */
    fun clearFeedback() {
        mutableState.update { it.copy(feedback = null) }
    }
}
