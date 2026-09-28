package com.keepasskey.app.ui.screens.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.keepasskey.app.R
import androidx.lifecycle.viewModelScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.passkey.PasskeyImportDraft
import com.keepasskey.app.passkey.ScanPayloadClassifier
import com.keepasskey.app.passkey.ScanPayloadKind
import com.keepasskey.app.security.UnsavedEditRegistry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.security.SecureRandom

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
    private val vaultRepository: VaultRepository,
    // TASK-21：非 Compose 层文案资源解析通道（生产 DI 注入真实现；单测注入假实现）
    private val stringsProvider: StringsProvider? = null,
    // ISSUE-P2-65：会话锁定观察者注册点（null 仅用于纯 JVM 单测）
    private val databaseSession: com.keepasskey.database.session.DatabaseSession? = null,
    // PD-47：防截屏开关读取（生产 DI 注入真实现；单测注入 null 时恒 true = fail-closed）
    private val settingsRepository: SettingsRepository? = null,
    // ISSUE-P3-352 AC①：搜索词预填宿主（生产 DI 注入 @Singleton；null 仅纯 JVM 单测）
    private val createEntryPrefill: CreateEntryPrefillHost? = null,
    // ISSUE-P2-355 AC③：全局脏表单注册表（生产 DI 注入 @Singleton；null 仅纯 JVM 单测）
    private val unsavedEditRegistry: UnsavedEditRegistry? = null
) : ViewModel() {

    // P3-23：null 时回退空串实现（生产 Hilt 恒注入 StringsProviderModule 真实现）
    private val strings: StringsProvider = stringsProvider ?: StringsProvider { _, _ -> "" }

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

    // TASK-15：库内自定义图标池快照（UUID hex → PNG 字节），UI 层按需解码为 ImageBitmap
    private val _customIconOptions = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val customIconOptions: StateFlow<Map<String, ByteArray>> = _customIconOptions.asStateFlow()

    /** TASK-10：既有 TOTP 种子的一次性预填通道（语义同 [loadedPassword]） */
    private val _loadedTotpSecret = MutableStateFlow<CharArray?>(null)
    val loadedTotpSecret: StateFlow<CharArray?> = _loadedTotpSecret.asStateFlow()

    /** TASK-10：既有受保护自定义字段的一次性预填通道（仅在条目加载时填充一次，编辑不经此回写） */
    private val _loadedProtectedFields = MutableStateFlow<Map<String, CharArray>>(emptyMap())
    val loadedProtectedFields: StateFlow<Map<String, CharArray>> = _loadedProtectedFields.asStateFlow()

    /** PD-47：防截屏开关快照（扫码对话框 FLAG_SECURE 跟随）；无仓库注入（纯 JVM 单测）恒 true = fail-closed。 */
    private val _flagSecureEnabled = MutableStateFlow(true)
    val flagSecureEnabled: StateFlow<Boolean> = _flagSecureEnabled.asStateFlow()

    /** ISSUE-P2-286 AC① / ISSUE-P3-337 搬移：强度评估调度（见该类 KDoc，行为逐字不变）。 */
    private val entropyRefresh = EntryEditEntropyRefresh(viewModelScope, _uiState::update)

    /**
     * ISSUE-P3-337 Q1：编辑页「扫码 / 相册导入通行密钥」会话（挂当前条目、原地替换）。
     * 解析与草案承载走与顶栏同一个 [com.keepasskey.app.passkey.PasskeyImportDraftHost]；
     * 声明在 `init` 之前是为 `UnconfinedTestDispatcher` 下 `loadEntry` 可同步读到它。
     */
    private val passkeyImport = EntryEditPasskeyImport(
        scope = viewModelScope,
        repository = vaultRepository,
        host = EntryEditPasskeyImportHost(
            isReadOnly = { _uiState.value.isReadOnly },
            entryId = { _uiState.value.entryId },
            hasUnsavedEdits = { _uiState.value.isDirty },
            onNotice = { res -> showMessage(UiMessage(res)) },
            onReplaced = { _uiState.value.entryId?.let { loadEntry(it) } }
        )
    )

    /** 待确认的通行密钥导入草案（非空即确认对话框可见；仅内存持有，不经路由 / SavedStateHandle）。 */
    val pendingPasskeyImport: StateFlow<PasskeyImportDraft?> = passkeyImport.pendingPasskeyImport

    /**
     * `ISSUE-P3-342`：解除绑定会话（真实写通路 + 确认对话框；口径与理由见
     * [EntryEditPasskeyUnbind] 与 `PD-50`）。确认态只存活于本会话内存（P2-105 红线）。
     */
    private val passkeyUnbind = EntryEditPasskeyUnbind(
        scope = viewModelScope,
        repository = vaultRepository,
        uiState = _uiState,
        onNotice = { res -> showMessage(UiMessage(res)) },
        onReload = { _uiState.value.entryId?.let { loadEntry(it) } }
    )

    /** 非空即「解除绑定」确认对话框可见。 */
    val showUnbindPasskeyConfirm: StateFlow<Boolean> = passkeyUnbind.confirm

    /** ISSUE-P3-342 期间按行数分档闸门拆出的两个纯 UI 状态协作者（行为逐字不变，见各自 KDoc）。 */
    private val generator = EntryEditPasswordGenerator(
        state = { _uiState.value },
        update = _uiState::update,
        emitPassword = this::onPasswordChangeSecure
    )

    private val attachments = EntryEditAttachmentDraft(strings = strings, update = _uiState::update)

    /**
     * ISSUE-P3-337 搬移：自定义字段四写入口的执行者（AC⑪① 只读锁在 [protectedFieldChars] 一侧
     * 由它执行，见该类 KDoc）。
     */
    private val customFields = EntryEditCustomFieldEditor(
        state = { _uiState.value },
        update = { transform -> _uiState.update(transform) },
        protectedChars = protectedFieldChars
    )

    init {
        // H4-只读整改：会话只读时编辑页禁用保存
        _uiState.update { it.copy(isReadOnly = vaultRepository.isSessionReadOnly()) }
        val entryId: String? = savedStateHandle["entryId"]
        val groupId: String? = savedStateHandle["groupId"]
        val templateId: String? = savedStateHandle["templateId"]
        if (entryId != null) {
            loadEntry(entryId)
        } else if (templateId != null) {
            // ISSUE-P3-51：从模板新建——预填字段但保持 entryId 为空（保存即新建）
            loadTemplate(templateId, groupId)
        } else if (groupId != null) {
            _uiState.update { it.copy(groupId = groupId) }
        }

        val searchPrefill = createEntryPrefill?.takeTitle()
        if (searchPrefill != null && entryId == null && templateId == null) {
            _uiState.update { it.copy(title = searchPrefill, url = if (searchPrefill.contains("://")) searchPrefill else it.url) }
        }
        viewModelScope.launch {
            vaultRepository.getGroups().collect { groups ->
                _uiState.update { it.copy(availableGroups = groups) }
            }
        }

        // TASK-15：加载库内自定义图标池（上传/选择界面数据源）
        viewModelScope.launch {
            _customIconOptions.value = vaultRepository.getCustomIconBytes()
        }

        // PD-47：随设置流刷新防截屏开关快照（扫码对话框在组合时消费当前值）
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.getSettings().map { it.flagSecureEnabled }.collect { _flagSecureEnabled.value = it }
            }
        }

        unsavedEditRegistry?.register(this) { _uiState.value.isDirty } // ISSUE-P2-355 AC③：注册脏态提供者（onCleared 注销配对，杜绝陈旧脏态）

        // ISSUE-P3-368 AC②：保存链进度桥接——0..1 确定段、null 分段不确定段，
        // 即发即弃只更新 UI 状态（顶栏进度条在 isSaving 期间渲染）
        viewModelScope.launch {
            vaultRepository.ioProgress().collect { value ->
                _uiState.update { it.copy(saveProgress = value) }
            }
        }
    }

    fun loadEntry(id: String) {
        // ISSUE-P3-359 AC⑤：解密预填期间置加载态——入口同步置位，首帧即在遮罩之下
        _uiState.update { it.copy(isLoading = true) }
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
                    // AC⑪①：被只读锁定的凭据材料**不解密进编辑态**——编辑页既改不动它，
                    // 就没有把它（私钥 PEM / userHandle / PRF 种子）物化到本 ViewModel 的理由；
                    // 保存路径由 VaultEntryWriteCoordinator 的回填分支按既有值原样写回。
                    if (cf.isProtected && !isLockedPasskeyFieldKey(cf.key)) {
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
                _uiState.update { applyLoadedEntry(it, entry, password?.size ?: 0) }
                // ISSUE-P2-286：载入既有条目时同步评估强度（编辑页与详情页同一真相源）
                if (password != null && password.isNotEmpty()) {
                    entropyRefresh.refresh(password)
                }
            } else {
                // ISSUE-P3-359 AC⑤：未找到（被删除 / 无效 id）同样回落，避免遮罩永久悬挂
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * ISSUE-P3-51：以库内模板条目预填**新建**表单（不复制机密与大对象，见 [applyTemplateEntry]）。
     * 模板不可用（被删除）时保持空白新建表单，不报错。
     */
    private fun loadTemplate(templateId: String, targetGroupId: String?) {
        // ISSUE-P3-359 AC⑤：模板预填同为异步载入，遮罩口径与 loadEntry 一致
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val template = vaultRepository.getEntry(templateId).firstOrNull()
            if (template == null) {
                // 模板不可用（被删除）→ 保持空白新建表单并结束加载态，不报错
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            _uiState.update { applyTemplateEntry(it, template, targetGroupId) }
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
        entropyRefresh.refresh(password)
    }

    // TASK-15：标准图标与自定义图标互斥——选标准图标即清除自定义引用
    fun onIconChange(icon: String) = _uiState.update { it.copy(iconName = icon, customIconId = null, isDirty = true) }

    /** TASK-15：选择/清除（null）自定义图标引用 */
    fun onCustomIconSelected(iconId: String?) =
        _uiState.update { current -> current.copy(customIconId = iconId, isDirty = current.customIconId != iconId) }

    /** TASK-15：上传 PNG 字节为库级自定义图标并选中；失败如实上浮 */
    fun onCustomIconUploaded(pngBytes: ByteArray) {
        viewModelScope.launch {
            when (val result = vaultRepository.addCustomIcon(pngBytes)) {
                is com.keepasskey.core.result.KdbxResult.Success -> {
                    _customIconOptions.value = vaultRepository.getCustomIconBytes()
                    _uiState.update { it.copy(customIconId = result.data, isDirty = true) }
                }
                is com.keepasskey.core.result.KdbxResult.Failure ->
                    _uiState.update {
                        it.copy(userMessage = UiMessage(R.string.edit_save_failed, listOf(result.message)))
                    }
            }
        }
    }
    // ISSUE-P3-359 AC②：任何标题输入即清除字段级校验位（问题修复的即时反馈）
    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title, isDirty = true, titleError = false) }
    fun onUsernameChange(username: String) = _uiState.update { it.copy(username = username, isDirty = true) }
    fun onUrlChange(url: String) = _uiState.update { it.copy(url = url, isDirty = true) }
    fun onNotesChange(notes: String) = _uiState.update { it.copy(notes = notes, isDirty = true) }

    // `ISSUE-P3-342`：原 `onTogglePasskey` 已删除——它只翻一个草稿布尔，而 `isPasskey` 从不落盘
    // （不在 saveMergedEntry / mapUiEntryToKdbx 的字段清单里，读路径反而按凭据字段存在与否重算）
    // ⇒ 「绑定」点了保存后什么也不会发生，「解除」点了保存后凭据**仍然可被 CM 捞出并签名**。
    // 替代者是下面三个方法（真实写通路 + 确认对话框，见 EntryEditPasskeyUnbind 与 PD-50）；
    // 未绑定态不再渲染「绑定」按钮。

    /** 请求解除绑定：只做前置校验并挂确认对话框，**不写库**。 */
    fun requestUnbindPasskey() = passkeyUnbind.request()

    /** 取消解除：只关对话框，不写库（AC② 的取消路径）。 */
    fun dismissUnbindPasskey() = passkeyUnbind.dismiss()

    /** 确认解除：摘掉本条目的凭据字段，成功后由仓库重算并回显绑定态。 */
    fun confirmUnbindPasskey() = passkeyUnbind.confirmUnbind()

    /**
     * TASK-10：TOTP 种子输入的 CharArray 桥接上行（语义同 [onPasswordChangeSecure]）。
     * 桥接数组归组件所有（组件自行清零），此处复制私有副本长期持有；
     * 同时终结既有 TOTP 预填通道生命周期。
     */
    fun onTotpSecretChangeSecure(secret: CharArray) {
        totpSecretChars.fill('0')
        totpSecretChars = secret.copyOf()
        // ISSUE-P3-320：扫码回填后经预填通道**回显新种子**（新鲜副本 + epoch 递增换 key，
        // 驱动 SecurePasswordField 重新消费一次），字段即时可见扫码结果——旧实现将通道置 null
        // 且不换 key，字段停留旧内容（或被下一轮清零竞态刷成 '0' 串），用户无法感知扫码成功。
        // 旧预填数组仍按「用毕清零」原地擦除（SecurePasswordField 已组合期同步消费完毕，无竞态）。
        _loadedTotpSecret.value?.fill('0')
        _loadedTotpSecret.value = secret.copyOf()
        _uiState.update { it.copy(isDirty = true, totpPrefillEpoch = it.totpPrefillEpoch + 1) }
    }

    /**
     * 扫码 / 相册导入对话框上行的解码文本按载荷分流（`ISSUE-P3-337` Q1 + 口径 1）。
     *
     * 只有**通行密钥形态**改走导入会话；其余一律沿用本页原有行为——直接把解码文本当种子回填
     * （[onTotpSecretChangeSecure] 一字未改）。AC⑤ 的「TOTP 分支一字不改」在本页按
     * 「非通行密钥形态即旧通路」落地：编辑页的手填兼容通道本就接受纯 Base32 等宽松形态，
     * 顶栏那条「Unknown 即拒」在这里**不适用**——这里回填的是用户看得见、可撤销的输入框，
     * 不是静默建条目；按顶栏口径拒绝反而会让「扫一张旧种子二维码」这条既有通路回归。
     */
    fun onQrPayloadDecoded(decoded: CharArray) {
        if (ScanPayloadClassifier.classify(decoded) == ScanPayloadKind.Passkey) {
            passkeyImport.beginFromScan(decoded)
        } else {
            onTotpSecretChangeSecure(decoded)
        }
    }

    /** 用户在确认对话框点「替换通行密钥」（Q1）；草案的擦除义务见 [EntryEditPasskeyImport.confirm]。 */
    fun confirmPasskeyImport() = passkeyImport.confirm()

    /** 用户取消导入：擦除草案、不写库（AC③ 取消路径）。 */
    fun dismissPasskeyImport() = passkeyImport.dismiss()

    fun onTagsInputChange(input: String) = _uiState.update { it.copy(tagsInput = input, isDirty = true) }

    fun onAutoTypeSequenceChange(sequence: String) = _uiState.update { it.copy(autoTypeSequence = sequence, isDirty = true) }

    fun onOverrideUrlChange(url: String) = _uiState.update { it.copy(overrideUrl = url, isDirty = true) }

    // ISSUE-P3-310：过期编辑两态（关闭 = 永不过期；选择日期 = 当日 23:59:59 过期）
    fun onToggleExpiry(enabled: Boolean) = _uiState.update {
        it.copy(expiresEnabled = enabled, isDirty = true)
    }

    fun onExpiryDateSelected(date: java.time.LocalDate) = _uiState.update {
        it.copy(expiresEnabled = true, expiryDate = date, isDirty = true)
    }

    fun onTogglePasswordVisibility() = generator.togglePasswordVisibility()
    fun onToggleGenerator() = generator.togglePanel()
    fun onPassLengthChange(length: Float) = generator.onPassLengthChange(length)
    fun onToggleUpper() = generator.toggleUpper()
    fun onToggleLower() = generator.toggleLower()
    fun onToggleDigits() = generator.toggleDigits()
    fun onToggleSymbols() = generator.toggleSymbols()
    fun generatePassword() = generator.generate()

    fun onGroupChange(groupId: String?) = _uiState.update { it.copy(groupId = groupId, isDirty = true) }

    fun addCustomField() = customFields.add()

    /** 非受保护字段明文 / 键名 / 保护标记编辑入口（被锁的通行密钥字段就地拒绝，见执行者 KDoc）。 */
    fun updateCustomField(id: String, key: String, value: String, isProtected: Boolean) =
        customFields.updateField(id, key, value, isProtected)

    /**
     * TASK-10：受保护字段明文输入的 CharArray 桥接上行（语义同 [onPasswordChangeSecure]）。
     * AC⑪①：私钥 PEM / `userHandle` 等被锁字段在 [EntryEditCustomFieldEditor.updateProtectedValue]
     * 内直接拒收。
     */
    fun updateProtectedFieldValue(id: String, chars: CharArray) =
        customFields.updateProtectedValue(id, chars)

    fun removeCustomField(id: String) = customFields.remove(id)

    /**
     * 断点1 整改：真实附件添加——读取用户经 SAF 选择文件的字节并随编辑会话驻留内存，
     * 保存时随条目提交入库（保存时经去重器入池）。同名附件视为替换。
     */
    fun addAttachment(fileName: String, fileSizeFormatted: String, data: ByteArray) =
        attachments.add(fileName, fileSizeFormatted, data)

    fun removeAttachment(id: String) = attachments.remove(id)

    /**
     * 保存执行体（`ISSUE-P2-354 AC①②` 下沉同批，行为与守卫语义见其 KDoc）。
     * 借用 lambda 只在协程体内读取私有驻留，本字段不引入任何新明文副本。
     */
    private val saveRunner = EntryEditSaveRunner(
        scope = viewModelScope,
        repository = vaultRepository,
        strings = strings,
        uiState = _uiState,
        events = _events,
        passwordChars = { passwordChars },
        totpSecretChars = { totpSecretChars },
        protectedFieldChars = { protectedFieldChars }
    )

    /** 保存条目（`ISSUE-P2-354 AC①②`：并发第二次直接 return，成功才一次性导航）。 */
    fun saveEntry() = saveRunner.request()

    fun showMessage(msg: UiMessage) {
        _uiState.update { it.copy(userMessage = msg) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    override fun onCleared() {
        // M1 整改：ViewModel 销毁时彻底擦除密码驻留（预填通道与编辑副本）
        clearAllSecrets()
        // ISSUE-P2-355 AC③：注销全局脏态提供者（锁定销毁编辑页后不得残留陈旧脏态）
        unsavedEditRegistry?.unregister(this)
        sessionLockGuard.unregister()
        super.onCleared()
    }

    /**
     * ISSUE-P2-65：会话锁定 / 关闭时擦除全部编辑态明文（口令 / TOTP 种子 / 受保护字段），
     * 不得仅依赖 ViewModel 销毁（`onCleared`）——锁定后 ViewModel 可能仍被导航栈持有。
     */
    private val sessionLockGuard = com.keepasskey.database.session.SessionLockGuard(databaseSession) {
        clearAllSecrets()
    }

    init {
        sessionLockGuard.register()
    }

    private fun clearAllSecrets() {
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
        // ISSUE-P3-337 AC③：会话锁定 / 销毁时，尚未确认的导入草案（私钥明文唯一持有者）一并擦除
        passkeyImport.wipeAll()
        // ISSUE-P3-342：会话锁定 / 页面销毁时不留悬空的「解除」确认态
        // （不这么做会出现"重新解锁后对话框还在，点了就删凭据"的路径）
        passkeyUnbind.reset()
    }
}
