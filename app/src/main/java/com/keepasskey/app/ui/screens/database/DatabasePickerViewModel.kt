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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val remoteBrowseController: com.keepasskey.app.sync.RemoteBrowseController? = null
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

    /** ISSUE-P3-21：生成型密钥文件的一次性交付状态（Screen 据此弹出强制保存提示） */
    private val keyFileDeliveryFlow = MutableStateFlow<KeyFileDeliveryState>(KeyFileDeliveryState.None)
    val keyFileDelivery: StateFlow<KeyFileDeliveryState> = keyFileDeliveryFlow.asStateFlow()

    private val _events = MutableSharedFlow<DatabasePickerEvent>()
    val events: SharedFlow<DatabasePickerEvent> = _events.asSharedFlow()

    val uiState: StateFlow<DatabasePickerUiState> = combine(
        vaultRepository.getDatabases(),
        userMessageFlow,
        combine(showCreateDialogFlow, showOpenSourceDialogFlow) { c, o -> Pair(c, o) },
        isCreatingFlow
    ) { databases, userMessage, (showCreateDialog, showOpenSourceDialog), isLoading ->
        DatabasePickerUiState(
            databases = databases,
            isLoading = isLoading,
            userMessage = userMessage,
            showCreateDialog = showCreateDialog,
            showOpenSourceDialog = showOpenSourceDialog
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
    }

    fun closeOpenSourceDialog() {
        showOpenSourceDialogFlow.value = false
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
        targetUri: String? = null
    ) {
        // ISSUE-P2-354 AC①：busy 守卫——Argon2 派生是秒级操作，守卫在**协程之外同步置位**，
        // 快速连点的第二次调用直接被拒（对话框按钮的 enabled 只是 UI 层，挡不住重帧内的双击）。
        // 入参为借用语义（调用方持有并自行擦除），被拒路径不接管、不擦除。
        if (isCreatingFlow.value) return
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
                    showCreateDialogFlow.value = false
                    publishPickerMessage(UiMessage(R.string.db_picker_msg_created))
                    if (resolved.generated) {
                        // ISSUE-P3-21 验收 2：生成型密钥文件必须一次性交付（丢失即无法解锁）
                        keyFileDeliveryFlow.value = KeyFileDeliveryState.PendingSave(
                            suggestedFileName = suggestedKeyFileName(name)
                        )
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
     * 把生成型密钥文件写入用户选定的 SAF 目标（ISSUE-P3-21 验收 2）。
     * 复用既有导出通道 [VaultRepository.exportKeyFileBytes]：只做「取字节 → 写 SAF → 擦副本」，
     * 不新造第二条密钥文件生成/读取路径；写盘成功即关闭一次性提示。
     */
    fun saveGeneratedKeyFileTo(targetUri: Uri) {
        viewModelScope.launch {
            val resolver = appContext?.contentResolver
            if (resolver == null) {
                // 禁止静默失败：没有写盘上下文时如实告知用户，提示保持驻留
                publishPickerMessage(UiMessage(R.string.db_picker_keyfile_save_failed))
                return@launch
            }
            // 复用既有导出通道取字节：会话未绑定密钥文件时如实失败（绝不写空文件冒充成功）
            val bytes = vaultRepository.exportKeyFileBytes().getOrNull()
            if (bytes == null) {
                publishPickerMessage(UiMessage(R.string.db_picker_keyfile_save_failed))
                return@launch
            }
            val written = withContext(Dispatchers.IO) {
                try {
                    resolver.openOutputStream(targetUri)?.use { output ->
                        output.write(bytes)
                        output.flush()
                        true
                    } ?: false
                } catch (_: Exception) {
                    // 异常不外泄内容（可能是提供方拒绝/磁盘满），统一由下方语义化提示承接
                    false
                } finally {
                    // 密钥文件字节副本用毕即擦（会话内仍持有自己的副本供后续导出）
                    bytes.fill(0)
                }
            }
            if (written) {
                keyFileDeliveryFlow.value = KeyFileDeliveryState.None
                publishPickerMessage(UiMessage(R.string.db_picker_keyfile_saved))
            } else {
                publishPickerMessage(UiMessage(R.string.db_picker_keyfile_save_failed))
            }
        }
    }

    /** 用户显式选择「暂不保存密钥文件」：关闭一次性提示（不清会话缓存，可经设置页导出补存） */
    fun dismissKeyFileDelivery() {
        keyFileDeliveryFlow.value = KeyFileDeliveryState.None
    }

    // ISSUE-P3-400：对话框「浏览远端目录」复用设置页同一控制器单例（表单凭据优先，无已保存回退）

    private val idleRemoteBrowseState = MutableStateFlow<com.keepasskey.app.sync.RemoteBrowseUiState>(com.keepasskey.app.sync.RemoteBrowseUiState.Idle)

    /** 浏览对话框状态（未注入控制器时恒 Idle，仅供单测构造路径） */
    val remoteBrowseState: StateFlow<com.keepasskey.app.sync.RemoteBrowseUiState>
        get() = remoteBrowseController?.state ?: idleRemoteBrowseState

    /** 浏览 WebDAV 目录：[password] 为借用语义副本（UI 传 copyOf），协程结束即擦 */
    fun browseRemoteWebDav(url: String, username: String, password: CharArray, directoryPath: String, cursor: String?) {
        val controller = remoteBrowseController ?: return
        viewModelScope.launch {
            try {
                controller.browseWebDav(url, username, password, directoryPath, cursor)
            } finally {
                password.fill('0')
            }
        }
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
        val controller = remoteBrowseController ?: return
        viewModelScope.launch {
            try {
                controller.browseS3(endpoint, bucket, region, accessKey, secretKey, directoryPath, usePathStyle, cursor)
            } finally {
                accessKey.fill('0')
                secretKey.fill('0')
            }
        }
    }

    /** 关闭浏览对话框并复位浏览状态 */
    fun dismissRemoteBrowse() {
        remoteBrowseController?.reset()
    }

    /** 导入并登记外部来源的密码库（见 [OpenVaultSourceType] / [OpenVaultSubmission]）。
     *
     * ISSUE-P2-399：云端来源不再是「只登记不连接」的假桩——先经 `CloudVaultImporter` 用所填
     * 凭据把远端库下载到本地（下载成功才落凭据），再按**本地文件路径**走既有
     * [importLocalDatabase] 登记出口，与「本地打开 → 同步配置补全」手工链路完全同构。
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
                is OpenVaultSubmission.Local -> importLocalDatabase(submission)
                is OpenVaultSubmission.Cloud -> importCloudDatabase(submission.request)
            }
        }
    }

    /** 本地库：登记（`content://` 持久化授权 / 文件路径）并置为活动库（原实现原样保留） */
    private suspend fun importLocalDatabase(
        submission: OpenVaultSubmission.Local,
        syncType: String = OpenVaultSourceType.LOCAL.label
    ) {
        val result = vaultRepository.importExternalDatabase(
            submission.name,
            submission.path,
            syncType = syncType
        )
        if (result is KdbxResult.Success) {
            val fileName = if (submission.name.endsWith(".kdbx", ignoreCase = true)) {
                submission.name
            } else {
                "${submission.name}.kdbx"
            }
            showOpenSourceDialogFlow.value = false
            publishPickerMessage(UiMessage(R.string.db_picker_msg_opened))
            _events.emit(DatabasePickerEvent.DatabaseSelected(fileName))
        } else {
            publishPickerMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
        }
    }

    /**
     * 云端库（ISSUE-P2-399）：先下载后登记。凭据 `CharArray` 擦除责任在 `CloudVaultImporter`；
     * 成功后以本地文件路径走 [importLocalDatabase] 同一出口，`syncType` 保持云端标签
     * （卡片云徽章与解锁页「云端库」状态照常呈现）。
     */
    private suspend fun importCloudDatabase(request: com.keepasskey.app.sync.CloudVaultImportRequest) {
        val importer = cloudVaultImporter
        if (importer == null) {
            publishPickerMessage(
                UiMessage(R.string.op_failed, listOf("CloudVaultImporter unavailable"), isError = true)
            )
            return
        }
        when (val result = importer.import(request)) {
            is com.keepasskey.app.sync.CloudVaultImportResult.Success -> {
                val local = java.io.File(result.localPath)
                val syncType = when (request) {
                    is com.keepasskey.app.sync.CloudVaultImportRequest.WebDav -> OpenVaultSourceType.WEBDAV.label
                    is com.keepasskey.app.sync.CloudVaultImportRequest.S3 -> OpenVaultSourceType.S3_COMPATIBLE.label
                }
                importLocalDatabase(
                    OpenVaultSubmission.Local(name = local.nameWithoutExtension, path = result.localPath),
                    syncType = syncType
                )
            }
            is com.keepasskey.app.sync.CloudVaultImportResult.Failure ->
                publishPickerMessage(result.message)
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

    /** 建议的密钥文件名：与密码库同名（`.kdbx` → `.keyx`），与设置页导出通道命名习惯一致 */
    private fun suggestedKeyFileName(vaultName: String): String {
        val base = if (vaultName.endsWith(KEY_FILE_EXTENSION_SOURCE, ignoreCase = true)) {
            vaultName.dropLast(KEY_FILE_EXTENSION_SOURCE.length)
        } else vaultName
        return base.ifBlank { DEFAULT_KEY_FILE_BASE } + KEY_FILE_EXTENSION
    }

    private companion object {
        /** 密码库文件扩展名（用于推导密钥文件建议名） */
        const val KEY_FILE_EXTENSION_SOURCE = ".kdbx"

        /** 密钥文件扩展名：KeePass 2.x XML 密钥文件的官方惯例后缀 */
        const val KEY_FILE_EXTENSION = ".keyx"

        /** 密码库名为空时的兜底建议名（与设置页导出通道的默认名一致） */
        const val DEFAULT_KEY_FILE_BASE = "keepasskey"
    }
}
