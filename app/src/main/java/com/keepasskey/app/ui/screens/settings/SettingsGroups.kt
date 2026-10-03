package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.keepasskey.app.BuildConfig
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 设置主页的分组装配（`ISSUE-P3-444` AC③ 增设搜索时自 `SettingsScreen.kt` 下沉，**AC③ 作废后保留**）。
 *
 * 现状（§427 修订）：**不做设置项搜索**——分组装配与 `SettingsScreen.kt` 分离这一结构照旧保留
 * （`SettingsScreen.kt` 需要这份余量以避免贴近单文件规模闸门），但仅作为「声明式分组列表 + 统一渲染」，
 * 不再承载任何过滤 / 高亮维度。分组顺序、行顺序、图标与文案资源与引入搜索前**逐项一致**。
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
    // ===== ISSUE-P3-444 修订：界面偏好二级页入口（原为分组内联两开关） =====
    val onNavigateToInterface: () -> Unit
)

/** 分组内一行：只承载渲染体（行自身的文案在组合期由各 `ModernSettingsRow` 解析）。 */
internal class SettingsGroupRow(val content: @Composable () -> Unit)

/**
 * 一个设置分组：分组标题资源（**可空**，`null` 表示不渲染分组标题）+ 行列表。
 *
 * `ISSUE-P3-468` F 项：release 构建下「开发者调试」隐藏、「系统」组仅剩「关于」单条目时，
 * 不渲染分组标题，让「关于」独立成卡（Material 指南不建议单条目分组）。
 */
internal class SettingsGroupSpec(
    @StringRes val headerRes: Int?,
    val rows: List<SettingsGroupRow>
)

/** 渲染一个分组卡片；零行时不渲染（不产生只有标题的空卡）；标题资源为 `null` 时不渲染标题。 */
@Composable
internal fun SettingsGroup(@StringRes headerRes: Int?, rows: List<SettingsGroupRow>) {
    if (rows.isEmpty()) return
    if (headerRes != null) {
        ModernSectionHeader(title = stringResource(headerRes))
    }
    SettingsGroupCard {
        rows.forEachIndexed { index, row ->
            if (index > 0) SettingsItemDivider()
            row.content()
        }
    }
}

/**
 * 全部分组（顺序＝渲染顺序）；`BuildConfig.DEBUG` 只决定系统组是否含「调试」行、
 * 以及是否渲染「系统」组标题（`ISSUE-P3-468` F 项：release 下仅「关于」时标题省略、独立成卡）。
 */
@Composable
internal fun settingsGroups(uiState: SettingsUiState, actions: SettingsActions): List<SettingsGroupSpec> =
    listOf(
        storageGroup(actions),
        securityGroup(actions),
        autofillGroup(actions),
        displayGroup(actions),
        systemGroup(uiState, actions)
    )

@Composable
private fun storageGroup(actions: SettingsActions): SettingsGroupSpec = SettingsGroupSpec(
    headerRes = R.string.settings_cat_storage,
    rows = listOf(
        SettingsGroupRow {
            ModernSettingsRow(
                icon = Icons.Default.Storage,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_database),
                subtitle = stringResource(R.string.settings_database_sub),
                onClick = actions.onNavigateToDatabase
            )
        },
        // ISSUE-P3-467：数据导入与导出（动作流，自数据库属性页拆出）
        SettingsGroupRow {
            ModernSettingsRow(
                icon = Icons.Default.ImportExport,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.settings_import_export),
                subtitle = stringResource(R.string.settings_import_export_sub),
                onClick = actions.onNavigateToImportExport
            )
        },
        SettingsGroupRow {
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
            SettingsGroupRow {
                ModernSettingsRow(
                    icon = Icons.Default.Fingerprint,
                    iconTint = securityColors.passkey,
                    title = stringResource(R.string.settings_security),
                    subtitle = stringResource(R.string.settings_security_sub),
                    onClick = actions.onNavigateToSecurity
                )
            },
            SettingsGroupRow {
                ModernSettingsRow(
                    icon = Icons.Default.Password,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.set_totp_entry_title),
                    subtitle = stringResource(R.string.set_totp_entry_sub),
                    onClick = actions.onNavigateToTotp
                )
            },
            SettingsGroupRow {
                ModernSettingsRow(
                    icon = Icons.Default.HealthAndSafety,
                    iconTint = securityColors.success,
                    title = stringResource(R.string.settings_health),
                    subtitle = stringResource(R.string.settings_health_sub),
                    onClick = actions.onNavigateToHealth
                )
            },
            // ISSUE-P3-413：凭据操作归安全域——六路调研无一家把改密与存储/同步并列
            SettingsGroupRow {
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
        // ISSUE-P3-468：id 自 settings_cat_preferences 改名对齐（组名「自动填充」不变）
        headerRes = R.string.settings_cat_autofill,
        rows = listOf(
            SettingsGroupRow {
                ModernSettingsRow(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = stringResource(R.string.settings_autofill),
                    subtitle = stringResource(R.string.settings_autofill_sub),
                    onClick = actions.onNavigateToAutofill
                )
            },
            SettingsGroupRow {
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

/** ISSUE-P3-413：外观独立成组，不与自动填充混排。 */
@Composable
private fun displayGroup(actions: SettingsActions): SettingsGroupSpec = SettingsGroupSpec(
    headerRes = R.string.settings_cat_display,
    rows = listOf(
        SettingsGroupRow {
            ModernSettingsRow(
                icon = Icons.Default.Palette,
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_theme),
                subtitle = stringResource(R.string.settings_theme_sub),
                onClick = actions.onNavigateToTheme
            )
        },
        // ISSUE-P3-467：列表与导航偏好（自主题页拆出）
        SettingsGroupRow {
            ModernSettingsRow(
                icon = Icons.AutoMirrored.Filled.List,
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_list_nav),
                subtitle = stringResource(R.string.settings_list_nav_sub),
                onClick = actions.onNavigateToListNav
            )
        },
        // ISSUE-P3-444 修订：界面偏好（等宽字段字体 / 动效降级）收进本组第三个入口，
        // 不再以独立分组平铺在主页——两项都属「界面与显示」的细分表现，与上两行同族
        // ISSUE-P3-468：条目名「界面偏好」改「字体与动效」（与组名「界面与显示」语义近重合）
        SettingsGroupRow {
            ModernSettingsRow(
                icon = Icons.Default.Tune,
                iconTint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.settings_cat_interface),
                subtitle = stringResource(R.string.settings_interface_sub),
                onClick = actions.onNavigateToInterface
            )
        }
    )
)

@Composable
private fun systemGroup(uiState: SettingsUiState, actions: SettingsActions): SettingsGroupSpec {
    val securityColors = LocalSecurityColors.current
    return SettingsGroupSpec(
        // ISSUE-P3-468 F：release 构建「调试」行隐藏后本组仅剩「关于」单条目 —— 不渲染组标题，
        // 「关于」独立成卡（Material 不建议单条目分组）；debug 构建维持「系统」标题 + 两行
        headerRes = if (BuildConfig.DEBUG) R.string.set_section_system else null,
        rows = buildList {
            // ISSUE-P3-413：诊断日志入口仅 debug 构建露出（release 日志缓冲恒空、导出恒禁用）
            if (BuildConfig.DEBUG) {
                add(
                    SettingsGroupRow {
                        ModernSettingsRow(
                            icon = Icons.Default.BugReport,
                            iconTint = securityColors.warning,
                            title = stringResource(R.string.debug_title),
                            subtitle = stringResource(R.string.set_debug_entry_sub),
                            onClick = actions.onNavigateToDebug
                        )
                    }
                )
            }
            add(
                SettingsGroupRow {
                    ModernSettingsRow(
                        icon = Icons.Default.Info,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.settings_about),
                        subtitle = stringResource(R.string.set_about_entry_sub, uiState.appVersion),
                        onClick = actions.onNavigateToAbout
                    )
                }
            )
        }
    )
}
