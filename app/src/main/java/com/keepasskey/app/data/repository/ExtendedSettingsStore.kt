package com.keepasskey.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.settings.IconSetOption
import com.keepasskey.app.ui.screens.settings.ListDensity
import dagger.hilt.android.qualifiers.ApplicationContext
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
            allowedWifiSsids = p.getString(K_ALLOWED_WIFI_SSIDS, null) ?: defaults.allowedWifiSsids,
            createBackupBeforeSave = p.getBoolean(
                K_CREATE_BACKUP_BEFORE_SAVE, defaults.createBackupBeforeSave
            ),
            checkRemoteChangesBeforeSave = p.getBoolean(
                K_CHECK_REMOTE_CHANGES_BEFORE_SAVE, defaults.checkRemoteChangesBeforeSave
            ),
            conflictResolution = enumOrDefault(
                p.getString(K_CONFLICT_RESOLUTION, null), defaults.conflictResolution
            ),
            useFileTransactions = p.getBoolean(K_USE_FILE_TRANSACTIONS, defaults.useFileTransactions),
            webdavChunkedUpload = p.getBoolean(K_WEBDAV_CHUNKED_UPLOAD, defaults.webdavChunkedUpload),
            webdavChunkSizeMb = p.getInt(K_WEBDAV_CHUNK_SIZE_MB, defaults.webdavChunkSizeMb),
            preloadDatabaseEnabled = p.getBoolean(K_PRELOAD_DATABASE, defaults.preloadDatabaseEnabled),

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
            .putString(K_ALLOWED_WIFI_SSIDS, settings.allowedWifiSsids)
            .putBoolean(K_CREATE_BACKUP_BEFORE_SAVE, settings.createBackupBeforeSave)
            .putBoolean(K_CHECK_REMOTE_CHANGES_BEFORE_SAVE, settings.checkRemoteChangesBeforeSave)
            .putString(K_CONFLICT_RESOLUTION, settings.conflictResolution.name)
            .putBoolean(K_USE_FILE_TRANSACTIONS, settings.useFileTransactions)
            .putBoolean(K_WEBDAV_CHUNKED_UPLOAD, settings.webdavChunkedUpload)
            .putInt(K_WEBDAV_CHUNK_SIZE_MB, settings.webdavChunkSizeMb)
            .putBoolean(K_PRELOAD_DATABASE, settings.preloadDatabaseEnabled)
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
        const val K_ALLOWED_WIFI_SSIDS = "allowed_wifi_ssids"
        const val K_CREATE_BACKUP_BEFORE_SAVE = "create_backup_before_save"
        const val K_CHECK_REMOTE_CHANGES_BEFORE_SAVE = "check_remote_changes_before_save"
        const val K_CONFLICT_RESOLUTION = "conflict_resolution"
        const val K_USE_FILE_TRANSACTIONS = "use_file_transactions"
        const val K_WEBDAV_CHUNKED_UPLOAD = "webdav_chunked_upload"
        const val K_WEBDAV_CHUNK_SIZE_MB = "webdav_chunk_size_mb"
        const val K_PRELOAD_DATABASE = "preload_database_enabled"
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
