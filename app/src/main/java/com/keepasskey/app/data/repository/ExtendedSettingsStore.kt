package com.keepasskey.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.settings.IconSetOption
import com.keepasskey.app.ui.screens.settings.ListDensity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进阶偏好持久化仓库（TASK-12 整改）。
 *
 * 此前 `SettingsViewModel` 的 `ExtendedSettings` 约 35 个开关为纯内存回显：
 * 每次进程重启全部静默回落默认值，用户已做出的偏好选择被无声丢弃。
 * 本仓库以 SharedPreferences 持久化全部字段（整体读、整体写），未写入的键
 * 回落到 [ExtendedSettings] 默认值；`appContext` 为 null（纯 JVM 单元测试注入）时
 * 退化为不持久化的内存语义，不破坏可测性。
 *
 * 生命周期决策（TASK-04）：`RealSettingsRepository` 已迁移 Preferences DataStore；
 * 本仓库仍沿用 SharedPreferences——其同步 load/save API 与「null 上下文注入 JVM 单测」
 * 的可测性设计强绑定，DataStore 化需将全部消费方（SettingsViewModel/控制器/调度器）
 * 改为异步语义，风险大于收益，后续如 DataStore 化须整体评估。
 */
@Singleton
class ExtendedSettingsStore @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 进程级唯一内存权威快照（ISSUE-P2-21 整改）。
     *
     * 此前各 ViewModel 作用域的控制器各自 `MutableStateFlow(store.load())`：设置二级页
     * （导航域 ViewModel）改动偏好后，活动域 ViewModel（KeePasskeyApp 宿主）的内存快照
     * **不会更新**——「返回键锁定」等依赖宿主域读值的特性因此读到的永远是旧值。
     * 快照上移到 @Singleton 后，任一实例的写入对所有读者即时可见（XML 落盘仍由
     * [save] 统一执行）。
     */
    private val settingsFlow = MutableStateFlow(load())

    /** 进程内共享的进阶偏好快照（读侧唯一来源） */
    val settings: StateFlow<ExtendedSettings> = settingsFlow.asStateFlow()

    /** 更新共享快照（不改持久化；持久化请走 [save]） */
    fun publish(settings: ExtendedSettings) {
        settingsFlow.value = settings
    }

    /** 读出全部持久化偏好（含 wifiOnlySync 独立键）；无持久化层时返回默认值 */
    fun load(): ExtendedSettings {
        val p = prefs ?: return ExtendedSettings()
        val defaults = ExtendedSettings()
        return ExtendedSettings(
            // 文件处理与进阶同步
            useOfflineCache = p.getBoolean(K_USE_OFFLINE_CACHE, defaults.useOfflineCache),
            periodicBackgroundSyncEnabled = p.getBoolean(
                K_PERIODIC_SYNC_ENABLED, defaults.periodicBackgroundSyncEnabled
            ),
            periodicBackgroundSyncIntervalMinutes = p.getInt(
                K_PERIODIC_SYNC_INTERVAL, defaults.periodicBackgroundSyncIntervalMinutes
            ),
            autoSyncEnabled = p.getBoolean(K_AUTO_SYNC_ENABLED, defaults.autoSyncEnabled),
            createBackupBeforeSave = p.getBoolean(
                K_CREATE_BACKUP_BEFORE_SAVE, defaults.createBackupBeforeSave
            ),
            checkRemoteChangesBeforeSave = p.getBoolean(
                K_CHECK_REMOTE_CHANGES_BEFORE_SAVE, defaults.checkRemoteChangesBeforeSave
            ),
            conflictResolution = enumOrDefault(
                p.getString(K_CONFLICT_RESOLUTION, null), defaults.conflictResolution
            ),
            webdavChunkedUpload = p.getBoolean(K_WEBDAV_CHUNKED_UPLOAD, defaults.webdavChunkedUpload),
            webdavChunkSizeMb = p.getInt(K_WEBDAV_CHUNK_SIZE_MB, defaults.webdavChunkSizeMb),

            // 安全锁定规则与环境
            lockWhenScreenOff = p.getBoolean(K_LOCK_WHEN_SCREEN_OFF, defaults.lockWhenScreenOff),
            lockWhenNavigateBack = p.getBoolean(K_LOCK_WHEN_NAVIGATE_BACK, defaults.lockWhenNavigateBack),
            clearPasswordOnLeave = p.getBoolean(K_CLEAR_PASSWORD_ON_LEAVE, defaults.clearPasswordOnLeave),
            rememberRecentFiles = p.getBoolean(K_REMEMBER_RECENT_FILES, defaults.rememberRecentFiles),
            rememberKeyFileLocation = p.getBoolean(
                K_REMEMBER_KEY_FILE_LOCATION, defaults.rememberKeyFileLocation
            ),
            showKillAppOption = p.getBoolean(K_SHOW_KILL_APP_OPTION, defaults.showKillAppOption),

            // 自动填充进阶
            offerSaveCredentials = p.getBoolean(K_OFFER_SAVE_CREDENTIALS, defaults.offerSaveCredentials),
            inlineSuggestionsEnabled = p.getBoolean(
                K_INLINE_SUGGESTIONS_ENABLED, defaults.inlineSuggestionsEnabled
            ),
            autoReturnFromQuery = p.getBoolean(K_AUTO_RETURN_FROM_QUERY, defaults.autoReturnFromQuery),
            autofillCopyTotp = p.getBoolean(K_AUTOFILL_COPY_TOTP, defaults.autofillCopyTotp),
            autofillShowTotpNotification = p.getBoolean(
                K_AUTOFILL_SHOW_TOTP_NOTIFICATION, defaults.autofillShowTotpNotification
            ),
            skipDalVerification = p.getBoolean(K_SKIP_DAL_VERIFICATION, defaults.skipDalVerification),
            overrideNoAutofill = p.getBoolean(K_OVERRIDE_NO_AUTOFILL, defaults.overrideNoAutofill),
            autofillSessionGrantEnabled = p.getBoolean(
                K_AUTOFILL_SESSION_GRANT, defaults.autofillSessionGrantEnabled
            ),
            // ISSUE-P2-228：三条通道总开关自内存态迁入持久化（迁移前无任何持久化键）
            credentialProviderEnabled = p.getBoolean(
                K_CREDENTIAL_PROVIDER_ENABLED, defaults.credentialProviderEnabled
            ),
            passkeySupportEnabled = p.getBoolean(
                K_PASSKEY_SUPPORT_ENABLED, defaults.passkeySupportEnabled
            ),
            autofillServiceEnabled = p.getBoolean(
                K_AUTOFILL_SERVICE_ENABLED, defaults.autofillServiceEnabled
            ),
            // TASK-44：自动填充黑名单改由 AutofillBlocklistStore 持久化真实包名条目，
            // 原 disabledAutofillQueriesCount（无写入方计数）及其持久化键一并下架

            // 显示与视觉进阶
            maskPasswordsDefault = p.getBoolean(K_MASK_PASSWORDS_DEFAULT, defaults.maskPasswordsDefault),
            maskTotpDefault = p.getBoolean(K_MASK_TOTP_DEFAULT, defaults.maskTotpDefault),
            showUnlockedNotification = p.getBoolean(
                K_SHOW_UNLOCKED_NOTIFICATION, defaults.showUnlockedNotification
            ),
            showGroupInSearchResult = p.getBoolean(
                K_SHOW_GROUP_IN_SEARCH_RESULT, defaults.showGroupInSearchResult
            ),
            showGroupInEntry = p.getBoolean(K_SHOW_GROUP_IN_ENTRY, defaults.showGroupInEntry),
            listDensity = enumOrDefault(p.getString(K_LIST_DENSITY, null), defaults.listDensity),
            autoActivateSearchOnOpen = p.getBoolean(
                K_AUTO_ACTIVATE_SEARCH_ON_OPEN, defaults.autoActivateSearchOnOpen
            ),
            iconSet = enumOrDefault(p.getString(K_ICON_SET, null), defaults.iconSet),

            // TOTP 规范字段映射
            totpSeedFieldName = p.getString(K_TOTP_SEED_FIELD_NAME, null)
                ?: defaults.totpSeedFieldName,
            totpSettingsFieldName = p.getString(K_TOTP_SETTINGS_FIELD_NAME, null)
                ?: defaults.totpSettingsFieldName,
            defaultTotpStepSeconds = p.getInt(
                K_DEFAULT_TOTP_STEP_SECONDS, defaults.defaultTotpStepSeconds
            ),
            defaultTotpDigits = p.getInt(K_DEFAULT_TOTP_DIGITS, defaults.defaultTotpDigits),

            // TASK-47：已泄露密码检测开关（默认关闭，未持久化时回落 false）
            breachCheckEnabled = p.getBoolean(K_BREACH_CHECK_ENABLED, defaults.breachCheckEnabled),

            // 调试日志
            debugLogEnabled = p.getBoolean(K_DEBUG_LOG_ENABLED, defaults.debugLogEnabled),
            verboseSyncLog = p.getBoolean(K_VERBOSE_SYNC_LOG, defaults.verboseSyncLog)
        )
    }

    /** 整体写入全部偏好（批量 apply） */
    fun save(settings: ExtendedSettings) {
        val p = prefs ?: return
        p.edit()
            .putBoolean(K_USE_OFFLINE_CACHE, settings.useOfflineCache)
            .putBoolean(K_PERIODIC_SYNC_ENABLED, settings.periodicBackgroundSyncEnabled)
            .putInt(K_PERIODIC_SYNC_INTERVAL, settings.periodicBackgroundSyncIntervalMinutes)
            .putBoolean(K_AUTO_SYNC_ENABLED, settings.autoSyncEnabled)
            .putBoolean(K_CREATE_BACKUP_BEFORE_SAVE, settings.createBackupBeforeSave)
            .putBoolean(K_CHECK_REMOTE_CHANGES_BEFORE_SAVE, settings.checkRemoteChangesBeforeSave)
            .putString(K_CONFLICT_RESOLUTION, settings.conflictResolution.name)
            .putBoolean(K_WEBDAV_CHUNKED_UPLOAD, settings.webdavChunkedUpload)
            .putInt(K_WEBDAV_CHUNK_SIZE_MB, settings.webdavChunkSizeMb)
            .putBoolean(K_LOCK_WHEN_SCREEN_OFF, settings.lockWhenScreenOff)
            .putBoolean(K_LOCK_WHEN_NAVIGATE_BACK, settings.lockWhenNavigateBack)
            .putBoolean(K_CLEAR_PASSWORD_ON_LEAVE, settings.clearPasswordOnLeave)
            .putBoolean(K_REMEMBER_RECENT_FILES, settings.rememberRecentFiles)
            .putBoolean(K_REMEMBER_KEY_FILE_LOCATION, settings.rememberKeyFileLocation)
            .putBoolean(K_SHOW_KILL_APP_OPTION, settings.showKillAppOption)
            .putBoolean(K_OFFER_SAVE_CREDENTIALS, settings.offerSaveCredentials)
            .putBoolean(K_INLINE_SUGGESTIONS_ENABLED, settings.inlineSuggestionsEnabled)
            .putBoolean(K_AUTO_RETURN_FROM_QUERY, settings.autoReturnFromQuery)
            .putBoolean(K_AUTOFILL_COPY_TOTP, settings.autofillCopyTotp)
            .putBoolean(K_AUTOFILL_SHOW_TOTP_NOTIFICATION, settings.autofillShowTotpNotification)
            .putBoolean(K_SKIP_DAL_VERIFICATION, settings.skipDalVerification)
            .putBoolean(K_OVERRIDE_NO_AUTOFILL, settings.overrideNoAutofill)
            .putBoolean(K_AUTOFILL_SESSION_GRANT, settings.autofillSessionGrantEnabled)
            .putBoolean(K_CREDENTIAL_PROVIDER_ENABLED, settings.credentialProviderEnabled)
            .putBoolean(K_PASSKEY_SUPPORT_ENABLED, settings.passkeySupportEnabled)
            .putBoolean(K_AUTOFILL_SERVICE_ENABLED, settings.autofillServiceEnabled)
            .putBoolean(K_MASK_PASSWORDS_DEFAULT, settings.maskPasswordsDefault)
            .putBoolean(K_MASK_TOTP_DEFAULT, settings.maskTotpDefault)
            .putBoolean(K_SHOW_UNLOCKED_NOTIFICATION, settings.showUnlockedNotification)
            .putBoolean(K_SHOW_GROUP_IN_SEARCH_RESULT, settings.showGroupInSearchResult)
            .putBoolean(K_SHOW_GROUP_IN_ENTRY, settings.showGroupInEntry)
            .putString(K_LIST_DENSITY, settings.listDensity.name)
            .putBoolean(K_AUTO_ACTIVATE_SEARCH_ON_OPEN, settings.autoActivateSearchOnOpen)
            .putString(K_ICON_SET, settings.iconSet.name)
            .putString(K_TOTP_SEED_FIELD_NAME, settings.totpSeedFieldName)
            .putString(K_TOTP_SETTINGS_FIELD_NAME, settings.totpSettingsFieldName)
            .putInt(K_DEFAULT_TOTP_STEP_SECONDS, settings.defaultTotpStepSeconds)
            .putInt(K_DEFAULT_TOTP_DIGITS, settings.defaultTotpDigits)
            .putBoolean(K_BREACH_CHECK_ENABLED, settings.breachCheckEnabled)
            .putBoolean(K_DEBUG_LOG_ENABLED, settings.debugLogEnabled)
            .putBoolean(K_VERBOSE_SYNC_LOG, settings.verboseSyncLog)
            .apply()
    }

    /**
     * ISSUE-P3-03 (43f)：诊断日志开关单键读取。
     *
     * 供 [com.keepasskey.app.data.logger.DiagnosticLogGate] 在每次日志写入时求值，
     * 因此只读单个布尔键，不做全字段反序列化（避免为一行日志构造整个偏好对象）。
     * 无持久化层（纯 JVM 单测注入 null 上下文）时按关闭处理，与默认值一致。
     */
    fun isDiagnosticLogEnabled(): Boolean =
        prefs?.getBoolean(K_DEBUG_LOG_ENABLED, false) ?: false

    /**
     * ISSUE-P3-03 (43f)：详细同步日志开关单键读取（同 [isDiagnosticLogEnabled] 的轻量语义）。
     * 与诊断日志开关相互独立：详细模式只追加同步过程细节，不解除普通诊断事件的记录约束。
     */
    fun isVerboseSyncLogEnabled(): Boolean =
        prefs?.getBoolean(K_VERBOSE_SYNC_LOG, false) ?: false

    /**
     * ISSUE-P3-03 (43b)：自动填充内联建议开关单键读取（供自动填充服务高频判定）。
     *
     * ISSUE-P2-71：兜底值由 `true` 改为 `false`——同 [isAutofillCopyTotpEnabled]，
     * 硬编码缺省会让数据类默认值的修改在「无持久化层 / 键缺失」路径失效。
     */
    fun isInlineSuggestionsEnabled(): Boolean =
        prefs?.getBoolean(K_INLINE_SUGGESTIONS_ENABLED, false) ?: false

    /**
     * ISSUE-P3-03 (43b)：填充后复制 TOTP 开关单键读取。
     *
     * ISSUE-P2-43：兜底值由 `true` 改为 `false`——该方法自带硬编码缺省（不读
     * [ExtendedSettings] 默认值），此前仅改数据类默认会让「无持久化层 / 键缺失」路径
     * 继续按「开启」判定，等于默认关闭形同虚设。
     */
    fun isAutofillCopyTotpEnabled(): Boolean =
        prefs?.getBoolean(K_AUTOFILL_COPY_TOTP, false) ?: false

    /**
     * ISSUE-P3-42：会话授权宽限开关单键读取（默认关闭）。
     *
     * 供 [com.keepasskey.app.autofill.KeePasskeyAutofillService] 在每次填充请求时求值；
     * 关闭时服务端**根本不查询**授权存储，行为与既有「每次强制二次确认」完全一致。
     */
    fun isAutofillSessionGrantEnabled(): Boolean =
        prefs?.getBoolean(K_AUTOFILL_SESSION_GRANT, false) ?: false

    /**
     * 「凭据管理器 (Credential Manager)」通道总开关（ISSUE-P2-228，默认开启）。
     *
     * 消费方：[com.keepasskey.app.passkey.KeePasskeyCredentialProviderService] 在
     * `get` / `create` 两条入口各求值一次，关闭即返回**空响应**（无候选、无解锁引导、无保存入口）。
     * 系统仍会列出本应用为凭据提供方（注册与否由 Manifest 决定，应用内无法动态摘除），
     * 但不再交付任何凭据——这是本开关**能做到**的最强语义，UI 文案须如实表述，
     * 不得写「已从系统移除」。
     */
    fun isCredentialProviderEnabled(): Boolean =
        prefs?.getBoolean(K_CREDENTIAL_PROVIDER_ENABLED, CHANNEL_SWITCH_DEFAULT) ?: CHANNEL_SWITCH_DEFAULT

    /**
     * 「通行密钥 (Passkey)」支持开关（ISSUE-P2-228，默认开启）。
     *
     * 消费方：CM 通道只**过滤掉公钥类**请求与候选（注册请求不产 entry、已解锁检索不产
     * `PublicKeyCredentialEntry`），密码类填充与保存不受影响。
     */
    fun isPasskeySupportEnabled(): Boolean =
        prefs?.getBoolean(K_PASSKEY_SUPPORT_ENABLED, CHANNEL_SWITCH_DEFAULT) ?: CHANNEL_SWITCH_DEFAULT

    /**
     * 「系统自动填充服务 (`AutofillService`)」通道总开关（ISSUE-P2-228，默认开启）。
     *
     * 消费方：[com.keepasskey.app.autofill.KeePasskeyAutofillService] 的填充与保存两条路径
     * （经 `AutofillAccessPolicy` 的 `APP_DISABLED` 拒绝态，不下发解锁引导 / 数据集 / `SaveInfo`，
     * 也不落库）。与「系统是否已把本应用选为自动填充服务」是两件事——后者是真实系统状态，
     * 由 `AutofillHealthProbe` 呈现并可一键跳转系统设置。
     */
    fun isAutofillServiceEnabled(): Boolean =
        prefs?.getBoolean(K_AUTOFILL_SERVICE_ENABLED, CHANNEL_SWITCH_DEFAULT) ?: CHANNEL_SWITCH_DEFAULT

    /**
     * ISSUE-P3-43：是否覆盖页面的 `importantForAutofill=no` 标记（默认 false = 尊重页面标记）。
     *
     * 供 [com.keepasskey.app.autofill.KeePasskeyAutofillService] 在每次填充/保存请求时求值；
     * 默认值下，页面显式声明禁止自动填充的字段将被跳过（此前该开关无消费方，属假开关）。
     */
    fun isOverrideNoAutofillEnabled(): Boolean =
        prefs?.getBoolean(K_OVERRIDE_NO_AUTOFILL, false) ?: false

    /**
     * ISSUE-P3-44：是否保存自动填充捕获的新密码（默认 true）。
     *
     * 此前该偏好（`offerSaveCredentials`）只在设置页与持久化链路中流转、**无任何填充侧消费方**，
     * 属「假开关」——用户关闭后仍然照常落库。本方法为其提供真实判定入口。
     */
    fun isOfferSaveCredentialsEnabled(): Boolean =
        prefs?.getBoolean(K_OFFER_SAVE_CREDENTIALS, true) ?: true

    /** wifiOnlySync 属 SyncUiState 域（周期同步的网络约束消费方），以独立键持久化 */
    fun loadWifiOnlySync(): Boolean = prefs?.getBoolean(K_WIFI_ONLY_SYNC, true) ?: true

    fun saveWifiOnlySync(enabled: Boolean) {
        prefs?.edit()?.putBoolean(K_WIFI_ONLY_SYNC, enabled)?.apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        const val PREFS_NAME = "keepasskey_extended_settings"

        const val K_USE_OFFLINE_CACHE = "use_offline_cache"
        const val K_PERIODIC_SYNC_ENABLED = "periodic_sync_enabled"
        const val K_PERIODIC_SYNC_INTERVAL = "periodic_sync_interval_minutes"
        // ISSUE-P3-272：自动同步总开关（控制解锁后自动同步触发点）
        const val K_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        const val K_CREATE_BACKUP_BEFORE_SAVE = "create_backup_before_save"
        const val K_CHECK_REMOTE_CHANGES_BEFORE_SAVE = "check_remote_changes_before_save"
        const val K_CONFLICT_RESOLUTION = "conflict_resolution"
        const val K_WEBDAV_CHUNKED_UPLOAD = "webdav_chunked_upload"
        const val K_WEBDAV_CHUNK_SIZE_MB = "webdav_chunk_size_mb"
        const val K_LOCK_WHEN_SCREEN_OFF = "lock_when_screen_off"
        const val K_LOCK_WHEN_NAVIGATE_BACK = "lock_when_navigate_back"
        const val K_CLEAR_PASSWORD_ON_LEAVE = "clear_password_on_leave"
        const val K_REMEMBER_RECENT_FILES = "remember_recent_files"
        const val K_REMEMBER_KEY_FILE_LOCATION = "remember_key_file_location"
        const val K_SHOW_KILL_APP_OPTION = "show_kill_app_option"
        const val K_OFFER_SAVE_CREDENTIALS = "offer_save_credentials"
        const val K_INLINE_SUGGESTIONS_ENABLED = "inline_suggestions_enabled"
        const val K_AUTO_RETURN_FROM_QUERY = "auto_return_from_query"
        const val K_AUTOFILL_COPY_TOTP = "autofill_copy_totp"
        const val K_AUTOFILL_SHOW_TOTP_NOTIFICATION = "autofill_show_totp_notification"
        const val K_SKIP_DAL_VERIFICATION = "skip_dal_verification"
        const val K_OVERRIDE_NO_AUTOFILL = "override_no_autofill"
        /** ISSUE-P3-42：会话授权宽限开关（默认 false，未持久化时按关闭处理） */
        const val K_AUTOFILL_SESSION_GRANT = "autofill_session_grant_enabled"

        // ISSUE-P2-228：三条通道总开关（默认开启 = 与假开关时期的实际可观察行为一致）
        const val K_CREDENTIAL_PROVIDER_ENABLED = "credential_provider_enabled"
        const val K_PASSKEY_SUPPORT_ENABLED = "passkey_support_enabled"
        const val K_AUTOFILL_SERVICE_ENABLED = "autofill_service_enabled"

        /**
         * 三条通道总开关的**共同缺省值**（ISSUE-P2-228）。
         *
         * 单键读取路径不经过 [ExtendedSettings] 默认值，故此处显式收敛为一个常量：
         * ISSUE-P2-43 的教训即「只改数据类默认值会让『无持久化层 / 键缺失』路径仍按旧默认判定」。
         * 由 `AutofillChannelSwitchDefaultsTest` 锁定「数据类默认 ⇄ 本常量」两侧一致。
         */
        const val CHANNEL_SWITCH_DEFAULT = true
        const val K_MASK_PASSWORDS_DEFAULT = "mask_passwords_default"
        const val K_MASK_TOTP_DEFAULT = "mask_totp_default"
        const val K_SHOW_UNLOCKED_NOTIFICATION = "show_unlocked_notification"
        const val K_SHOW_GROUP_IN_SEARCH_RESULT = "show_group_in_search_result"
        const val K_SHOW_GROUP_IN_ENTRY = "show_group_in_entry"
        const val K_LIST_DENSITY = "list_density"
        const val K_AUTO_ACTIVATE_SEARCH_ON_OPEN = "auto_activate_search_on_open"
        const val K_ICON_SET = "icon_set"
        const val K_TOTP_SEED_FIELD_NAME = "totp_seed_field_name"
        const val K_TOTP_SETTINGS_FIELD_NAME = "totp_settings_field_name"
        const val K_DEFAULT_TOTP_STEP_SECONDS = "default_totp_step_seconds"
        const val K_DEFAULT_TOTP_DIGITS = "default_totp_digits"
        const val K_BREACH_CHECK_ENABLED = "breach_check_enabled"
        const val K_DEBUG_LOG_ENABLED = "debug_log_enabled"
        const val K_VERBOSE_SYNC_LOG = "verbose_sync_log"
        const val K_WIFI_ONLY_SYNC = "wifi_only_sync"
    }
}
