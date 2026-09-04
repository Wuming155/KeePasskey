package com.keepasskey.app.ui.navigation

/**
 * 界面路由定义
 */
sealed class Screen(val route: String) {
    data object Unlock : Screen("unlock")
    data object DatabasePicker : Screen("database_picker")
    data object VaultList : Screen("vault_list")
    data object Authenticator : Screen("authenticator")
    data object Generator : Screen("generator")
    data object ConflictResolver : Screen("conflict_resolver")
    data object EntryDetail : Screen("entry_detail/{entryId}") {
        fun createRoute(entryId: String): String = "entry_detail/$entryId"
    }
    data object EntryEdit : Screen("entry_edit?entryId={entryId}&groupId={groupId}") {
        fun createRoute(entryId: String? = null, groupId: String? = null): String {
            val params = mutableListOf<String>()
            if (entryId != null) params.add("entryId=$entryId")
            if (groupId != null) params.add("groupId=$groupId")
            return if (params.isNotEmpty()) "entry_edit?${params.joinToString("&")}" else "entry_edit"
        }
    }
    data object Settings : Screen("settings")
    data object SettingsDatabase : Screen("settings/database")
    data object SettingsSync : Screen("settings/sync")
    data object SettingsAutofill : Screen("settings/autofill")
    data object SettingsSecurity : Screen("settings/security")
    data object SettingsTheme : Screen("settings/theme")
    data object SettingsHealth : Screen("settings/health")
    data object SettingsTotp : Screen("settings/totp")
    data object SettingsDebug : Screen("settings/debug")
    data object SettingsAbout : Screen("settings/about")
}
