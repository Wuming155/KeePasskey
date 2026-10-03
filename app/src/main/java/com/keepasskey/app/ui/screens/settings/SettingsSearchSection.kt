package com.keepasskey.app.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.BuildConfig
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.subscreens.DisplayPrefRow
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 设置主页的分组装配 + 设置项搜索栏（`ISSUE-P3-444` AC③）。
 *
 * 拆出本文件的原因有二：① 搜索要求「先知道全部分组的可搜索文本」才能在零命中时给空态，
 * 分组因此必须**数据化**（原先硬编码在 `SettingsContent` 的组合树上，无从统计）；
 * ② `SettingsScreen.kt` 已逼近单文件规模闸门，分组装配下沉后主文件只留页面壳与对话框宿主。
 *
 * 分组顺序、行顺序、图标与文案资源与搜索结果改造前**逐项一致**（纯结构搬运 + 过滤维度）。
 */
internal class SettingsActions(
    val onNavigateToDatabase: () -> Unit,
    val onNavigateToImportExport: () -> Unit,
    val onNavigateToSync: () -> Unit,
    val onNavigateToAutofill: () -> Unit,
    val onNavigateToPasskey: () -> Unit,
    val onNavigateToSecurity: () -> Unit,
    val onNavigateToTheme: () -> Unit,
    val onNavigateToListNav: () -> Unit,
    val onNavigateToHealth: () -> Unit,
    val onNavigateToTotp: () -> Unit,
    val onNavigateToDebug: () -> Unit,
    val onNavigateToAbout: () -> Unit,
    /** 打开「更换主密钥」对话框（状态由 `SettingsContent` 持有） */
    val onOpenMasterKeyDialog: () -> Unit,
    // ===== ISSUE-P3-444：界面偏好组 =====
    val onMonospaceFieldsChange: (Boolean) -> Unit,
    val onReduceAnimationsChange: (Boolean) -> Unit
)

/** 全部分组（顺序＝渲染顺序）；`BuildConfig.DEBUG` 只决定系统组是否含「调试」行。 */
@Composable
internal fun settingsGroups(uiState: SettingsUiState, actions: SettingsActions): List<SettingsGroupSpec> =
    listOf(
        storageGroup(actions),
        securityGroup(actions),
        autofillGroup(actions),
        interfaceGroup(uiState, actions),
        displayGroup(actions),
        systemGroup(uiState, actions)
    )

@Composable
private fun storageGroup(actions: SettingsActions): SettingsGroupSpec = SettingsGroupSpec(
    headerRes = R.string.settings_cat_storage,
    rows = listOf(
        settingsRow(R.string.settings_database, R.string.settings_database_sub) {
            ModernSettingsRow(
                icon = Icons.Default.Storage,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_database),
                subtitle = stringResource(R.string.settings_database_sub),
                onClick = actions.onNavigateToDatabase
            )
        },
        // ISSUE-P3-467：数据导入与导出（动作流，自数据库属性页拆出）
        settingsRow(R.string.settings_import_export, R.string.settings_import_export_sub) {
            ModernSettingsRow(
                icon = Icons.Default.ImportExport,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_import_export),
                subtitle = stringResource(R.string.settings_import_export_sub),
                onClick = actions.onNavigateToImportExport
            )
        },
        settingsRow(R.string.settings_sync, R.string.settings_sync_sub) {
            ModernSettingsRow(
                icon = Icons.Default.CloudSync,
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_sync),
                subtitle = stringResource(R.string.settings_sync_sub),
                onClick = actions.onNavigateToSync
            )
        }
    )
)

@Composable
private fun securityGroup(actions: SettingsActions): SettingsGroupSpec {
    val securityColors = LocalSecurityColors.current
    return SettingsGroupSpec(
        headerRes = R.string.settings_cat_security,
        rows = listOf(
            settingsRow(R.string.settings_security, R.string.settings_security_sub) {
                ModernSettingsRow(
                    icon = Icons.Default.Fingerprint,
                    iconTint = securityColors.passkey,
                    title = stringResource(R.string.settings_security),
                    subtitle = stringResource(R.string.settings_security_sub),
                    onClick = actions.onNavigateToSecurity
                )
            },
            settingsRow(R.string.set_totp_entry_title, R.string.set_totp_entry_sub) {
                ModernSettingsRow(
                    icon = Icons.Default.Password,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.set_totp_entry_title),
                    subtitle = stringResource(R.string.set_totp_entry_sub),
                    onClick = actions.onNavigateToTotp
                )
            },
            settingsRow(R.string.settings_health, R.string.settings_health_sub) {
                ModernSettingsRow(
                    icon = Icons.Default.HealthAndSafety,
                    iconTint = securityColors.success,
                    title = stringResource(R.string.settings_health),
                    subtitle = stringResource(R.string.settings_health_sub),
                    onClick = actions.onNavigateToHealth
                )
            },
            // ISSUE-P3-413：凭据操作归安全域——六路调研无一家把改密与存储/同步并列
            settingsRow(R.string.settings_change_master_key, R.string.settings_change_master_key_sub) {
                ModernSettingsRow(
                    icon = Icons.Default.VpnKey,
                    iconTint = securityColors.warning,
                    title = stringResource(R.string.settings_change_master_key),
                    subtitle = stringResource(R.string.settings_change_master_key_sub),
                    onClick = actions.onOpenMasterKeyDialog
                )
            }
        )
    )
}

@Composable
private fun autofillGroup(actions: SettingsActions): SettingsGroupSpec {
    val securityColors = LocalSecurityColors.current
    return SettingsGroupSpec(
        // ISSUE-P3-432：自动填充与通行密钥拆为两个设置项
        headerRes = R.string.settings_cat_preferences,
        rows = listOf(
            settingsRow(R.string.settings_autofill, R.string.settings_autofill_sub) {
                ModernSettingsRow(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.settings_autofill),
                    subtitle = stringResource(R.string.settings_autofill_sub),
                    onClick = actions.onNavigateToAutofill
                )
            },
            settingsRow(R.string.settings_passkey, R.string.settings_passkey_sub) {
                ModernSettingsRow(
                    icon = Icons.Default.Key,
                    iconTint = securityColors.passkey,
                    title = stringResource(R.string.settings_passkey),
                    subtitle = stringResource(R.string.settings_passkey_sub),
                    onClick = actions.onNavigateToPasskey
                )
            }
        )
    )
}

/** `ISSUE-P3-444` AC⑤：界面偏好分组（等宽字段字体 / 动效降级）。 */
@Composable
private fun interfaceGroup(uiState: SettingsUiState, actions: SettingsActions): SettingsGroupSpec =
    SettingsGroupSpec(
        headerRes = R.string.settings_cat_interface,
        rows = listOf(
            settingsRow(R.string.settings_monospace_fields, R.string.settings_monospace_fields_sub) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                    DisplayPrefRow(
                        title = stringResource(R.string.settings_monospace_fields),
                        subtitle = stringResource(R.string.settings_monospace_fields_sub),
                        checked = uiState.monospaceFieldsEnabled,
                        onCheckedChange = actions.onMonospaceFieldsChange
                    )
                }
            },
            settingsRow(R.string.settings_reduce_animations, R.string.settings_reduce_animations_sub) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                    DisplayPrefRow(
                        title = stringResource(R.string.settings_reduce_animations),
                        subtitle = stringResource(R.string.settings_reduce_animations_sub),
                        checked = uiState.reduceAnimations,
                        onCheckedChange = actions.onReduceAnimationsChange
                    )
                }
            }
        )
    )

/** ISSUE-P3-413：外观独立成组，不与自动填充混排。 */
@Composable
private fun displayGroup(actions: SettingsActions): SettingsGroupSpec = SettingsGroupSpec(
    headerRes = R.string.settings_cat_display,
    rows = listOf(
        settingsRow(R.string.settings_theme, R.string.settings_theme_sub) {
            ModernSettingsRow(
                icon = Icons.Default.Palette,
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_theme),
                subtitle = stringResource(R.string.settings_theme_sub),
                onClick = actions.onNavigateToTheme
            )
        },
        // ISSUE-P3-467：列表与导航偏好（自主题页拆出）
        settingsRow(R.string.settings_list_nav, R.string.settings_list_nav_sub) {
            ModernSettingsRow(
                icon = Icons.AutoMirrored.Filled.List,
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_list_nav),
                subtitle = stringResource(R.string.settings_list_nav_sub),
                onClick = actions.onNavigateToListNav
            )
        }
    )
)

@Composable
private fun systemGroup(uiState: SettingsUiState, actions: SettingsActions): SettingsGroupSpec {
    val securityColors = LocalSecurityColors.current
    return SettingsGroupSpec(
        headerRes = R.string.set_section_system,
        rows = buildList {
            // ISSUE-P3-413：诊断日志入口仅 debug 构建露出（release 日志缓冲恒空、导出恒禁用）
            if (BuildConfig.DEBUG) {
                add(settingsRow(R.string.debug_title, R.string.set_debug_entry_sub) {
                    ModernSettingsRow(
                        icon = Icons.Default.BugReport,
                        iconTint = securityColors.warning,
                        title = stringResource(R.string.debug_title),
                        subtitle = stringResource(R.string.set_debug_entry_sub),
                        onClick = actions.onNavigateToDebug
                    )
                })
            }
            add(settingsRow(R.string.settings_about, R.string.set_about_entry_sub) {
                ModernSettingsRow(
                    icon = Icons.Default.Info,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_about),
                    subtitle = stringResource(R.string.set_about_entry_sub, uiState.appVersion),
                    onClick = actions.onNavigateToAbout
                )
            })
        }
    )
}

/**
 * 设置项搜索框（`ISSUE-P3-444` AC③）：顶栏之下的独立输入行，非空时右侧给清除入口。
 */
@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(
                text = stringResource(R.string.settings_search_hint),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.settings_search_clear)
                    )
                }
            }
        },
        shape = RoundedCornerShape(14.dp)
    )
}
