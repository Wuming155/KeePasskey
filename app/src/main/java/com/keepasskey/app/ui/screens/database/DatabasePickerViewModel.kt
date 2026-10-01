package com.keepasskey.app.ui.screens.database

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateKeyFileFactor
import com.keepasskey.app.data.repository.CreateVaultPreset
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultRemovalKind
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult
import com.keepasskey.core.result.KdbxResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DatabasePickerEvent {
    data class DatabaseSelected(val id: String) : DatabasePickerEvent
}

@HiltViewModel
class DatabasePickerViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    // ISSUE-P3-21：既有密钥文件字节读取通道（SAF，复用解锁特性已建立的 KeyFileAccess 契约）。
    // nullable 仅为单测注入内存假实现；生产 DI 经 KeyFileAccessModule 恒注入 SafKeyFileAccess
    private val keyFileAccess: KeyFileAccess? = null,
    // ISSUE-P3-21：生成型密钥文件的 SAF 落盘所需上下文。nullable 仅为单测构造；
    // 生产 DI 注入 @ApplicationContext（与 SettingsViewModel 同一既有范式）
    @ApplicationContext private val appContext: Context? = null,
    // ISSUE-P2-288：弱主口令「显式二次确认」留痕通道。nullable 仅为单测构造；生产 DI 恒注入
    private val debugLog: com.keepasskey.app.data.logger.DebugLogBuffer? = null,
    // ISSUE-P2-399：云端打开导入器（下载远端库 → 落凭据）。nullable 仅为单测构造；生产 DI 恒注入
    private val cloudVaultImporter: com.keepasskey.app.sync.CloudVaultImporter? = null,
    // ISSUE-P3-400：远端目录浏览控制器（打开对话框「浏览远端目录」共用设置页同一单例）。
    // nullable 仅为单测构造；生产 DI 恒注入
    private val remoteBrowseController: com.keepasskey.app.sync.RemoteBrowseController? = null,
    // ISSUE-P2-424 AC③ / ISSUE-P3-425：云同步凭据仓库（已配置云账号的预填读取与云端直建判定）。
    // nullable 仅为单测构造；生产 DI 恒注入
    private val syncCredentialsStore: com.keepasskey.app.sync.SyncCredentialsStore? = null,
    // 快照/预填读取的调度器（Keystore 解封为阻塞操作，限定符见 di/PickerIoDispatcher.kt）；
    // 单测注入 Unconfined 以获得确定性推进
    @com.keepasskey.app.di.PickerIoDispatcher
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    /** ISSUE-P2-288 AC②：弱主口令「显式二次确认」的留痕（不落明文） */
    fun noteWeakMasterPasswordConfirmed() {
        debugLog?.warn("MasterPasswordPolicy", "用户显式确认使用低于强度门槛的主密码（建库）")
    }
    // ISSUE-P3-359 AC④：放宽 internal 供同包 publishPickerMessage 双写
    internal val userMessageFlow = MutableStateFlow<UiMessage?>(null)
    private val showCreateDialogFlow = MutableStateFlow(false)
    private val showOpenSourceDialogFlow = MutableStateFlow(false)
    /** ISSUE-P2-354 AC①：建库进行中（原 UiState.isLoading 死字段的真相源；busy 守卫读它） */
    private val isCreatingFlow = MutableStateFlow(false)

    // ISSUE-P3-21：生成型密钥文件的一次性交付（状态机自本 VM 拆出，§391 行数分档闸门）
    private val keyFileDeliveryController = DatabasePickerKeyFileDeliveryController(
        vaultRepository = vaultRepository,
        appContext = appContext,
        scope = viewModelScope,
        publish = { publishPickerMessage(it) }
    )
    val keyFileDelivery: StateFlow<KeyFileDeliveryState>
        get() = keyFileDeliveryController.delivery

    private val _events = MutableSharedFlow<DatabasePickerEvent>()
    val events: SharedFlow<DatabasePickerEvent> = _events.asSharedFlow()

    // ISSUE-P3-400：远端目录浏览的透传层（控制器单例仍与设置页共用，§391 拆出）
    private val remoteBrowse = DatabasePickerRemoteBrowseController(
        browseController = remoteBrowseController,
        scope = viewModelScope
    )

    // ISSUE-P2-399 / ISSUE-P2-424：「从来源打开已有库」的登记流程（§391 拆出，行为逐字不变）
    private val openVaultImporter = DatabasePickerOpenVaultImporter(
        vaultRepository = vaultRepository,
        cloudVaultImporter = cloudVaultImporter,
        publish = { publishPickerMessage(it) },
        onOpened = { fileName ->
            showOpenSourceDialogFlow.value = false
            publishPickerMessage(UiMessage(R.string.db_picker_msg_opened))
            _events.emit(DatabasePickerEvent.DatabaseSelected(fileName))
        }
    )

    // ISSUE-P2-424 AC③ / ISSUE-P3-425：已配置云账号的两处消费面（可用性快照 + 打开对话框预填）
    // 自本 VM 拆出（§391 行数分档闸门）；凭据数组所有权口径见该类 KDoc
    private val cloudAccount = DatabasePickerCloudAccountController(
        store = syncCredentialsStore,
        appContext = appContext,
        ioDispatcher = ioDispatcher,
        scope = viewModelScope
    )

    /** 打开对话框的预填包（对话框组合后消费；未消费路径由关窗兜底擦除） */
    val openVaultPrefill: StateFlow<OpenVaultPrefill?> = cloudAccount.prefill

    init {
        cloudAccount.refreshSnapshot()
    }

    val uiState: StateFlow<DatabasePickerUiState> = combine(
        vaultRepository.getDatabases(),
        userMessageFlow,
        combine(showCreateDialogFlow, showOpenSourceDialogFlow) { c, o -> Pair(c, o) },
        isCreatingFlow,
        cloudAccount.cloudSnapshot
    ) { databases, userMessage, (showCreateDialog, showOpenSourceDialog), isLoading, cloudSnapshot ->
        DatabasePickerUiState(
            databases = databases,
            isLoading = isLoading,
            userMessage = userMessage,
            showCreateDialog = showCreateDialog,
            showOpenSourceDialog = showOpenSourceDialog,
            cloudSnapshot = cloudSnapshot
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DatabasePickerUiState()
    )

    fun selectDatabase(id: String) {
        viewModelScope.launch {
            vaultRepository.selectDatabase(id)
            _events.emit(DatabasePickerEvent.DatabaseSelected(id))
        }
    }

    fun openCreateDialog() {
        showCreateDialogFlow.value = true
    }

    fun closeCreateDialog() {
        showCreateDialogFlow.value = false
    }

    fun openOpenSourceDialog() {
        showOpenSourceDialogFlow.value = true
        // ISSUE-P2-424 AC③：打开即装载预填包（上次来源 + 已配置云账号），Keystore 解封落 IO
        cloudAccount.startPrefillLoad()
    }

    fun closeOpenSourceDialog() {
        showOpenSourceDialogFlow.value = false
        // 取消在途装载 + 擦除未消费的预填凭据（所有权口径见 DatabasePickerCloudAccountController）
        cloudAccount.cancelAndWipe()
    }

    /**
     * ISSUE-P2-424 AC①：路由 `openImport=true` 的自动展开入口。配置变更（旋转）会重建组合并
     * 重放 `LaunchedEffect`，本守卫保证每个 ViewModel 实例只消费一次路由意图（VM 经
     * activity-retained 作用域跨配置变更存活），关窗后不因重建而反复弹出。
     */
    fun openImportFromRoute() {
        if (routeAutoImportConsumed) return
        routeAutoImportConsumed = true
        openOpenSourceDialog()
    }

    private var routeAutoImportConsumed = false

    /** 对话框已把预填包写入表单快照态（接管数组擦除责任）后回调：ViewModel 侧弃持 */
    fun consumeOpenVaultPrefill() {
        cloudAccount.consumePrefill()
    }

    /** 对话框来源 Chip 被用户切换：留痕为下次打开的预选来源（AC③「预选记忆」） */
    fun noteOpenVaultSource(source: OpenVaultSourceType) {
        cloudAccount.noteSource(source)
    }

    /**
     * 创建新密码库（ISSUE-P3-21：复合密钥第二因子真实接线，对齐官方 `CompositeKey` 三分支）。
     *
     * 因子语义：[keyFile] = false → 仅主密码；= true 且 [keyFileSourceUri] 为空 → 主密码 +
     * **生成型**密钥文件（成功后必须经一次性提示交付用户）；= true 且 uri 非空 → 主密码 +
     * **用户选定的既有**密钥文件——读取失败（空文件 / 超限 / 提供方拒绝）一律**显式失败且
     * 不创建库**，绝不静默降级（否则用户以为在用旧密钥文件、实际因子已被替换）。
     * 主密码副本在 `finally` 擦除；ISSUE-P2-85：[preset] 为类型化加密预设，不经字符串标签中转。
     */
    fun createDatabase(
        name: String,
        masterPassword: CharArray,
        keyFile: Boolean,
        preset: CreateVaultPreset,
        keyFileSourceUri: String? = null,
        /** ISSUE-P2-229：非空即建到用户经系统文件选择器自选的位置（`content://` uri 字符串） */
        targetUri: String? = null,
        /**
         * ISSUE-P3-425：存储位置意图。「云端」= 本地建库（filesDir）+ 以云 syncType 登记
         * （远端上传交由既有同步周期，绑定不符保护照常生效）。
         */
        storageLocation: VaultStorageLocation = VaultStorageLocation.INTERNAL
    ) {
        // ISSUE-P2-354 AC①：busy 守卫——Argon2 派生是秒级操作，守卫在**协程之外同步置位**，
        // 快速连点的第二次调用直接被拒（对话框按钮的 enabled 只是 UI 层，挡不住重帧内的双击）。
        // 入参为借用语义（调用方持有并自行擦除），被拒路径不接管、不擦除。
        if (isCreatingFlow.value) return
        // ISSUE-P3-425 fail-closed：云端直建要求已配置云账号——不满足时显式失败，
        // 绝不静默降级成「普通本地库」（用户以为建到了云端，实际没有）
        if (storageLocation == VaultStorageLocation.CLOUD && !cloudAccount.cloudReady) {
            publishPickerMessage(UiMessage(R.string.db_picker_cloud_create_unconfigured, isError = true))
            return
        }
        isCreatingFlow.value = true
        viewModelScope.launch {
            // H2 整改：主密码全程 CharArray——复制私有副本并在 finally 擦除；
            // 入参数组为调用方（弹窗）所有，由其自身生命周期管理擦除
            val pwd = masterPassword.copyOf()
            try {
                val resolution = resolveKeyFileFactor(keyFile, keyFileSourceUri)
                if (resolution is KeyFileFactorResolution.Failed) {
                    publishPickerMessage(resolution.message)
                    return@launch
                }
                val resolved = resolution as KeyFileFactorResolution.Resolved
                // H3 整改：创建失败（写盘失败等）不再谎报创建成功
                val result = try {
                    vaultRepository.createDatabaseWithKeyFile(name, pwd, resolved.factor, preset, targetUri)
                } finally {
                    // 借用语义的调用方责任：数据层已克隆持有自己的副本，此处副本用毕即擦
                    resolved.borrowedKeyFileBytes?.fill(0)
                }
                if (result is KdbxResult.Success) {
                    val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
                    // ISSUE-P3-425：云端直建 = 以云 syncType 幂等重登记同一本地文件
                    // （与「云端打开」登记形态一致：本地文件是落地细节，卡片只显示云端库）；
                    // 登记 id 变为绝对路径，但活动库切换由登记内部完成，选中事件不受影响
                    val cloudRegisterFailed = storageLocation == VaultStorageLocation.CLOUD &&
                        !registerCloudCreatedVault(fileName)
                    showCreateDialogFlow.value = false
                    publishPickerMessage(
                        UiMessage(
                            if (cloudRegisterFailed) R.string.db_picker_msg_cloud_register_failed
                            else R.string.db_picker_msg_created,
                            isError = cloudRegisterFailed
                        )
                    )
                    if (resolved.generated) {
                        // ISSUE-P3-21 验收 2：生成型密钥文件必须一次性交付（丢失即无法解锁）
                        keyFileDeliveryController.requestSave(name)
                    }
                    // ISSUE-P2-229：自选位置库在目录中的标识就是 uri 字符串（`importExternalDatabase` 同口径），
                    // 以文件名下行的选中事件对这类库无效
                    _events.emit(DatabasePickerEvent.DatabaseSelected(targetUri ?: fileName))
                } else {
                    publishPickerMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
                }
            } finally {
                pwd.fill('0')
                // ISSUE-P2-354 AC①：busy 在任何结果路径（含失败/提前返回）都回落
                isCreatingFlow.value = false
            }
        }
    }

    /**
     * 云端直建的第二步（ISSUE-P3-425）：把刚建好的 filesDir 库文件按**云 syncType** 幂等重登记
     * （复用 `importExternalDatabase` 单一登记出口，同一 path 覆盖条目并置活动库——与
     * `CloudVaultImporter` 下载后登记的形态完全一致；目录扫描对同路径条目让位，不出双卡）。
     * 远端文件的真实上传交由既有同步周期执行，`SyncVaultBindingStore` 的绑定不符保护照常生效。
     */
    private suspend fun registerCloudCreatedVault(fileName: String): Boolean {
        val dir = appContext?.filesDir ?: return false
        val path = java.io.File(dir, fileName).absolutePath
        val syncType = cloudAccount.cloudSyncTypeLabel ?: return false
        return vaultRepository.importExternalDatabase(fileName, path, syncType = syncType) is KdbxResult.Success
    }

    /**
     * 把生成型密钥文件写入用户选定的 SAF 目标（ISSUE-P3-21 验收 2）。
     * 实现见 [DatabasePickerKeyFileDeliveryController.saveTo]（§391 拆出，行为逐字不变）。
     */
    fun saveGeneratedKeyFileTo(targetUri: Uri) = keyFileDeliveryController.saveTo(targetUri)

    /** 用户显式选择「暂不保存密钥文件」：关闭一次性提示（不清会话缓存，可经设置页导出补存） */
    fun dismissKeyFileDelivery() = keyFileDeliveryController.dismiss()

    // ISSUE-P3-400：对话框「浏览远端目录」复用设置页同一控制器单例（表单凭据优先，无已保存回退）；
    // 实现见 DatabasePickerRemoteBrowseController（§391 拆出，行为逐字不变）

    /** 浏览对话框状态（未注入控制器时恒 Idle，仅供单测构造路径） */
    val remoteBrowseState: StateFlow<com.keepasskey.app.sync.RemoteBrowseUiState>
        get() = remoteBrowse.browseState

    /** 浏览 WebDAV 目录：[password] 为借用语义副本（UI 传 copyOf），协程结束即擦 */
    fun browseRemoteWebDav(url: String, username: String, password: CharArray, directoryPath: String, cursor: String?) {
        remoteBrowse.browseWebDav(url, username, password, directoryPath, cursor)
    }

    /** 浏览 S3 目录：AK/SK 借用语义同上 */
    fun browseRemoteS3(
        endpoint: String,
        bucket: String,
        region: String,
        accessKey: CharArray,
        secretKey: CharArray,
        directoryPath: String,
        usePathStyle: Boolean,
        cursor: String?
    ) {
        remoteBrowse.browseS3(endpoint, bucket, region, accessKey, secretKey, directoryPath, usePathStyle, cursor)
    }

    /** 关闭浏览对话框并复位浏览状态 */
    fun dismissRemoteBrowse() = remoteBrowse.dismiss()

    /** 导入并登记外部来源的密码库（见 [OpenVaultSourceType] / [OpenVaultSubmission]）。
     *
     * ISSUE-P2-399：云端来源不再是「只登记不连接」的假桩——先经 `CloudVaultImporter` 用所填
     * 凭据把远端库下载到本地（下载成功才落凭据），再按**本地文件路径**走既有
     * [DatabasePickerOpenVaultImporter.importLocal] 登记出口，与「本地打开 → 同步配置补全」手工链路完全同构。
     *
     * ISSUE-P2-87 / ISSUE-P3-248（如实口径）：本方法成功后**不在本页**做工作因子提示，且
     * 没有任何弱因子提示通道——本页会随 [DatabasePickerEvent.DatabaseSelected] 立即被
     * `popBackStack()` 退栈（Snackbar 来不及渲染），而「交给落点代为提示」并不成立：
     * 选择器路径只走本文件 [importLocalDatabase]，从不写 `UnlockUiState.infoMessage`。
     * 该残余已登记 `docs/architecture/已知工程限界.md` §8（不是修复，是留痕）。
     */
    fun importDatabaseFromSource(submission: OpenVaultSubmission) {
        viewModelScope.launch {
            when (submission) {
                is OpenVaultSubmission.Local -> openVaultImporter.importLocal(submission)
                is OpenVaultSubmission.Cloud -> openVaultImporter.importCloud(submission.request)
            }
        }
    }

    /**
     * 移除密码库。
     *
     * `ISSUE-P1-241`：[kind] 是该动作的**真实对象**（由界面按 `VaultRemovalKind.of(条目 path,
     * filesDir)` 判定，与确认弹窗所用文案同一枚判据）——应用私有库移除即**删除其物理文件**，
     * 外部库只摘除本机登记。此前该参数不存在，数据层只能按 `id` 形状反推，而「外部登记的 id
     * 恰是裸文件名」时会误删应用私有库的同名文件（文案却承诺不删）。
     * **不得**在调用侧以外的地方另行判定，也不得把该参数默认成「会删」。
     */
    fun removeDatabase(id: String, kind: VaultRemovalKind) {
        viewModelScope.launch {
            val result = vaultRepository.removeDatabase(id, kind)
            if (result is KdbxResult.Success) {
                publishPickerMessage(UiMessage(R.string.db_picker_msg_removed))
            } else {
                publishPickerMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    /**
     * 解析建库密钥文件因子（无密钥文件 / 生成型 / 用户选定既有文件）。
     * ISSUE-P2-399 批次：自本类下沉至 `DatabaseKeyFileFactorResolver`（纯结构性搬移，
     * 语义与失败分支零变化），本类只保留调用点。
     */
    private suspend fun resolveKeyFileFactor(keyFile: Boolean, sourceUri: String?): KeyFileFactorResolution =
        DatabaseKeyFileFactorResolver.resolve(keyFile, sourceUri, keyFileAccess)
}
