package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.keepasskey.app.ui.model.textArg
import com.keepasskey.app.ui.model.StringsProvider

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
 *
 * ISSUE-P3-434：**改绑/解绑成功后**同步「记住的密钥文件位置」（[keyFileAccess]，可空 =
 * 未装配 / 单测）——`Use` 按偏好与持久授权登记新来源或清除旧记录，`Remove` 清除记录；
 * 记忆记录是冷启动记忆恢复与指纹现读链（P1-431 第③步）的唯一指向，陈旧记录必须清除。
 *
 * §411（ISSUE-P3-448）：上述同步同时维护**应用私有目录收编副本**（[vaultCopyStore]，可空 =
 * 单测未装配）——`Use` 成功即收编新文件字节（不再依赖持久授权），`Remove` / 偏好关闭清除副本；
 * 另承载设置页「导入密钥文件」行的新语义入口 [importRememberedCopy]：把解锁时所选（记忆）
 * 的密钥文件收编进私有目录，不再要求用户重复手选。
 */
internal class SettingsMasterKeyChangeController(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    /** 忙守卫 + ISSUE-P2-398：改密成功后的重封印挂点（activity 宿主由 UI 层透传；null 时不重封印）；
     * ISSUE-P3-430：密码入参可为 null = 密码分量未变（仅改绑密钥文件，由会话快照封印） */
    private val resealAfterChange: (suspend (FragmentActivity?, CharArray?) -> Unit)? = null,
    /** ISSUE-P3-434：记忆记录同步通道（生产 = `SafKeyFileAccess`；null 时不同步，单测可注入假实现） */
    private val keyFileAccess: KeyFileAccess? = null,
    /** §411（ISSUE-P3-448）：私有目录收编副本通道（null 时副本面整体旁路，单测未装配） */
    private val vaultCopyStore: com.keepasskey.app.security.KeyFileVaultCopyStore? = null,
    /**
     * 活动库 id（副本键）。默认实现＝活动库登记 id（副本面与解锁页 `activeDatabaseId` 同源）；
     * 注入缺失（单测）时副本面整体旁路。
     */
    private val activeDbId: suspend () -> String? = {
        repository.getDatabases().first().firstOrNull { it.isActive }?.id
    },
    // ISSUE-P3-453：错误码 → 本地化文案通道（置于末位以免打乱既有位置传参；生产由宿主显式注入）
    private val strings: StringsProvider = StringsProvider { _, _ -> "" }
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
     * ISSUE-P3-434：成功后同步「记住的密钥文件位置」（[syncRememberedKeyFile]），
     * 在重封印（可能等待 BiometricPrompt 用户授权）之前完成——纯偏好层 IO，不阻塞用户。
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
                            is KdbxResult.Failure -> UiMessage(R.string.op_failed, listOf(result.textArg(strings)))
                        }
                    )
                }
                if (result is KdbxResult.Success) {
                    // ISSUE-P3-434：记忆记录必须与新绑定的密钥文件一致（先于重封印）；
                    // §411（P3-448）：副本随同一裁决收编 / 清除
                    syncRememberedKeyFile(keyFileIntent)
                    resealAfterChange?.invoke(activity, newPasswordChars.takeIf { !keepPassword })
                }
            } finally {
                newPasswordChars.fill('0')
                keyFileIntent.eraseBorrowedBytes()
                mutableState.update { it.copy(isChanging = false) }
            }
        }
    }

    /**
     * 改绑 / 解绑成功后同步「记住的密钥文件位置」（ISSUE-P3-434）。
     *
     * 与解锁页 `rememberKeyFileOnSuccess` 同口径（ISSUE-P3-04）：
     * - 偏好关闭 → 清除记录（不留任何密钥文件元数据）；
     * - `Use`：偏好开启 + 来源 Uri 非空 + 对其取得**持久化读授权** → 登记新来源；
     *   任一不满足 → 清除旧记录（旧记录指向已解绑的文件，留存必然误导下次冷启动）；
     * - `Remove` → 清除记录（改后库无第二因子）；
     * - `Keep` → 不动（密钥文件未变，记录仍有效）。
     *
     * 全程只触碰 Uri / 显示名（非密钥元数据）；任何失败都不影响已成功的改密回执。
     */
    private suspend fun syncRememberedKeyFile(intent: ChangeKeyFileIntent) {
        val access = keyFileAccess ?: return
        val dbId = activeDbId()
        val rememberEnabled = access.isRememberEnabled()
        when (intent) {
            is ChangeKeyFileIntent.Use -> {
                // §411 走查（用户回执①）：副本收编与记忆偏好解耦——改绑成功即收编新文件
                // 字节进私有目录（不依赖持久授权），偏好关闭只停用 Uri 记忆、不影响副本
                if (dbId != null) vaultCopyStore?.save(dbId, intent.bytes, intent.displayName)
                if (!rememberEnabled) {
                    access.forget()
                    return
                }
                if (intent.sourceUri.isNotBlank() && access.persistReadPermission(intent.sourceUri)) {
                    access.remember(intent.sourceUri, intent.displayName)
                } else {
                    access.forget()
                }
            }
            ChangeKeyFileIntent.Remove -> {
                access.forget()
                if (dbId != null) vaultCopyStore?.clear(dbId)
            }
            ChangeKeyFileIntent.Keep -> Unit
        }
    }

    /**
     * §411（ISSUE-P3-448）：设置页「导入密钥文件」行的新语义入口——**收编**。
     *
     * 把解锁时所选（记忆）的密钥文件导入应用私有目录，不再要求用户重复手选：
     * 1. 副本已存在 → 视为已收编，直接成功回执（幂等）；
     * 2. 记忆 Uri 存在 → 经 [KeyFileAccess.read] 现读并收编（授权失效则显式失败回执）；
     * 3. 无记忆亦无副本 → 返回 false，调用方回落原「SAF 手选 → 改绑」流程
     *    （该库尚无第二因子，收编无从谈起；改绑成功后经 [syncRememberedKeyFile] 收编）。
     *
     * @return true = 已按新语义处理（成败均有回执）；false = 无记忆，回落手选流程。
     */
    suspend fun importRememberedCopy(): Boolean {
        val access = keyFileAccess ?: return false
        val dbId = activeDbId() ?: return false
        // 1. 副本已存在（幂等收编）
        val existing = vaultCopyStore?.load(dbId)
        if (existing != null) {
            existing.bytes.fill(0)
            mutableState.update {
                it.copy(feedback = UiMessage(R.string.dbset_keyfile_import_memory_done))
            }
            return true
        }
        // 2. 记忆 Uri 现读收编
        val remembered = access.loadRemembered()
        if (remembered != null) {
            when (val outcome = access.read(remembered.uri)) {
                is com.keepasskey.app.ui.screens.unlock.KeyFileReadResult.Success -> {
                    val saved = vaultCopyStore?.save(dbId, outcome.bytes, outcome.displayName)
                    outcome.bytes.fill(0)
                    mutableState.update {
                        it.copy(
                            feedback = if (saved == true) {
                                UiMessage(R.string.dbset_keyfile_import_memory_done)
                            } else {
                                UiMessage(R.string.unlock_keyfile_read_failed)
                            }
                        )
                    }
                }
                else -> {
                    // §411 走查（用户回执①）：授权失效 / 读取失败 → 清陈旧记录并**回落手选**
                    // （返回 false 让调用方直接弹 SAF 选择器，重选成功即经改绑流程收编副本），
                    // 不再死报「无法读取」让用户无路可走
                    access.forget()
                    mutableState.update {
                        it.copy(feedback = UiMessage(R.string.dbset_keyfile_import_memory_stale))
                    }
                    return false
                }
            }
            return true
        }
        // 3. 无记忆：回落手选改绑流程
        return false
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
