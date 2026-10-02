package com.keepasskey.app.ui.navigation

/**
 * 界面路由定义
 */
sealed class Screen(val route: String) {
    data object Unlock : Screen("unlock")

    /** 密码库管理页（用户裁决 2026-10-01：原 openImport / openCreate 直达参数已退役——
     * 解锁页空状态两枚入口就地在解锁页弹框，不经本页） */
    data object DatabasePicker : Screen("database_picker")
    data object VaultList : Screen("vault_list")
    data object Authenticator : Screen("authenticator")
    data object Generator : Screen("generator")
    data object ConflictResolver : Screen("conflict_resolver")
    data object EntryDetail : Screen("entry_detail/{entryId}") {
        fun createRoute(entryId: String): String = "entry_detail/$entryId"
    }
    data object EntryEdit : Screen("entry_edit?entryId={entryId}&groupId={groupId}&templateId={templateId}") {
        fun createRoute(entryId: String? = null, groupId: String? = null, templateId: String? = null): String {
            val params = mutableListOf<String>()
            if (entryId != null) params.add("entryId=$entryId")
            if (groupId != null) params.add("groupId=$groupId")
            if (templateId != null) params.add("templateId=$templateId")
            return if (params.isNotEmpty()) "entry_edit?${params.joinToString("&")}" else "entry_edit"
        }
    }
    data object Settings : Screen("settings")
    data object SettingsDatabase : Screen("settings/database")
    data object SettingsSync : Screen("settings/sync")
    data object SettingsAutofill : Screen("settings/autofill")

    /** 通行密钥 (Passkey) 二级设置页（ISSUE-P3-432：CM 凭据管理器通道自自动填充页拆出） */
    data object SettingsPasskey : Screen("settings/passkey")

    /** 特权浏览器白名单（CM 通道通行密钥可用性；自通行密钥页进入） */
    data object SettingsPrivilegedBrowsers : Screen("settings/privileged_browsers")
    data object SettingsSecurity : Screen("settings/security")
    data object SettingsTheme : Screen("settings/theme")
    data object SettingsHealth : Screen("settings/health")
    data object SettingsTotp : Screen("settings/totp")
    data object SettingsDebug : Screen("settings/debug")
    data object SettingsAbout : Screen("settings/about")
}
