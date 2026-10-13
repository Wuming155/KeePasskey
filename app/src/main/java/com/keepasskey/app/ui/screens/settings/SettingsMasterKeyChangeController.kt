package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.result.KdbxError
import com.keepasskey.database.session.CredentialRotationCancellationException
import com.keepasskey.database.session.SessionWriteReceipt
import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * - **作用域**：生产任务由应用单例 [CredentialRotationRuntime] 持有，页面只订阅状态。
 *   页面与 ViewModel 销毁均不会取消已受理的写入；[scope] 仅供纯 JVM 测试注入。
 * - **忙守卫**：[MasterKeyChangeTaskState.isChanging] 为 true 时并发第二次**直接拒绝**，
 *   且入参数组照样清零——受理时复制密码与因子并即时擦除调用方输入；任务只读自己的副本，忙 / 闲、成败、异常路径
 *   都由本类的 `finally` 负责擦除；
 * - **回执**：结果经 [state] 的 `feedback` 下发（`SettingsContent` 既有 Snackbar 路径展示后
 *   由 `clearFeedback` 清除），对话框据 busy 回落自行关闭。
 *
 * ISSUE-P2-398：**改密成功后**追加生物识别封印凭据重封印（[resealAfterChange]，可空 =
 * 未装配 / 单测），同任务内串行执行——此时新密码仍在任务私有副本中存活（`finally` 才清零），
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
/**
 * `ISSUE-P3-528`：`SettingsViewModel` 侧的**唯一装配点**（自 VM 下沉，使该文件回到行数档沿之下）。
 *
 * 各参数语义与 [SettingsMasterKeyChangeController] 的 ctor KDoc 逐项一致，此处只记装配方三条口径：
 * - [strings]：`ISSUE-P3-453` 的「错误码 → 本地化文案」通道，命名置末位以不打断既有位置传参序列；
 * - [residualBackupProbe]：`ISSUE-P2-520` 的换密回执 `.bak` 残余读数（真会话 stat 挂 IO；可空会话短路）；
 * - [onCredentialsRotated]：`ISSUE-P3-528` 的「云端副本待替换」登记（生产 = `SyncCoordinator.markLocalVaultRecrypted`）。
 */
internal fun masterKeyChangeControllerOf(
    repository: VaultRepository,
    scope: CoroutineScope,
    resealAfterChange: suspend (FragmentActivity?, CharArray?) -> Unit,
    keyFileAccess: KeyFileAccess?,
    vaultCopyStore: com.keepasskey.app.security.KeyFileVaultCopyStore?,
    strings: StringsProvider,
    residualBackupProbe: suspend () -> Boolean,
    onCredentialsRotated: suspend () -> Unit,
    runtime: CredentialRotationRuntime? = null,
    completion: CredentialRotationCompletion? = null
): SettingsMasterKeyChangeController = SettingsMasterKeyChangeController(
    repository = repository,
    scope = scope,
    resealAfterChange = resealAfterChange,
    keyFileAccess = keyFileAccess,
    vaultCopyStore = vaultCopyStore,
    strings = strings,
    residualBackupProbe = residualBackupProbe,
    onCredentialsRotated = onCredentialsRotated,
    runtime = runtime,
    completion = completion?.let { owner -> owner::complete },
    captureSyncTarget = completion?.let { owner -> owner::captureTarget },
    onUncertainWrite = completion?.let { owner -> owner::invalidate }
)

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
    /**
     * ISSUE-P2-520：活动库是否绑定云端同步——回执的「云端旧版本」说明仅对同步库呈现
     * （本地库无云端副本，该说明是噪音）。默认＝活动库登记 `isRemote`（与 [activeDbId] 同源同查）。
     */
    private val syncBound: suspend () -> Boolean = {
        repository.getDatabases().first().firstOrNull { it.isActive }?.isRemote == true
    },
    /**
     * ISSUE-P2-520：换密成功后活动库 `.bak` 残余探测（「删除失败 → 有可见信号」的读数侧）。
     * null = 未装配（单测旁路），说明缺席——与 [resealAfterChange] 等可选挂点同一口径。
     * 生产由 `SettingsViewModel` 以真会话装配（文件 stat 挂 IO 调度器；null 会话短路不产生
     * 真实调度器跳跃，runTest 下默认装配也安全）；读数语义 =
     * `AtomicFileWriter.backupFileFor(activeFile).exists()`。
     */
    private val residualBackupProbe: (suspend () -> Boolean)? = null,
    // ISSUE-P3-453：错误码 → 本地化文案通道（置于末位以免打乱既有位置传参；生产由宿主显式注入）
    private val strings: StringsProvider = StringsProvider { _, _ -> "" },
    /**
     * `ISSUE-P3-528`：主凭据变更**成功后**的登记回调（生产 = `SyncCoordinator.markLocalVaultRecrypted`，
     * 由 `SettingsViewModel` 装配；null = 未装配，机制整体旁路）。
     *
     * 调用时机：写盘成功之后、重封印**之前**——重封印可能要等用户过生物识别（甚至被取消），
     * 而「本库已用新凭据重新加密、云端副本待替换」这一事实在写盘成功时就已成立。
     * 契约：实现自身不得抛出（失败以返回值表达）；换密已成功这一结果不因登记失败而改变。
     */
    private val onCredentialsRotated: (suspend () -> Unit)? = null,
    private val runtime: CredentialRotationRuntime? = null,
    private val completion: (suspend (String?, FragmentActivity?, CharArray?, String?) -> Unit)? = null,
    private val captureSyncTarget: (suspend () -> String?)? = null,
    private val onUncertainWrite: ((String?) -> Unit)? = null
) {

    private val mutableState = runtime?.state ?: MutableStateFlow(MasterKeyChangeTaskState())

    /** busy + 结果反馈（投影层订阅它并入 [SettingsUiState]）。 */
    val state: StateFlow<MasterKeyChangeTaskState> = mutableState.asStateFlow()

    /**
     * 提交凭据变更；复制输入后即时擦除调用方数组，任务完成后擦除自有副本。
     *
     * ISSUE-P3-428：[keyFileIntent] 透传对密钥文件第二因子的意图（默认 Keep 沿用
     * 既有语义）；`Use` 携带的字节同为借用语义——仓库侧只读不擦除，**清零责任在本
     * 任务完成钩子与 `finally`**（与密码数组同一收尾窗口），成败与异常路径均不遗留。
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
        val previous = mutableState.value
        if ((keepPassword && keyFileIntent == ChangeKeyFileIntent.Keep) || previous.isChanging ||
            !mutableState.compareAndSet(previous, previous.copy(isChanging = true))
        ) {
            newPasswordChars.fill('0')
            keyFileIntent.eraseBorrowedBytes()
            return
        }
        val input = try {
            CredentialRotationInput.copyAndEraseCaller(newPasswordChars, keyFileIntent)
        } catch (error: Throwable) { // cancel-n/a: 保护段仅复制数组，无挂起点
            mutableState.update { it.copy(isChanging = false) }
            throw error
        }
        val job = (runtime?.scope ?: scope).launch {
            try {
                val action: suspend () -> Unit = { performRotation(input, activity) }
                if (runtime != null) runtime.run(action) else action()
            } finally {
                input.close()
            }
        }
        // 忙态仅在完成钩子释放：finally 提前释放会让下一任务受理后又被旧钩子覆盖成空闲。
        // DEFAULT launch 在已取消作用域中可能一次都未进入体；完成钩子仍负责擦除与忙态回落。
        job.invokeOnCompletion {
            input.close()
            mutableState.update { it.copy(isChanging = false) }
        }
    }

    private suspend fun performRotation(input: CredentialRotationInput, activity: FragmentActivity?) {
        val databaseId = activeDbId()
        val syncTarget = captureSyncTarget?.invoke()
        val password = input.password.takeIf { it.isNotEmpty() }
        val result = try {
            if (password == null) repository.changeKeyFileOnly(input.keyFile)
            else repository.changeMasterPassword(password, input.keyFile)
        } catch (error: CredentialRotationCancellationException) {
            if (error.writeStatus == SessionWriteReceipt.Status.COMMITTED) {
                finishCommitted(input.keyFile, databaseId, null, password, syncTarget)
                withContext(NonCancellable) { publishFeedback(KdbxResult.Success(Unit)) }
            } else {
                onUncertainWrite?.invoke(databaseId)
                publishFeedback(KdbxResult.Failure(error, code = KdbxError.CREDENTIALS_WRITE_UNCERTAIN))
            }
            throw error
        }
        if (result is KdbxResult.Success) {
            finishCommitted(input.keyFile, databaseId, activity, password, syncTarget)
        } else if (result is KdbxResult.Failure && result.code == KdbxError.CREDENTIALS_WRITE_UNCERTAIN) {
            onUncertainWrite?.invoke(databaseId)
        }
        publishFeedback(result)
    }

    private suspend fun finishCommitted(
        intent: ChangeKeyFileIntent,
        databaseId: String?,
        activity: FragmentActivity?,
        password: CharArray?,
        syncTarget: String?
    ) {
        try {
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000L) { syncRememberedKeyFile(intent, databaseId) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AppLog.w("CredentialRotation", "记忆密钥文件维护失败", error)
        } finally {
            val owner = completion
            if (owner != null) owner(databaseId, activity, password, syncTarget)
            else {
                try {
                    onCredentialsRotated?.invoke()
                } finally {
                    resealAfterChange?.invoke(activity, password)
                }
            }
        }
    }

    private suspend fun publishFeedback(result: KdbxResult<Unit>) {
        val feedback = when (result) {
            is KdbxResult.Success -> {
                val cloudNote = if (syncBound()) strings.get(R.string.set_master_key_cloud_old_version_note) else ""
                val backupNote = if (residualBackupProbe?.invoke() == true) {
                    strings.get(R.string.set_master_key_backup_cleanup_failed_note)
                } else ""
                UiMessage(R.string.set_master_key_updated, listOf(cloudNote, backupNote))
            }
            is KdbxResult.Failure -> UiMessage(R.string.op_failed, listOf(result.textArg(strings)))
        }
        mutableState.update { it.copy(feedback = feedback) }
    }

    /**
     * 改绑 / 解绑成功后同步「记住的密钥文件位置」（ISSUE-P3-434；ISSUE-P2-460 起按库归属）。
     *
     * 与解锁页 `rememberKeyFileOnSuccess` 同口径（ISSUE-P3-04 + ISSUE-P2-460 AC①）：
     * - 偏好关闭 → 清除记忆（本库记录 + 旧版全局槽，不留任何密钥文件元数据）；
     * - `Use`：偏好开启 + 来源 Uri 非空 + 对其取得**持久化读授权** → 登记新来源到**本库名下**
     *   （dbId 摘要键，绝不落全局槽）；任一不满足 → 清除旧记录（旧记录指向已解绑的文件，
     *   留存必然误导下次冷启动）；
     * - `Remove` → 清除记忆（改后库无第二因子）；
     * - `Keep` → 不动（密钥文件未变，记录仍有效）。
     *
     * 全程只触碰 Uri / 显示名（非密钥元数据）；任何失败都不影响已成功的改密回执。
     */
    private suspend fun syncRememberedKeyFile(intent: ChangeKeyFileIntent, dbId: String?) {
        val access = keyFileAccess ?: return
        val rememberEnabled = access.isRememberEnabled()
        when (intent) {
            is ChangeKeyFileIntent.Use -> {
                // §411 走查（用户回执①）：副本收编与记忆偏好解耦——改绑成功即收编新文件
                // 字节进私有目录（不依赖持久授权），偏好关闭只停用 Uri 记忆、不影响副本
                if (dbId != null) vaultCopyStore?.save(dbId, intent.bytes, intent.displayName)
                if (!rememberEnabled) {
                    access.forget(dbId)
                    return
                }
                if (intent.sourceUri.isNotBlank() && dbId != null &&
                    access.persistReadPermission(intent.sourceUri)
                ) {
                    access.remember(dbId, intent.sourceUri, intent.displayName)
                } else {
                    access.forget(dbId)
                }
            }
            ChangeKeyFileIntent.Remove -> {
                access.forget(dbId)
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
        // 2. 本库记忆 Uri 现读收编（ISSUE-P2-460：记忆按库归属，读的是本库名下的记录）
        val remembered = access.loadRemembered(dbId)
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
                    access.forget(dbId)
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
