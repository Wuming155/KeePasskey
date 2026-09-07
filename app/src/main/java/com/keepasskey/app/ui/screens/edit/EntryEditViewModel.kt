package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import java.security.SecureRandom

/**
 * 编辑页单次事件
 */
sealed interface EntryEditEvent {
    data object SaveSuccess : EntryEditEvent
}

/**
 * 凭据添加与编辑状态容器 ViewModel
 *
 * M1 整改（加解密审查 2026-09）：条目密码不再进入 [EntryEditUiState]（String 不可变驻留
 * StateFlow 堆内存），改由 ViewModel 以 [CharArray] 私有承载——
 * - 输入上行走 [onPasswordChangeSecure]（SecurePasswordField CharArray 桥接）；
 * - 既有条目密码经 [loadedPassword] 一次性下发至 SecurePasswordField 预填，
 *   显示用 String 仅存活于组件内部（框架边界），不进任何状态流；
 * - 保存时向仓库提交副本（仓库负责用毕清零），ViewModel 自有副本在 [onCleared] 擦除。
 */
@HiltViewModel
class EntryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val vaultRepository: VaultRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EntryEditUiState())
    val uiState: StateFlow<EntryEditUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EntryEditEvent>()
    val events: SharedFlow<EntryEditEvent> = _events.asSharedFlow()

    /** M1 整改：编辑中的条目密码，仅以 CharArray 驻留 ViewModel（绝不进入 UiState/StateFlow） */
    private var passwordChars = CharArray(0)

    /** TASK-10：编辑中的 TOTP 种子，仅以 CharArray 驻留 ViewModel（绝不进入 UiState/StateFlow） */
    private var totpSecretChars = CharArray(0)

    /**
     * TASK-10：受保护自定义字段编辑明文（键为 UiCustomField.id），仅以 CharArray 驻留
     * ViewModel——UI 投影中受保护字段值恒为空串（与详情页读路径掩码投影语义一致）。
     * 保存时按 id 显式提交，未编辑过的字段由仓库回填既有值（F2 语义）。
     */
    private val protectedFieldChars = mutableMapOf<String, CharArray>()

    /** 既有条目密码的一次性预填通道：SecurePasswordField 消费后即由输入路径接管 */
    private val _loadedPassword = MutableStateFlow<CharArray?>(null)
    val loadedPassword: StateFlow<CharArray?> = _loadedPassword.asStateFlow()

    /** TASK-10：既有 TOTP 种子的一次性预填通道（语义同 [loadedPassword]） */
    private val _loadedTotpSecret = MutableStateFlow<CharArray?>(null)
    val loadedTotpSecret: StateFlow<CharArray?> = _loadedTotpSecret.asStateFlow()

    /** TASK-10：既有受保护自定义字段的一次性预填通道（仅在条目加载时填充一次，编辑不经此回写） */
    private val _loadedProtectedFields = MutableStateFlow<Map<String, CharArray>>(emptyMap())
    val loadedProtectedFields: StateFlow<Map<String, CharArray>> = _loadedProtectedFields.asStateFlow()

    init {
        // H4-只读整改：会话只读时编辑页禁用保存
        _uiState.update { it.copy(isReadOnly = vaultRepository.isSessionReadOnly()) }
        val entryId: String? = savedStateHandle["entryId"]
        val groupId: String? = savedStateHandle["groupId"]
        if (entryId != null) {
            loadEntry(entryId)
        } else if (groupId != null) {
            _uiState.update { it.copy(groupId = groupId) }
        }

        viewModelScope.launch {
            vaultRepository.getGroups().collect { groups ->
                _uiState.update { it.copy(availableGroups = groups) }
            }
        }
    }

    fun loadEntry(id: String) {
        viewModelScope.launch {
            val entry = vaultRepository.getEntry(id).firstOrNull()
            if (entry != null) {
                // M1 整改：密码明文不随条目投影下发，编辑时按需单条解密为 CharArray
                // （所有权移交：数组副本交 loadedPassword 预填通道，用户编辑后即清零回收）
                val password = vaultRepository.getEntryPasswordChars(entry.id)
                // TASK-10：受保护自定义字段明文同理按需单条解密为 CharArray——不进 UiState
                // String，经私有映射驻留 + loadedProtectedFields 一次性预填；
                // UI 投影中受保护字段值恒为空串（保存时未编辑字段由仓库回填既有值）
                val loadedProtected = mutableMapOf<String, CharArray>()
                for (cf in entry.customFields) {
                    if (cf.isProtected) {
                        vaultRepository.getEntryProtectedFieldChars(entry.id, cf.key)?.let { chars ->
                            loadedProtected[cf.id] = chars
                        }
                    }
                }
                // 断点4 整改 + TASK-10：TOTP 配置原文按需解密为 CharArray（otp 字段优先，
                // 回退 TOTP 开头的自定义字段），经一次性预填通道下发
                val totpRaw = vaultRepository.getEntryTotpSecretChars(entry.id)
                // M1 整改：密码副本双通道——ViewModel 私有副本（保存用）+ 预填通道（组件显示用，
                // 用户开始编辑或 ViewModel 销毁时清零）
                passwordChars.fill('0')
                passwordChars = password?.copyOf() ?: CharArray(0)
                _loadedPassword.value?.fill('0')
                _loadedPassword.value = password
                // TASK-10：TOTP 私有副本 + 预填通道（语义同密码双通道）
                totpSecretChars.fill('0')
                totpSecretChars = totpRaw?.copyOf() ?: CharArray(0)
                _loadedTotpSecret.value?.fill('0')
                _loadedTotpSecret.value = totpRaw
                // TASK-10：重载前擦除旧受保护字段驻留，再重建私有副本与预填通道
                protectedFieldChars.values.forEach { it.fill('0') }
                protectedFieldChars.clear()
                _loadedProtectedFields.value.values.forEach { it.fill('0') }
                protectedFieldChars.putAll(loadedProtected.mapValues { (_, v) -> v.copyOf() })
                _loadedProtectedFields.value = loadedProtected
                _uiState.update {
                    it.copy(
                        entryId = entry.id,
                        groupId = entry.groupId,
                        iconName = entry.iconName,
                        title = entry.title,
                        username = entry.username,
                        passwordLength = password?.size ?: 0,
                        url = entry.url,
                        notes = entry.notes,
                        isPasskey = entry.isPasskey,
                        customFields = entry.customFields,
                        attachments = entry.attachments,
                        tagsInput = entry.tags.joinToString(", "),
                        autoTypeSequence = entry.autoTypeSequence,
                        overrideUrl = entry.overrideUrl.orEmpty(),
                        isDirty = false
                    )
                }
            }
        }
    }

    /**
     * M1 整改：SecurePasswordField 的 CharArray 桥接上行（用户输入）。
     * 桥接数组归组件所有（组件自行清零），此处复制私有副本长期持有；
     * 同时终结既有密码预填通道生命周期。
     */
    fun onPasswordChangeSecure(password: CharArray) {
        passwordChars.fill('0')
        passwordChars = password.copyOf()
        // 用户开始编辑后，既有密码预填通道生命周期结束
        _loadedPassword.value?.fill('0')
        _loadedPassword.value = null
        _uiState.update { it.copy(passwordLength = passwordChars.size, isDirty = true) }
    }

    fun onIconChange(icon: String) = _uiState.update { it.copy(iconName = icon, isDirty = true) }
    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title, isDirty = true) }
    fun onUsernameChange(username: String) = _uiState.update { it.copy(username = username, isDirty = true) }
    fun onUrlChange(url: String) = _uiState.update { it.copy(url = url, isDirty = true) }
    fun onNotesChange(notes: String) = _uiState.update { it.copy(notes = notes, isDirty = true) }

    fun onTogglePasskey() = _uiState.update {
        it.copy(
            isPasskey = !it.isPasskey,
            isDirty = true
        )
    }

    /**
     * TASK-10：TOTP 种子输入的 CharArray 桥接上行（语义同 [onPasswordChangeSecure]）。
     * 桥接数组归组件所有（组件自行清零），此处复制私有副本长期持有；
     * 同时终结既有 TOTP 预填通道生命周期。
     */
    fun onTotpSecretChangeSecure(secret: CharArray) {
        totpSecretChars.fill('0')
        totpSecretChars = secret.copyOf()
        _loadedTotpSecret.value?.fill('0')
        _loadedTotpSecret.value = null
        _uiState.update { it.copy(isDirty = true) }
    }

    fun onTagsInputChange(input: String) = _uiState.update { it.copy(tagsInput = input, isDirty = true) }

    fun onAutoTypeSequenceChange(sequence: String) = _uiState.update { it.copy(autoTypeSequence = sequence, isDirty = true) }

    fun onOverrideUrlChange(url: String) = _uiState.update { it.copy(overrideUrl = url, isDirty = true) }

    fun onTogglePasswordVisibility() = _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    fun onToggleGenerator() = _uiState.update { it.copy(showGenerator = !it.showGenerator) }

    fun onPassLengthChange(length: Float) {
        _uiState.update { it.copy(passLength = length) }
        generatePassword()
    }

    fun onToggleUpper() {
        _uiState.update { it.copy(useUpper = !it.useUpper) }
        generatePassword()
    }

    fun onToggleLower() {
        _uiState.update { it.copy(useLower = !it.useLower) }
        generatePassword()
    }

    fun onToggleDigits() {
        _uiState.update { it.copy(useDigits = !it.useDigits) }
        generatePassword()
    }

    fun onToggleSymbols() {
        _uiState.update { it.copy(useSymbols = !it.useSymbols) }
        generatePassword()
    }

    fun generatePassword() {
        val state = _uiState.value
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#\$%^&*()_+-=[]{}|;:,.<>?"

        var pool = ""
        if (state.useUpper) pool += upper
        if (state.useLower) pool += lower
        if (state.useDigits) pool += digits
        if (state.useSymbols) pool += symbols
        if (pool.isEmpty()) pool = lower

        val secureRandom = SecureRandom()
        // M1 整改：生成结果直达 CharArray，不经 String 中转
        val newPassword = CharArray(state.passLength.toInt()) {
            pool[secureRandom.nextInt(pool.length)]
        }
        onPasswordChangeSecure(newPassword)
        newPassword.fill('0')
    }

    fun onGroupChange(groupId: String?) = _uiState.update { it.copy(groupId = groupId, isDirty = true) }

    fun addCustomField() {
        val newField = UiCustomField(
            id = "field_${System.currentTimeMillis()}",
            key = "",
            value = "",
            isProtected = false
        )
        _uiState.update { it.copy(customFields = it.customFields + newField, isDirty = true) }
    }

    /**
     * 非受保护字段明文 / 键名 / 保护标记的通用编辑入口。
     * TASK-10：保护标记切换时同步迁移明文存储位置——
     * - 非受保护 → 受保护：明文迁入 CharArray 私有链路，状态值转为空串（掩码投影语义）；
     * - 受保护 → 非受保护：明文迁回状态 String（非受保护字段按格式边界以明文存储），Char 副本擦除。
     */
    fun updateCustomField(id: String, key: String, value: String, isProtected: Boolean) {
        val previous = _uiState.value.customFields.firstOrNull { it.id == id }
        var effectiveValue = value
        when {
            previous != null && !previous.isProtected && isProtected && value.isNotEmpty() -> {
                protectedFieldChars[id]?.fill('0')
                protectedFieldChars[id] = value.toCharArray()
                effectiveValue = ""
            }
            previous != null && previous.isProtected && !isProtected -> {
                val chars = protectedFieldChars.remove(id)
                if (chars != null) {
                    effectiveValue = String(chars)
                    chars.fill('0')
                }
            }
        }
        _uiState.update { state ->
            val updated = state.customFields.map { f ->
                if (f.id == id) f.copy(key = key, value = effectiveValue, isProtected = isProtected) else f
            }
            state.copy(customFields = updated, isDirty = true)
        }
    }

    /**
     * TASK-10：受保护字段明文输入的 CharArray 桥接上行（语义同 [onPasswordChangeSecure]）。
     * 桥接数组归组件所有（组件自行清零），此处复制私有副本长期持有。
     */
    fun updateProtectedFieldValue(id: String, chars: CharArray) {
        protectedFieldChars[id]?.fill('0')
        protectedFieldChars[id] = chars.copyOf()
        _uiState.update { it.copy(isDirty = true) }
    }

    fun removeCustomField(id: String) {
        protectedFieldChars.remove(id)?.fill('0')
        _uiState.update { state ->
            state.copy(customFields = state.customFields.filter { it.id != id }, isDirty = true)
        }
    }

    /**
     * 断点1 整改：真实附件添加——读取用户经 SAF 选择文件的字节并随编辑会话驻留内存，
     * 保存时随条目提交入库（保存时经去重器入池）。同名附件视为替换。
     */
    fun addAttachment(fileName: String, fileSizeFormatted: String, data: ByteArray) {
        if (data.isEmpty()) {
            _uiState.update { it.copy(userMessage = UiMessage(R.string.edit_attachment_empty)) }
            return
        }
        val newAtt = UiAttachment(
            id = "att_${System.currentTimeMillis()}",
            fileName = fileName,
            fileSizeFormatted = fileSizeFormatted,
            mimeType = "application/octet-stream",
            addedAt = "刚刚",
            data = data
        )
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.fileName == fileName } + newAtt, isDirty = true)
        }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filter { it.id != id }, isDirty = true)
        }
    }

    fun saveEntry() {
        val state = _uiState.value
        if (state.isReadOnly) {
            _uiState.update { it.copy(userMessage = UiMessage(R.string.readonly_save_rejected)) }
            return
        }
        if (state.title.isBlank()) {
            _uiState.update { it.copy(userMessage = UiMessage(R.string.edit_title_required)) }
            return
        }

        viewModelScope.launch {
            val entryId = state.entryId ?: UUID.randomUUID().toString()
            val entry = UiVaultEntry(
                id = entryId,
                title = state.title.trim(),
                username = state.username.trim(),
                url = state.url.trim(),
                notes = state.notes.trim(),
                isPasskey = state.isPasskey,
                category = if (state.isPasskey) EntryCategory.PASSKEY else EntryCategory.LOGIN,
                updatedAt = "刚刚",
                groupId = state.groupId,
                iconName = state.iconName,
                customFields = state.customFields.filter { it.key.isNotBlank() },
                attachments = state.attachments,
                tags = state.tagsInput.split(',', '\uff0c', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                autoTypeSequence = state.autoTypeSequence,
                overrideUrl = state.overrideUrl.trim().takeIf { it.isNotEmpty() }
            )
            // M1 整改：密码以独立参数显式提交，不再随条目投影携带；提交副本归仓库擦除
            // （契约：仓库任何结果路径用毕清零），ViewModel 自有副本保留以支持失败后继续编辑
            // 断点4 整改 + TASK-10：TOTP 种子与受保护自定义字段明文以 CharArray 副本随保存显式提交
            // H3 整改：保存失败必须显式反馈，禁止磁盘写失败时谎报成功
            val passwordCopy = passwordChars.copyOf()
            val totpCopy = totpSecretChars.copyOf()
            val protectedCopy = protectedFieldChars.mapValues { (_, v) -> v.copyOf() }
            val result = try {
                vaultRepository.saveEntry(
                    entry,
                    passwordChars = passwordCopy,
                    totpSecretChars = totpCopy,
                    protectedFieldChars = protectedCopy
                )
            } finally {
                // 兜底擦除：若仓库实现未按契约清零（如旧版本 Fake），此处保证副本不残留明文
                passwordCopy.fill('0')
                totpCopy.fill('0')
                protectedCopy.values.forEach { it.fill('0') }
            }
            if (result is KdbxResult.Success) {
                _events.emit(EntryEditEvent.SaveSuccess)
            } else {
                val failure = result as KdbxResult.Failure
                _uiState.update {
                    it.copy(userMessage = UiMessage(R.string.edit_save_failed, listOf(failure.message)))
                }
            }
        }
    }

    fun showMessage(msg: UiMessage) {
        _uiState.update { it.copy(userMessage = msg) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    override fun onCleared() {
        // M1 整改：ViewModel 销毁时彻底擦除密码驻留（预填通道与编辑副本）
        passwordChars.fill('0')
        passwordChars = CharArray(0)
        _loadedPassword.value?.fill('0')
        _loadedPassword.value = null
        // TASK-10：TOTP 种子与受保护自定义字段明文驻留一并彻底擦除
        totpSecretChars.fill('0')
        totpSecretChars = CharArray(0)
        _loadedTotpSecret.value?.fill('0')
        _loadedTotpSecret.value = null
        protectedFieldChars.values.forEach { it.fill('0') }
        protectedFieldChars.clear()
        _loadedProtectedFields.value.values.forEach { it.fill('0') }
        _loadedProtectedFields.value = emptyMap()
        super.onCleared()
    }
}
