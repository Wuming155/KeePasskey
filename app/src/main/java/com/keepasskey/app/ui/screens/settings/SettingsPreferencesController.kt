package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.AutofillFieldBlocklistStore
import com.keepasskey.app.autofill.AutofillSaveBlocklistStore
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 活动库文件头 → 设置页显示值的映射（ISSUE-P2-19 / P3-59）。
 *
 * 此前「密码库与加密」页的算法/KDF/参数全部来自 [DatabaseConfigUiState] 的硬编码占位默认值，
 * 与真实文件头不符（ ChaCha20 显示 vs AES 实际 / Argon2d·8轮 vs Argon2id·2轮 等）。
 * 本映射以活动库 [KdbxDatabase.header] 为单一真相源；未挂接会话时返回 null（UI 保持空态占位）。
 */
internal fun databaseConfigFromHeader(db: KdbxDatabase): DatabaseConfigUiState {
    val header = db.header
    val cipherLabel = when (header.cipherUuid) {
        KdbxConstants.Cipher.AES_256_CBC -> "AES-256-CBC (256-bit)"
        KdbxConstants.Cipher.CHACHA20 -> "ChaCha20-Poly1305 (256-bit)"
        KdbxConstants.Cipher.TWOFISH -> "Twofish-CBC (256-bit)"
        else -> ""
    }
    val kdf = header.kdfParameters
    val kdfLabel = when (kdf) {
        is KdfParameters.Aes -> "AES-KDF"
        is KdfParameters.Argon2 -> if (kdf.type == KdfParameters.Argon2.Argon2Type.ARGON2D) "Argon2d" else "Argon2id"
    }
    val compressionLabel = when (header.compression) {
        KdbxConstants.Compression.GZIP -> "GZip 压缩"
        else -> "无压缩"
    }
    return DatabaseConfigUiState(
        databaseName = db.databaseName,
        defaultUsername = db.defaultUserName,
        encryptionAlgorithm = cipherLabel,
        kdfAlgorithm = kdfLabel,
        argon2Iterations = if (kdf is KdfParameters.Argon2) kdf.iterations else 0L,
        argon2MemoryMb = if (kdf is KdfParameters.Argon2) kdf.memoryInBytes / (1024L * 1024L) else 0L,
        argon2Parallelism = if (kdf is KdfParameters.Argon2) kdf.parallelism else 0,
        compressionAlgorithm = compressionLabel,
        recycleBinEnabled = db.recycleBinEnabled
    )
}

/**
 * 基础偏好（`UserSettings` 仓库直写项）与本页局部 UI 状态（ISSUE-P3-29：自
 * `SettingsViewModel.kt` 拆出，纯结构性拆分，行为零变更）。
 *
 * 承接三类内容：
 * 1. `SettingsRepository` 直写偏好（主题 / 生物识别 / 列表显示 / 标签页等）；
 * 2. 本页局部投影状态（密码库配置 / 自动填充启用 / 自动锁定超时）及其 setter；
 * 3. 调试日志实时缓冲快照与刷新/清除动作。
 */
internal class SettingsPreferencesController(
    private val settingsRepository: SettingsRepository,
    vaultRepository: VaultRepository,
    private val autofillBlocklistStore: AutofillBlocklistStore,
    // ISSUE-P3-43 ③：保存侧独立黑名单（与填充黑名单分离）
    private val autofillSaveBlocklistStore: AutofillSaveBlocklistStore,
    // ISSUE-P3-43 ②：字段签名级屏蔽（写入方在手动选择器；此处仅计数回显与整体清除）
    private val autofillFieldBlocklistStore: AutofillFieldBlocklistStore,
    private val debugLogBuffer: DebugLogBuffer,
    // ISSUE-P2-19 / P3-59：活动库会话（可为 null——单测注入；null 时加密配置保持空态占位）
    private val databaseSession: DatabaseSession?,
    private val scope: CoroutineScope
) {

    private val autofillStateFlow = MutableStateFlow(
        AutofillUiState(
            credentialProviderEnabled = true,
            passkeySupportEnabled = true,
            autofillServiceEnabled = true
        )
    )

    private val databaseConfigStateFlow = MutableStateFlow(
        DatabaseConfigUiState(
            databaseName = "",
            defaultUsername = "",
            // ISSUE-P2-19：算法/KDF/参数不再预置假值——空串代表「尚无活动库或会话未就绪」，
            // 真实值由 init 中 databaseSession.databaseFlow 下发；UI 侧对空值显示「未设置」占位。
            encryptionAlgorithm = "",
            kdfAlgorithm = "",
            argon2Iterations = 0L,
            argon2MemoryMb = 0L,
            argon2Parallelism = 0,
            recycleBinEnabled = true
            // ISSUE-P3-65：tanExpiresOnUse / checkForDuplicateUuids 字段已移除——
            // 假开关无真实语义与消费方，UI 入口已如实禁用
            // ISSUE-P3-20：childDatabasesCount 字段已整体移除——它原先承载的硬编码 0
            // 会与真实挂载数冲突；真实值改由 childDatabaseCountFlow（核心层 mountedCount）下发
        )
    )

    private val securityTimeoutStateFlow = MutableStateFlow(
        SecurityTimeoutUiState(
            autoLockTimeoutSeconds = 0
        )
    )

    /** 调试日志真实缓冲快照（随刷新/清除动作更新） */
    private val debugLogLinesFlow = MutableStateFlow(debugLogBuffer.snapshot())

    val autofillState: StateFlow<AutofillUiState> = autofillStateFlow
    val databaseConfigState: StateFlow<DatabaseConfigUiState> = databaseConfigStateFlow
    val securityTimeoutState: StateFlow<SecurityTimeoutUiState> = securityTimeoutStateFlow
    val debugLogLines: StateFlow<List<String>> = debugLogLinesFlow

    // ========== TASK-44：自动填充黑名单（真实条目生命周期） ==========

    /**
     * 自动填充黑名单快照（按包名升序）。黑名单不进 `uiState` 的 combine 链——
     * 其更新频率与生命周期独立于设置项，单独下发可避免 5 流 combine 的元组膨胀。
     */
    val autofillBlockedPackages: StateFlow<List<String>> = autofillBlocklistStore.blockedPackages

    /**
     * 将应用加入黑名单。
     * @return true=新增成功；false=包名非法或已在黑名单中（调用方据此如实提示，不谎报成功）
     */
    fun blockAutofillPackage(packageName: String): Boolean = autofillBlocklistStore.add(packageName)

    /** 将应用移出黑名单（删除动作）。@return true=移除成功；false=包名非法或本就不在黑名单中 */
    fun unblockAutofillPackage(packageName: String): Boolean =
        autofillBlocklistStore.remove(packageName)

    // ========== ISSUE-P3-43：保存侧黑名单与字段签名级屏蔽 ==========

    /** 「不再提示保存」名单快照（按包名升序）。 */
    val autofillSaveBlockedPackages: StateFlow<List<String>> = autofillSaveBlocklistStore.blockedPackages

    /** 加入「不再提示保存」名单。@return true=新增成功；false=包名非法或已存在 */
    fun blockSavePackage(packageName: String): Boolean = autofillSaveBlocklistStore.add(packageName)

    /** 移出「不再提示保存」名单。@return true=移除成功；false=包名非法或本就不在名单中 */
    fun unblockSavePackage(packageName: String): Boolean = autofillSaveBlocklistStore.remove(packageName)

    /**
     * 已屏蔽字段签名的**条数**（不下发签名本身）。
     *
     * 只下发计数是有意的：签名不可逆，UI 无法把它还原成可读目标，
     * 把签名递到 UI 层只会诱导后来者去"展示列表"，做出一份失真回显。
     */
    val autofillBlockedFieldCount: StateFlow<Int> = autofillFieldBlocklistStore.blockedSignatures
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** 清除全部字段级屏蔽。@return 被清除的条数 */
    fun clearBlockedFields(): Int = autofillFieldBlocklistStore.clearAll()

    init {
        // 动态订阅活动数据库，更新设置页数据库名称与文件路径（ISSUE-P3-59：路径此前恒空）
        scope.launch {
            vaultRepository.getDatabases().collect { databases ->
                val active = databases.firstOrNull { it.isActive } ?: databases.firstOrNull()
                databaseConfigStateFlow.update {
                    it.copy(
                        databaseName = active?.name.orEmpty(),
                        databasePath = active?.path.orEmpty()
                    )
                }
            }
        }
        // ISSUE-P2-19 / P3-59：订阅活动库会话，把真实文件头（加密算法 / KDF / Argon2 参数 /
        // 压缩）与 Meta（默认用户名）下发到设置页，替换此前的硬编码占位值。
        // 用 copy 合并而非整体替换：databasePath（来自库记录）与用户开关状态不被覆盖。
        scope.launch {
            databaseSession?.databaseFlow?.collect { db ->
                if (db != null) {
                    val header = databaseConfigFromHeader(db)
                    databaseConfigStateFlow.update {
                        it.copy(
                            databaseName = header.databaseName,
                            defaultUsername = header.defaultUsername,
                            encryptionAlgorithm = header.encryptionAlgorithm,
                            kdfAlgorithm = header.kdfAlgorithm,
                            argon2Iterations = header.argon2Iterations,
                            argon2MemoryMb = header.argon2MemoryMb,
                            argon2Parallelism = header.argon2Parallelism,
                            compressionAlgorithm = header.compressionAlgorithm
                        )
                    }
                }
            }
        }
    }

    // ========== 密码库与加密配置 ==========
    fun setEncryptionAlgorithm(algorithm: String) {
        databaseConfigStateFlow.update { it.copy(encryptionAlgorithm = algorithm) }
    }

    fun setKdfAlgorithm(kdf: String) {
        databaseConfigStateFlow.update { it.copy(kdfAlgorithm = kdf) }
    }

    /**
     * 应用 Argon2 KDF 参数（ISSUE-P2-22 整改：真实生效）。
     *
     * 此前仅回写 UI 内存回显（假闭环）：既不更新会话头也不落盘，冷启动即还原。
     * 现接入 [DatabaseSession.updateDatabaseMeta] 更新活动库头的变体字典 I/M/P 并立即
     * [DatabaseSession.save]——写侧 `KdbxFile.save` 以保存时的 `header.kdfParameters`
     * （含全新随机 salt）重派生加密密钥并写出新外层头，语义与官方 KeePass「KDF 参数
     * 保存时生效」一致。回显不再自持状态：会话 databaseFlow 重发后由 init 的头映射
     * 通道统一下发（P2-19 单一真相源）。
     *
     * 无活动会话 / 库头非 Argon2 时如实 no-op（回显保持文件头真值，不产生假变更）。
     */
    fun setArgon2Parameters(iterations: Long, memoryMb: Long, parallelism: Int) {
        val session = databaseSession ?: return
        scope.launch {
            session.updateDatabaseMeta { db ->
                val kdf = db.header.kdfParameters
                if (kdf is KdfParameters.Argon2) {
                    db.copy(
                        header = db.header.copy(
                            kdfParameters = kdf.copy(
                                iterations = iterations,
                                memoryInBytes = memoryMb * 1024L * 1024L,
                                parallelism = parallelism
                            )
                        )
                    )
                } else {
                    db
                }
            }
            session.save()
        }
    }

    fun setRecycleBinEnabled(enabled: Boolean) {
        databaseConfigStateFlow.update { it.copy(recycleBinEnabled = enabled) }
    }

    // ISSUE-P3-65：setTanExpiresOnUse / setCheckForDuplicateUuids 已移除——
    // 两者仅回写内存回显且无任何行为消费方（假开关），UI 入口已如实禁用。

    // ========== 自动填充启用开关 ==========
    fun setCredentialProviderEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(credentialProviderEnabled = enabled) }
    }

    fun setPasskeySupportEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(passkeySupportEnabled = enabled) }
    }

    fun setAutofillServiceEnabled(enabled: Boolean) {
        autofillStateFlow.update { it.copy(autofillServiceEnabled = enabled) }
    }

    // ========== 设备解锁与安全 ==========
    fun setAutoLockTimeout(seconds: Int) {
        securityTimeoutStateFlow.update {
            it.copy(autoLockTimeoutSeconds = seconds)
        }
        scope.launch {
            settingsRepository.setAutoLockTimeoutSeconds(seconds)
        }
    }

    fun setClipboardTimeout(seconds: Int) {
        scope.launch {
            settingsRepository.setClipboardTimeout(seconds)
        }
    }

    /** ISSUE-P3-68：解锁失败重试节流总开关（仓库直写项，UI 回显经设置流投影） */
    fun setUnlockThrottleEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setUnlockThrottleEnabled(enabled)
        }
    }

    /** ISSUE-P3-68：重试退避的最长锁定时长（秒；仓库层 coerce 合法域） */
    fun setUnlockLockoutMaxSeconds(seconds: Int) {
        scope.launch {
            settingsRepository.setUnlockLockoutMaxSeconds(seconds)
        }
    }

    // ========== 仓库直写偏好 ==========
    fun setAppLanguage(language: AppLanguage) {
        scope.launch {
            settingsRepository.setAppLanguage(language)
        }
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        scope.launch {
            settingsRepository.setThemeMode(themeMode)
        }
    }

    fun setThemePalette(themePalette: AppThemePalette) {
        scope.launch {
            settingsRepository.setThemePalette(themePalette)
        }
    }

    fun setOledBlackOptimization(enabled: Boolean) {
        scope.launch {
            settingsRepository.setOledBlackOptimization(enabled)
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setDynamicColorEnabled(enabled)
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setBiometricEnabled(enabled)
        }
    }

    fun setAutoLockBackground(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoLockBackground(enabled)
        }
    }

    fun setFlagSecureEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setFlagSecureEnabled(enabled)
        }
    }

    fun setAutoClearClipboard(enabled: Boolean) {
        scope.launch {
            settingsRepository.setAutoClearClipboard(enabled)
        }
    }

    fun setShowUsernameInList(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowUsernameInList(enabled)
        }
    }

    fun setShowOtpInList(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowOtpInList(enabled)
        }
    }

    fun setShowPasskeyBadge(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowPasskeyBadge(enabled)
        }
    }

    fun setShowUrlInList(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowUrlInList(enabled)
        }
    }

    fun setHideFabOnScroll(enabled: Boolean) {
        scope.launch {
            settingsRepository.setHideFabOnScroll(enabled)
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        scope.launch {
            settingsRepository.setHapticFeedbackEnabled(enabled)
        }
    }

    fun setSyncOnColdStart(enabled: Boolean) {
        scope.launch {
            settingsRepository.setSyncOnColdStart(enabled)
        }
    }

    fun setShowAuthenticatorTab(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowAuthenticatorTab(enabled)
        }
    }

    fun setShowGeneratorTab(enabled: Boolean) {
        scope.launch {
            settingsRepository.setShowGeneratorTab(enabled)
        }
    }

    // ========== KP2A 扩展：调试日志（真实进程内缓冲） ==========
    fun refreshDebugLogs() {
        debugLogLinesFlow.value = debugLogBuffer.snapshot()
    }

    fun clearDebugLogs() {
        debugLogBuffer.clear()
        debugLogLinesFlow.value = emptyList()
    }
}

/** 自动填充启用开关的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class AutofillUiState(
    val credentialProviderEnabled: Boolean,
    val passkeySupportEnabled: Boolean,
    val autofillServiceEnabled: Boolean
)

/** 密码库配置的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class DatabaseConfigUiState(
    val databaseName: String,
    val defaultUsername: String,
    /** 文件路径（ISSUE-P3-59：取自活动库记录；无活动库时为空，UI 显示「未设置」占位） */
    val databasePath: String = "",
    val encryptionAlgorithm: String,
    val kdfAlgorithm: String,
    val argon2Iterations: Long,
    val argon2MemoryMb: Long,
    val argon2Parallelism: Int,
    /** 压缩算法显示值（ISSUE-P2-19：真实值来自文件头 compressionFlags） */
    val compressionAlgorithm: String = "",
    val recycleBinEnabled: Boolean
)

/** 安全超时配置的局部投影（原 `SettingsViewModel` 私有嵌套类型，ISSUE-P3-29 上移为同包 internal） */
internal data class SecurityTimeoutUiState(
    val autoLockTimeoutSeconds: Int
)
