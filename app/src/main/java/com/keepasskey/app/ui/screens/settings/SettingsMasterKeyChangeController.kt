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
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.withCredentialRequest
import com.keepasskey.database.session.SessionCredentialRequest
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
 * 更换主密钥执行器（ISSUE-P2-354 AC③）。
 * 生产任务由应用单例 [CredentialRotationRuntime] 持有，不随页面取消；[scope] 仅供 JVM 测试注入。
 * 受理时复制密码与因子并即时擦除调用方数组，自有副本由 finally 与完成钩子清零。
 * busy 守卫拒绝并发提交，结果由 [state] 投影为既有反馈。
 * 空密码只允许改绑/解绑密钥文件；成功后按受理归属维护记忆、副本、同步登记和生物封印。
 * 可空挂点仅为旧测试 fixture 兼容；产品入口经唯一装配点接入运行时。
 * ISSUE-P2-354 / ISSUE-P2-398 / ISSUE-P3-434 / ISSUE-P3-448（§411）承载对应流程约束。
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
    /** 活动库登记 id（副本键）；未装配时仅测试旁路。 */
    private val activeDbId: suspend () -> String? = {
        repository.getDatabases().first().firstOrNull { it.isActive }?.id
    },
    /** ISSUE-P2-520：活动库是否绑定同步；默认读库目录的 `isRemote`。 */
    private val syncBound: suspend () -> Boolean = {
        repository.getDatabases().first().firstOrNull { it.isActive }?.isRemote == true
    },
    /** ISSUE-P2-520：成功回执的 `.bak` 残留探测；null 时测试旁路，生产由 SettingsViewModel 装配。 */
    private val residualBackupProbe: (suspend () -> Boolean)? = null,
    // ISSUE-P3-453：错误码 → 本地化文案通道（置于末位以免打乱既有位置传参；生产由宿主显式注入）
    private val strings: StringsProvider = StringsProvider { _, _ -> "" },
    /** ISSUE-P3-528：成功后登记云端待替换事实；失败不改写本地换密结果。 */
    private val onCredentialsRotated: (suspend () -> Unit)? = null,
    private val runtime: CredentialRotationRuntime? = null,
    private val completion: (suspend (String?, FragmentActivity?, CharArray?, String?) -> Unit)? = null,
    private val captureSyncTarget: (() -> String?)? = null,
    private val onUncertainWrite: ((String?) -> Unit)? = null,
    private val credentialSession: DatabaseSession? = null,
    private val completionWithRequest: (suspend (SessionCredentialRequest, String?, FragmentActivity?, CharArray?, String?) -> Boolean)? = null
) {

    private val mutableState = runtime?.state ?: MutableStateFlow(MasterKeyChangeTaskState())

    /** busy + 结果反馈（投影层订阅它并入 [SettingsUiState]）。 */
    val state: StateFlow<MasterKeyChangeTaskState> = mutableState.asStateFlow()

    /** 提交后先克隆并擦调用方密码/因子；私有副本在事务结束与完成钩子中清零。空密码仅走密钥文件改绑。 */
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
        var ownedInput: CredentialRotationInput? = null
        val admissionAndInput = try {
            val input = CredentialRotationInput.copyAndEraseCaller(newPasswordChars, keyFileIntent)
            ownedInput = input
            val request = credentialSession?.captureCredentialRequest()
            val ticket = request?.ticket
            val info = ticket?.let { repository.databaseInfoForPath(it.pathIdentifier) }
            if (ticket != null && info == null) {
                input.close()
                mutableState.update {
                    it.copy(
                        isChanging = false,
                        feedback = UiMessage(R.string.op_failed, listOf(strings.get(R.string.err_save_failed)), isError = true)
                    )
                }
                return
            }
            val admission = CredentialRotationAdmission(
                request = request,
                databaseId = info?.id,
                remoteBound = info?.isRemote ?: false,
                syncTarget = captureSyncTarget?.invoke()
            )
            input to admission
        } catch (error: Throwable) { // cancel-n/a: 保护段仅复制数组，无挂起点
            ownedInput?.close()
            newPasswordChars.fill('0')
            keyFileIntent.eraseBorrowedBytes()
            mutableState.update { it.copy(isChanging = false) }
            throw error
        }
        val (input, admission) = admissionAndInput
        val job = try {
            (runtime?.scope ?: scope).launch {
                try {
                    val action: suspend () -> Unit = { performRotation(input, activity, admission) }
                    if (runtime != null) runtime.run(action) else action()
                } finally {
                    input.close()
                }
            }
        } catch (error: Throwable) { // cancel-n/a: launch 调度前的同步受理失败
            input.close()
            mutableState.update { it.copy(isChanging = false) }
            throw error
        }
        // 忙态仅在完成钩子释放：finally 提前释放会让下一任务受理后又被旧钩子覆盖成空闲。
        // DEFAULT launch 在已取消作用域中可能一次都未进入体；完成钩子仍负责擦除与忙态回落。
        job.invokeOnCompletion {
            input.close()
            mutableState.update { it.copy(isChanging = false) }
        }
    }

    private suspend fun performRotation(
        input: CredentialRotationInput, activity: FragmentActivity?, admission: CredentialRotationAdmission
    ) {
        val request = admission.request
        val databaseId = admission.databaseId ?: if (request == null) activeDbId() else null
        val syncTarget = admission.syncTarget
        val password = input.password.takeIf { it.isNotEmpty() }
        val result = try {
            val rotate: suspend () -> KdbxResult<Unit> = {
                if (password == null) repository.changeKeyFileOnly(input.keyFile)
                else repository.changeMasterPassword(password, input.keyFile)
            }
            if (request != null) credentialSession!!.withCredentialRequest(request, rotate) else rotate()
        } catch (error: CancellationException) {
            val writeStatus = request?.writeStatus
                ?: (error as? CredentialRotationCancellationException)?.writeStatus
            when (writeStatus) {
                SessionWriteReceipt.Status.COMMITTED -> withContext(NonCancellable) {
                    val cloudMarked = finishCommitted(input.keyFile, databaseId, null, password, syncTarget, request)
                    publishFeedback(KdbxResult.Success(Unit), admission, cloudMarked)
                }
                SessionWriteReceipt.Status.UNKNOWN -> {
                    withContext(NonCancellable) {
                        invalidateUncertain(databaseId, request)
                        publishFeedback(KdbxResult.Failure(error, code = KdbxError.CREDENTIALS_WRITE_UNCERTAIN), admission)
                    }
                }
                else -> Unit
            }
            throw error
        }
        if (result is KdbxResult.Success) {
            val cloudMarked = finishCommitted(input.keyFile, databaseId, activity, password, syncTarget, request)
            publishFeedback(result, admission, cloudMarked)
            return
        } else if (result is KdbxResult.Failure && result.code == KdbxError.CREDENTIALS_WRITE_UNCERTAIN) {
            invalidateUncertain(databaseId, request)
        }
        publishFeedback(result, admission)
    }

    /** 不确定写只清除仍由该受理票据拥有的封印。 */
    private suspend fun invalidateUncertain(databaseId: String?, request: SessionCredentialRequest?) {
        if (request == null) {
            onUncertainWrite?.invoke(databaseId)
            return
        }
        val ticket = request.ticket ?: return
        withContext(NonCancellable) {
            withTimeoutOrNull(10_000L) {
                credentialSession?.useCredentialTicket(ticket) {
                    onUncertainWrite?.invoke(databaseId)
                }
            }
        }
    }

    private suspend fun finishCommitted(
        intent: ChangeKeyFileIntent,
        databaseId: String?,
        activity: FragmentActivity?,
        password: CharArray?,
        syncTarget: String?,
        request: SessionCredentialRequest?
    ): Boolean {
        var cloudMarked = true
        try {
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000L) {
                    val ticket = request?.committedTicket
                    if (request == null) syncRememberedKeyFile(intent, databaseId)
                    else if (ticket != null) credentialSession!!.useCredentialTicket(ticket) {
                        syncRememberedKeyFile(intent, databaseId)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            AppLog.w("CredentialRotation", "记忆密钥文件维护失败", error)
        } finally {
            val guardedOwner = completionWithRequest
            val owner = completion
            if (guardedOwner != null && request != null) cloudMarked = guardedOwner(request, databaseId, activity, password, syncTarget)
            else if (owner != null) owner(databaseId, activity, password, syncTarget)
            else {
                try {
                    onCredentialsRotated?.invoke()
                } finally {
                    resealAfterChange?.invoke(activity, password)
                }
            }
        }
        return cloudMarked
    }

    private suspend fun publishFeedback(
        result: KdbxResult<Unit>, admission: CredentialRotationAdmission, cloudMarked: Boolean = true
    ) {
        val feedback = when (result) {
            is KdbxResult.Success -> {
                val isRemote = admission.remoteBound || admission.request == null && syncBound()
                val cloudNote = when {
                    !isRemote -> ""
                    !cloudMarked -> strings.get(R.string.set_master_key_cloud_mark_failed_note)
                    else -> strings.get(R.string.set_master_key_cloud_old_version_note)
                }
                val mayProbeBackup = admission.request == null || admission.request.committedTicket?.let {
                    credentialSession?.isCredentialTicketCurrent(it)
                } == true
                val backupNote = if (mayProbeBackup && residualBackupProbe?.invoke() == true) {
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
