package com.keepasskey.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级单例 DataStore（TASK-04 整改：自 SharedPreferences 迁移 Preferences DataStore）。
 */
private val Context.keepasskeySettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "keepasskey_settings"
)

/**
 * 生产环境持久化设置仓库实现（L3 整改；TASK-04 迁移 Preferences DataStore）。
 *
 * 持久化全部安全与外观设置，冷启动不丢失；未写入的键回落到 [UserSettings]
 * 安全默认值（安全项默认开启）。相较 SharedPreferences：事务性原子写、
 * 无 ANR 风险的协程友好 API、Flow 原生变更通知。
 */
@Singleton
class RealSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val migrationMutex = Mutex()

    @Volatile
    private var legacyMigrationDone = false

    override fun getSettings(): Flow<UserSettings> = flow {
        migrateFromLegacySharedPreferencesOnce()
        emitAll(context.keepasskeySettingsStore.data)
    }.map { prefs -> loadSettings(prefs) }
        .distinctUntilChanged()

    /**
     * 一次性迁移（TASK-04）：首次收集设置流时，把旧 SharedPreferences
     * `keepasskey_settings` 中的全部键值写入 DataStore 后删除旧文件，
     * 已落盘的用户偏好（主题/安全/列表显示等）冷启动无缝延续。
     * 经 [Mutex] 保证并发收集下迁移体恰好执行一次。
     */
    private suspend fun migrateFromLegacySharedPreferencesOnce() {
        migrationMutex.withLock {
            if (legacyMigrationDone) return
            withContext(Dispatchers.IO) {
                val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val entries = legacy.all
                if (entries.isNotEmpty()) {
                    context.keepasskeySettingsStore.edit { prefs ->
                        for ((key, value) in entries) {
                            // 本仓库历史键值仅含 Boolean/Int/String 三型
                            when (value) {
                                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                                is Int -> prefs[intPreferencesKey(key)] = value
                                is String -> prefs[stringPreferencesKey(key)] = value
                            }
                        }
                    }
                }
                context.deleteSharedPreferences(LEGACY_PREFS_NAME)
            }
            legacyMigrationDone = true
        }
    }

    private fun loadSettings(prefs: Preferences): UserSettings = UserSettings(
        themeMode = enumValueOrDefault(prefs[KEY_THEME_MODE], AppThemeMode.SYSTEM),
        themePalette = enumValueOrDefault(prefs[KEY_THEME_PALETTE], AppThemePalette.SAPPHIRE),
        oledBlackOptimization = prefs[KEY_OLED_BLACK] ?: false,
        dynamicColorEnabled = prefs[KEY_DYNAMIC_COLOR] ?: false,
        appLanguage = enumValueOrDefault(prefs[KEY_APP_LANGUAGE], AppLanguage.SYSTEM),
        biometricEnabled = prefs[KEY_BIOMETRIC_ENABLED] ?: true,
        autoLockBackground = prefs[KEY_AUTO_LOCK_BACKGROUND] ?: true,
        autoLockTimeoutSeconds = prefs[KEY_AUTO_LOCK_TIMEOUT] ?: 60,
        lockWhenScreenOff = prefs[KEY_LOCK_WHEN_SCREEN_OFF] ?: true,
        flagSecureEnabled = prefs[KEY_FLAG_SECURE] ?: true,
        autoClearClipboard = prefs[KEY_AUTO_CLEAR_CLIPBOARD] ?: true,
        showUsernameInList = prefs[KEY_SHOW_USERNAME_IN_LIST] ?: true,
        showOtpInList = prefs[KEY_SHOW_OTP_IN_LIST] ?: true,
        showPasskeyBadge = prefs[KEY_SHOW_PASSKEY_BADGE] ?: true,
        showUrlInList = prefs[KEY_SHOW_URL_IN_LIST] ?: true,
        hideFabOnScroll = prefs[KEY_HIDE_FAB_ON_SCROLL] ?: false,
        hapticFeedbackEnabled = prefs[KEY_HAPTIC_FEEDBACK] ?: true,
        clipboardTimeoutSeconds = prefs[KEY_CLIPBOARD_TIMEOUT] ?: 30,
        syncOnColdStart = prefs[KEY_SYNC_ON_COLD_START] ?: true,
        showAuthenticatorTab = prefs[KEY_SHOW_AUTHENTICATOR_TAB] ?: true,
        showGeneratorTab = prefs[KEY_SHOW_GENERATOR_TAB] ?: true
    )

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.keepasskeySettingsStore.edit(block)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    override suspend fun setThemeMode(themeMode: AppThemeMode) =
        edit { it[KEY_THEME_MODE] = themeMode.name }

    override suspend fun setThemePalette(themePalette: AppThemePalette) =
        edit { it[KEY_THEME_PALETTE] = themePalette.name }

    override suspend fun setOledBlackOptimization(enabled: Boolean) =
        edit { it[KEY_OLED_BLACK] = enabled }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) =
        edit { it[KEY_DYNAMIC_COLOR] = enabled }

    override suspend fun setAppLanguage(language: AppLanguage) =
        edit { it[KEY_APP_LANGUAGE] = language.name }

    override suspend fun setBiometricEnabled(enabled: Boolean) =
        edit { it[KEY_BIOMETRIC_ENABLED] = enabled }

    override suspend fun setAutoLockBackground(enabled: Boolean) =
        edit { it[KEY_AUTO_LOCK_BACKGROUND] = enabled }

    override suspend fun setAutoLockTimeoutSeconds(seconds: Int) =
        edit { it[KEY_AUTO_LOCK_TIMEOUT] = seconds }

    override suspend fun setLockWhenScreenOff(enabled: Boolean) =
        edit { it[KEY_LOCK_WHEN_SCREEN_OFF] = enabled }

    override suspend fun setFlagSecureEnabled(enabled: Boolean) =
        edit { it[KEY_FLAG_SECURE] = enabled }

    override suspend fun setAutoClearClipboard(enabled: Boolean) =
        edit { it[KEY_AUTO_CLEAR_CLIPBOARD] = enabled }

    override suspend fun setShowUsernameInList(enabled: Boolean) =
        edit { it[KEY_SHOW_USERNAME_IN_LIST] = enabled }

    override suspend fun setShowOtpInList(enabled: Boolean) =
        edit { it[KEY_SHOW_OTP_IN_LIST] = enabled }

    override suspend fun setShowPasskeyBadge(enabled: Boolean) =
        edit { it[KEY_SHOW_PASSKEY_BADGE] = enabled }

    override suspend fun setShowUrlInList(enabled: Boolean) =
        edit { it[KEY_SHOW_URL_IN_LIST] = enabled }

    override suspend fun setHideFabOnScroll(enabled: Boolean) =
        edit { it[KEY_HIDE_FAB_ON_SCROLL] = enabled }

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) =
        edit { it[KEY_HAPTIC_FEEDBACK] = enabled }

    override suspend fun setClipboardTimeout(seconds: Int) =
        edit { it[KEY_CLIPBOARD_TIMEOUT] = seconds }

    override suspend fun setSyncOnColdStart(enabled: Boolean) =
        edit { it[KEY_SYNC_ON_COLD_START] = enabled }

    override suspend fun setShowAuthenticatorTab(enabled: Boolean) =
        edit { it[KEY_SHOW_AUTHENTICATOR_TAB] = enabled }

    override suspend fun setShowGeneratorTab(enabled: Boolean) =
        edit { it[KEY_SHOW_GENERATOR_TAB] = enabled }

    private companion object {
        private const val LEGACY_PREFS_NAME = "keepasskey_settings"

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        private val KEY_OLED_BLACK = booleanPreferencesKey("oled_black_optimization")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        private val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        private val KEY_AUTO_LOCK_BACKGROUND = booleanPreferencesKey("auto_lock_background")
        private val KEY_AUTO_LOCK_TIMEOUT = intPreferencesKey("auto_lock_timeout_seconds")
        private val KEY_LOCK_WHEN_SCREEN_OFF = booleanPreferencesKey("lock_when_screen_off")
        private val KEY_FLAG_SECURE = booleanPreferencesKey("flag_secure_enabled")
        private val KEY_AUTO_CLEAR_CLIPBOARD = booleanPreferencesKey("auto_clear_clipboard")
        private val KEY_SHOW_USERNAME_IN_LIST = booleanPreferencesKey("show_username_in_list")
        private val KEY_SHOW_OTP_IN_LIST = booleanPreferencesKey("show_otp_in_list")
        private val KEY_SHOW_PASSKEY_BADGE = booleanPreferencesKey("show_passkey_badge")
        private val KEY_SHOW_URL_IN_LIST = booleanPreferencesKey("show_url_in_list")
        private val KEY_HIDE_FAB_ON_SCROLL = booleanPreferencesKey("hide_fab_on_scroll")
        private val KEY_HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")
        private val KEY_CLIPBOARD_TIMEOUT = intPreferencesKey("clipboard_timeout_seconds")
        private val KEY_SYNC_ON_COLD_START = booleanPreferencesKey("sync_on_cold_start")
        private val KEY_SHOW_AUTHENTICATOR_TAB = booleanPreferencesKey("show_authenticator_tab")
        private val KEY_SHOW_GENERATOR_TAB = booleanPreferencesKey("show_generator_tab")
    }
}
