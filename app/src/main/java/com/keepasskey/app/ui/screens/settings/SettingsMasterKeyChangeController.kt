package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
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
 *
 * ISSUE-P2-398：**改密成功后**追加生物识别封印凭据重封印（[resealAfterChange]，可空 =
 * 未装配 / 单测），同任务内串行执行——此时新密码仍在入参数组中存活（`finally` 才清零），
 * 是唯一能以新密码重封印的窗口；重封印任何失败都 fail-safe（不影响已成功的改密回执），
 * 且不改变 busy 语义（对话框在 BiometricPrompt 收起后才回落）。
 */
internal class SettingsMasterKeyChangeController(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    /** 忙守卫 + ISSUE-P2-398：改密成功后的重封印挂点（activity 宿主由 UI 层透传；null 时不重封印）；
     * ISSUE-P3-430：密码入参可为 null = 密码分量未变（仅改绑密钥文件，由会话快照封印） */
    private val resealAfterChange: (suspend (FragmentActivity?, CharArray?) -> Unit)? = null
) {

    private val mutableState = MutableStateFlow(MasterKeyChangeTaskState())

    /** busy + 结果反馈（投影层订阅它并入 [SettingsUiState]）。 */
    val state: StateFlow<MasterKeyChangeTaskState> = mutableState.asStateFlow()

    /**
     * 提交凭据变更（[newPasswordChars] 所有权移交本方法，见类 KDoc 的擦除契约）。
     *
     * ISSUE-P3-428：[keyFileIntent] 透传对密钥文件第二因子的意图（默认 Keep 沿用
     * 既有语义）；`Use` 携带的字节同为借用语义——仓库侧只读不擦除，**清零责任在本
     * 方法的 `finally`**（与密码数组同一收尾窗口），成败与异常路径均不遗留。
     * ISSUE-P3-430：[newPasswordChars] 为**空数组** = 密码分量不变，走
     * [VaultRepository.changeKeyFileOnly]（仅改绑密钥文件；此时 [keyFileIntent] 必须非
     * `Keep`，否则无任何改动——同步拒绝并留痕，不产生假回执）；重封印入参对应传 null。
     */
    fun submit(
        newPasswordChars: CharArray,
        keyFileIntent: ChangeKeyFileIntent = ChangeKeyFileIntent.Keep,
        activity: FragmentActivity? = null
    ) {
        val keepPassword = newPasswordChars.isEmpty()
        if (keepPassword && keyFileIntent == ChangeKeyFileIntent.Keep) {
            // 无任何改动（对话框闸门本应拦住）：原样清零，不进任务、不报错
            newPasswordChars.fill('0')
            keyFileIntent.eraseBorrowedBytes()
            return
        }
        if (mutableState.value.isChanging) {
            newPasswordChars.fill('0')
            keyFileIntent.eraseBorrowedBytes()
            return
        }
        mutableState.update { it.copy(isChanging = true) }
        scope.launch {
            try {
                val result = if (keepPassword) {
                    repository.changeKeyFileOnly(keyFileIntent)
                } else {
                    repository.changeMasterPassword(newPasswordChars, keyFileIntent)
                }
                mutableState.update {
                    it.copy(
                        feedback = when (result) {
                            is KdbxResult.Success<*> -> UiMessage(R.string.set_master_key_updated)
                            is KdbxResult.Failure -> UiMessage(R.string.op_failed, listOf(result.message))
                        }
                    )
                }
                if (result is KdbxResult.Success) {
                    resealAfterChange?.invoke(activity, newPasswordChars.takeIf { !keepPassword })
                }
            } finally {
                newPasswordChars.fill('0')
                keyFileIntent.eraseBorrowedBytes()
                mutableState.update { it.copy(isChanging = false) }
            }
        }
    }

    /** 回执经 Snackbar 展示后清除（一次性消息语义）。 */
    fun clearFeedback() {
        mutableState.update { it.copy(feedback = null) }
    }
}

/** ISSUE-P3-428：擦除 `Use` 意图携带的借用密钥文件字节（其余意图为 no-op）。 */
private fun ChangeKeyFileIntent.eraseBorrowedBytes() {
    if (this is ChangeKeyFileIntent.Use) bytes.fill(0)
}
