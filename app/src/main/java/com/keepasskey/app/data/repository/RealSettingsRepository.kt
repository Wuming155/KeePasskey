package com.keepasskey.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.ui.theme.AppThemeMode
import com.keepasskey.app.ui.theme.AppThemePalette
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 生产环境持久化设置仓库实现（L3 整改）。
 * 基于 SharedPreferences 持久化全部安全与外观设置，冷启动不丢失；
 * 未写入的键回落到 [UserSettings] 安全默认值（安全项默认开启）。
 */
@Singleton
class RealSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : SettingsRepository {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getSettings(): Flow<UserSettings> = callbackFlow {
        fun emitCurrent() {
            trySend(loadSettings())
        }
        emitCurrent()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            emitCurrent()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun loadSettings(): UserSettings = UserSettings(
        themeMode = enumValueOrDefault(prefs.getString(KEY_THEME_MODE, null), AppThemeMode.SYSTEM),
        themePalette = enumValueOrDefault(prefs.getString(KEY_THEME_PALETTE, null), AppThemePalette.SAPPHIRE),
        oledBlackOptimization = prefs.getBoolean(KEY_OLED_BLACK, false),
        dynamicColorEnabled = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
        appLanguage = enumValueOrDefault(prefs.getString(KEY_APP_LANGUAGE, null), AppLanguage.SYSTEM),
        biometricEnabled = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true),
        autoLockBackground = prefs.getBoolean(KEY_AUTO_LOCK_BACKGROUND, true),
        autoLockTimeoutSeconds = prefs.getInt(KEY_AUTO_LOCK_TIMEOUT, 60),
        lockWhenScreenOff = prefs.getBoolean(KEY_LOCK_WHEN_SCREEN_OFF, true),
        flagSecureEnabled = prefs.getBoolean(KEY_FLAG_SECURE, true),
        autoClearClipboard = prefs.getBoolean(KEY_AUTO_CLEAR_CLIPBOARD, true),
        showUsernameInList = prefs.getBoolean(KEY_SHOW_USERNAME_IN_LIST, true),
        showOtpInList = prefs.getBoolean(KEY_SHOW_OTP_IN_LIST, true),
        showPasskeyBadge = prefs.getBoolean(KEY_SHOW_PASSKEY_BADGE, true),
        showUrlInList = prefs.getBoolean(KEY_SHOW_URL_IN_LIST, true),
        hideFabOnScroll = prefs.getBoolean(KEY_HIDE_FAB_ON_SCROLL, false),
        hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true),
        clipboardTimeoutSeconds = prefs.getInt(KEY_CLIPBOARD_TIMEOUT, 30),
        syncOnColdStart = prefs.getBoolean(KEY_SYNC_ON_COLD_START, true),
        showAuthenticatorTab = prefs.getBoolean(KEY_SHOW_AUTHENTICATOR_TAB, true),
        showGeneratorTab = prefs.getBoolean(KEY_SHOW_GENERATOR_TAB, true)
    )

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs.edit()
        block(editor)
        editor.apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    override suspend fun setThemeMode(themeMode: AppThemeMode) =
        edit { it.putString(KEY_THEME_MODE, themeMode.name) }

    override suspend fun setThemePalette(themePalette: AppThemePalette) =
        edit { it.putString(KEY_THEME_PALETTE, themePalette.name) }

    override suspend fun setOledBlackOptimization(enabled: Boolean) =
        edit { it.putBoolean(KEY_OLED_BLACK, enabled) }

    override suspend fun setDynamicColorEnabled(enabled: Boolean) =
        edit { it.putBoolean(KEY_DYNAMIC_COLOR, enabled) }

    override suspend fun setAppLanguage(language: AppLanguage) =
        edit { it.putString(KEY_APP_LANGUAGE, language.name) }

    override suspend fun setBiometricEnabled(enabled: Boolean) =
        edit { it.putBoolean(KEY_BIOMETRIC_ENABLED, enabled) }

    override suspend fun setAutoLockBackground(enabled: Boolean) =
        edit { it.putBoolean(KEY_AUTO_LOCK_BACKGROUND, enabled) }

    override suspend fun setAutoLockTimeoutSeconds(seconds: Int) =
        edit { it.putInt(KEY_AUTO_LOCK_TIMEOUT, seconds) }

    override suspend fun setLockWhenScreenOff(enabled: Boolean) =
        edit { it.putBoolean(KEY_LOCK_WHEN_SCREEN_OFF, enabled) }

    override suspend fun setFlagSecureEnabled(enabled: Boolean) =
        edit { it.putBoolean(KEY_FLAG_SECURE, enabled) }

    override suspend fun setAutoClearClipboard(enabled: Boolean) =
        edit { it.putBoolean(KEY_AUTO_CLEAR_CLIPBOARD, enabled) }

    override suspend fun setShowUsernameInList(enabled: Boolean) =
        edit { it.putBoolean(KEY_SHOW_USERNAME_IN_LIST, enabled) }

    override suspend fun setShowOtpInList(enabled: Boolean) =
        edit { it.putBoolean(KEY_SHOW_OTP_IN_LIST, enabled) }

    override suspend fun setShowPasskeyBadge(enabled: Boolean) =
        edit { it.putBoolean(KEY_SHOW_PASSKEY_BADGE, enabled) }

    override suspend fun setShowUrlInList(enabled: Boolean) =
        edit { it.putBoolean(KEY_SHOW_URL_IN_LIST, enabled) }

    override suspend fun setHideFabOnScroll(enabled: Boolean) =
        edit { it.putBoolean(KEY_HIDE_FAB_ON_SCROLL, enabled) }

    override suspend fun setHapticFeedbackEnabled(enabled: Boolean) =
        edit { it.putBoolean(KEY_HAPTIC_FEEDBACK, enabled) }

    override suspend fun setClipboardTimeout(seconds: Int) =
        edit { it.putInt(KEY_CLIPBOARD_TIMEOUT, seconds) }

    override suspend fun setSyncOnColdStart(enabled: Boolean) =
        edit { it.putBoolean(KEY_SYNC_ON_COLD_START, enabled) }

    override suspend fun setShowAuthenticatorTab(enabled: Boolean) =
        edit { it.putBoolean(KEY_SHOW_AUTHENTICATOR_TAB, enabled) }

    override suspend fun setShowGeneratorTab(enabled: Boolean) =
        edit { it.putBoolean(KEY_SHOW_GENERATOR_TAB, enabled) }

    companion object {
        private const val PREFS_NAME = "keepasskey_settings"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_PALETTE = "theme_palette"
        private const val KEY_OLED_BLACK = "oled_black_optimization"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color_enabled"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_AUTO_LOCK_BACKGROUND = "auto_lock_background"
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout_seconds"
        private const val KEY_LOCK_WHEN_SCREEN_OFF = "lock_when_screen_off"
        private const val KEY_FLAG_SECURE = "flag_secure_enabled"
        private const val KEY_AUTO_CLEAR_CLIPBOARD = "auto_clear_clipboard"
        private const val KEY_SHOW_USERNAME_IN_LIST = "show_username_in_list"
        private const val KEY_SHOW_OTP_IN_LIST = "show_otp_in_list"
        private const val KEY_SHOW_PASSKEY_BADGE = "show_passkey_badge"
        private const val KEY_SHOW_URL_IN_LIST = "show_url_in_list"
        private const val KEY_HIDE_FAB_ON_SCROLL = "hide_fab_on_scroll"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback_enabled"
        private const val KEY_CLIPBOARD_TIMEOUT = "clipboard_timeout_seconds"
        private const val KEY_SYNC_ON_COLD_START = "sync_on_cold_start"
        private const val KEY_SHOW_AUTHENTICATOR_TAB = "show_authenticator_tab"
        private const val KEY_SHOW_GENERATOR_TAB = "show_generator_tab"
    }
}
