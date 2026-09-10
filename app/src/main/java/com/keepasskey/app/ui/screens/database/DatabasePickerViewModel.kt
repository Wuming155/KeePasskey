package com.keepasskey.app.ui.screens.database

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateKeyFileFactor
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiMessage
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

/**
 * 生成型密钥文件的一次性交付状态（ISSUE-P3-21 验收 2）。
 *
 * 勾选「生成附属密钥文件」建库时，密钥文件是复合密钥的第二因子：**丢失即永久无法解锁**，
 * 且它不会再次被生成（会话锁定后内存缓存即刻清零），故建库成功必须立即强制交付。
 * 本状态只承载**非密钥元数据**（建议文件名）；密钥文件字节始终留在数据层与 SAF 写入端，
 * 绝不进入 UiState / StateFlow / 日志。
 */
sealed interface KeyFileDeliveryState {

    /** 无待交付密钥文件 */
    data object None : KeyFileDeliveryState

    /** 库已按「主密码 + 密钥文件」建成，密钥文件尚未交付用户——必须显式保存或显式放弃 */
    data class PendingSave(val suggestedFileName: String) : KeyFileDeliveryState
}

@HiltViewModel
class DatabasePickerViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    // ISSUE-P3-21：既有密钥文件字节读取通道（SAF，复用解锁特性已建立的 KeyFileAccess 契约）。
    // nullable 仅为单测注入内存假实现；生产 DI 经 KeyFileAccessModule 恒注入 SafKeyFileAccess
    private val keyFileAccess: KeyFileAccess? = null,
    // ISSUE-P3-21：生成型密钥文件的 SAF 落盘所需上下文。nullable 仅为单测构造；
    // 生产 DI 注入 @ApplicationContext（与 SettingsViewModel 同一既有范式）
    @ApplicationContext private val appContext: Context? = null
) : ViewModel() {

    private val userMessageFlow = MutableStateFlow<UiMessage?>(null)
    private val showCreateDialogFlow = MutableStateFlow(false)
    private val showOpenSourceDialogFlow = MutableStateFlow(false)

    /** ISSUE-P3-21：生成型密钥文件的一次性交付状态（Screen 据此弹出强制保存提示） */
    private val keyFileDeliveryFlow = MutableStateFlow<KeyFileDeliveryState>(KeyFileDeliveryState.None)
    val keyFileDelivery: StateFlow<KeyFileDeliveryState> = keyFileDeliveryFlow.asStateFlow()

    private val _events = MutableSharedFlow<DatabasePickerEvent>()
    val events: SharedFlow<DatabasePickerEvent> = _events.asSharedFlow()

    val uiState: StateFlow<DatabasePickerUiState> = combine(
        vaultRepository.getDatabases(),
        userMessageFlow,
        combine(showCreateDialogFlow, showOpenSourceDialogFlow) { c, o -> Pair(c, o) }
    ) { databases, userMessage, (showCreateDialog, showOpenSourceDialog) ->
        DatabasePickerUiState(
            databases = databases,
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
     * 因子语义：
     * - [keyFile] = false → **仅主密码**建库；
     * - [keyFile] = true 且 [keyFileSourceUri] 为空 → **主密码 + 生成型密钥文件**：数据层生成
     *   符合 KeePass 2.x 规范的密钥文件并绑定进会话，成功后必须经一次性提示交付用户；
     * - [keyFile] = true 且 [keyFileSourceUri] 非空 → **主密码 + 用户选定的既有密钥文件**：
     *   经 [KeyFileAccess] 读取真实字节参与复合密钥。
     *   读取失败（空文件 / 超限 / 提供方拒绝）一律**显式失败且不创建库**——绝不静默降级为
     *   「生成型」或「仅主密码」，否则用户以为在用旧密钥文件、实际因子已被替换。
     *
     * 主密码副本在 `finally` 中显式擦除；借用给数据层的密钥文件字节副本在数据层用毕后立即擦除。
     */
    fun createDatabase(
        name: String,
        masterPassword: CharArray,
        keyFile: Boolean,
        preset: String,
        keyFileSourceUri: String? = null
    ) {
        viewModelScope.launch {
            // H2 整改：主密码全程 CharArray——复制私有副本并在 finally 擦除；
            // 入参数组为调用方（弹窗）所有，由其自身生命周期管理擦除
            val pwd = masterPassword.copyOf()
            try {
                val resolution = resolveKeyFileFactor(keyFile, keyFileSourceUri)
                if (resolution is KeyFileFactorResolution.Failed) {
                    userMessageFlow.value = resolution.message
                    return@launch
                }
                val resolved = resolution as KeyFileFactorResolution.Resolved
                // H3 整改：创建失败（写盘失败等）不再谎报创建成功
                val result = try {
                    vaultRepository.createDatabaseWithKeyFile(name, pwd, resolved.factor, preset)
                } finally {
                    // 借用语义的调用方责任：数据层已克隆持有自己的副本，此处副本用毕即擦
                    resolved.borrowedKeyFileBytes?.fill(0)
                }
                if (result is KdbxResult.Success) {
                    val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
                    showCreateDialogFlow.value = false
                    userMessageFlow.value = UiMessage(R.string.db_picker_msg_created)
                    if (resolved.generated) {
                        // ISSUE-P3-21 验收 2：生成型密钥文件必须一次性交付（丢失即无法解锁）
                        keyFileDeliveryFlow.value = KeyFileDeliveryState.PendingSave(
                            suggestedFileName = suggestedKeyFileName(name)
                        )
                    }
                    _events.emit(DatabasePickerEvent.DatabaseSelected(fileName))
                } else {
                    userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message))
                }
            } finally {
                pwd.fill('0')
            }
        }
    }

    /**
     * 把生成型密钥文件写入用户选定的 SAF 目标（ISSUE-P3-21 验收 2）。
     *
     * 复用**既有导出通道** [VaultRepository.exportKeyFileBytes]（建库时会话已按借用语义克隆
     * 缓存该密钥文件，见 `DatabaseSession.create`）：本方法只做「取字节 → 写 SAF → 擦副本」，
     * 不新造第二条密钥文件生成/读取路径。写盘成功即关闭一次性提示。
     */
    fun saveGeneratedKeyFileTo(targetUri: Uri) {
        viewModelScope.launch {
            val resolver = appContext?.contentResolver
            if (resolver == null) {
                // 禁止静默失败：没有写盘上下文时如实告知用户，提示保持驻留
                userMessageFlow.value = UiMessage(R.string.db_picker_keyfile_save_failed)
                return@launch
            }
            // 复用既有导出通道取字节：会话未绑定密钥文件时如实失败（绝不写空文件冒充成功）
            val bytes = vaultRepository.exportKeyFileBytes().getOrNull()
            if (bytes == null) {
                userMessageFlow.value = UiMessage(R.string.db_picker_keyfile_save_failed)
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
                userMessageFlow.value = UiMessage(R.string.db_picker_keyfile_saved)
            } else {
                userMessageFlow.value = UiMessage(R.string.db_picker_keyfile_save_failed)
            }
        }
    }

    /**
     * 用户显式选择「暂不保存密钥文件」：关闭一次性提示。
     *
     * 不清除会话内的密钥文件缓存（用户仍可在设置页「导出密钥文件」补存），
     * 但提示正文已如实声明「未保存将无法解锁」的后果，不存在误导性默认。
     */
    fun dismissKeyFileDelivery() {
        keyFileDeliveryFlow.value = KeyFileDeliveryState.None
    }

    fun importDatabaseFromSource(source: OpenVaultSourceType, name: String, path: String) {
        viewModelScope.launch {
            val result = vaultRepository.importExternalDatabase(name, path, syncType = source.label)
            if (result is KdbxResult.Success) {
                val fileName = if (name.endsWith(".kdbx", ignoreCase = true)) name else "$name.kdbx"
                showOpenSourceDialogFlow.value = false
                userMessageFlow.value = UiMessage(R.string.db_picker_msg_opened)
                _events.emit(DatabasePickerEvent.DatabaseSelected(fileName))
            } else {
                userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message))
            }
        }
    }

    fun removeDatabase(id: String) {
        viewModelScope.launch {
            val result = vaultRepository.removeDatabase(id)
            if (result is KdbxResult.Success) {
                userMessageFlow.value = UiMessage(R.string.db_picker_msg_removed)
            } else {
                userMessageFlow.value = UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message))
            }
        }
    }

    fun clearUserMessage() {
        userMessageFlow.value = null
    }

    /** 建库密钥文件因子的解析结果（ISSUE-P3-21） */
    private sealed interface KeyFileFactorResolution {

        /**
         * [generated] 标记是否为「生成型」因子（决定是否需要一次性交付提示）；
         * [borrowedKeyFileBytes] 为借用给数据层的字节副本，调用方用毕必须清零。
         */
        class Resolved(
            val factor: CreateKeyFileFactor,
            val generated: Boolean,
            val borrowedKeyFileBytes: ByteArray? = null
        ) : KeyFileFactorResolution

        /** 因子无法成立（既有密钥文件读取失败等）：显式失败，不得降级为其它因子 */
        class Failed(val message: UiMessage) : KeyFileFactorResolution
    }

    /**
     * 解析建库密钥文件因子：无密钥文件 / 生成型 / 用户选定既有文件。
     *
     * 既有文件路径**没有任何降级分支**：读不到就失败，绝不改用生成型（那会产出一个
     * 用户手上没有对应密钥文件的库）。
     */
    private suspend fun resolveKeyFileFactor(keyFile: Boolean, sourceUri: String?): KeyFileFactorResolution {
        if (!keyFile) {
            return KeyFileFactorResolution.Resolved(CreateKeyFileFactor.None, generated = false)
        }
        if (sourceUri.isNullOrBlank()) {
            return KeyFileFactorResolution.Resolved(CreateKeyFileFactor.Generate, generated = true)
        }
        val access = keyFileAccess
            ?: return KeyFileFactorResolution.Failed(UiMessage(R.string.unlock_keyfile_read_failed))
        return when (val outcome = access.read(sourceUri)) {
            is KeyFileReadResult.Success -> KeyFileFactorResolution.Resolved(
                factor = CreateKeyFileFactor.Existing(outcome.bytes),
                generated = false,
                // 字节所有权已移交因子；清零责任随借用契约留给 createDatabase 的调用收尾
                borrowedKeyFileBytes = outcome.bytes
            )
            // 「读不到」分型（空文件 / 超限 / 流异常）一律显式反馈，绝不静默忽略
            KeyFileReadResult.Empty,
            KeyFileReadResult.TooLarge,
            KeyFileReadResult.Unreadable -> KeyFileFactorResolution.Failed(
                UiMessage(R.string.unlock_keyfile_read_failed)
            )
        }
    }

    /** 建议的密钥文件名：与密码库同名（`.kdbx` → `.keyx`），与设置页导出通道命名习惯一致 */
    private fun suggestedKeyFileName(vaultName: String): String {
        val base = if (vaultName.endsWith(KEY_FILE_EXTENSION_SOURCE, ignoreCase = true)) {
            vaultName.dropLast(KEY_FILE_EXTENSION_SOURCE.length)
        } else {
            vaultName
        }
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
