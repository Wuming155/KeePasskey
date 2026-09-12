package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 密码库设置页的行组件与自包含区块卡片：
 * 由 DatabaseSettingsScreen 的 LazyColumn 按区块装配，间距结构与原实现一致。
 */

@Composable
internal fun DatabaseFieldRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = stringResource(R.string.dbset_cd_modify),
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
internal fun DatabaseActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** LazyColumn 区块小标题 */
@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/** 标题 + 描述 + 右侧开关的设置行（`enabled=false` 时整行降透明度且开关不可交互） */
@Composable
internal fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange.takeIf { enabled },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/** 1. 常规与基础属性：名称、路径、默认用户、压缩与回收站开关 */
@Composable
internal fun DatabaseBasicCard(
    uiState: SettingsUiState,
    onRecycleBinToggle: (Boolean) -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DatabaseFieldRow(label = stringResource(R.string.dbset_field_db_name), value = ifBlankOrUnset(uiState.databaseName))
            // ISSUE-P3-59：文件路径/默认用户名真实下发，空值显示「未设置」占位（此前恒空白）
            DatabaseFieldRow(label = stringResource(R.string.dbset_field_db_path), value = ifBlankOrUnset(uiState.databasePath))
            DatabaseFieldRow(label = stringResource(R.string.dbset_field_default_user), value = ifBlankOrUnset(uiState.databaseDefaultUsername))
            DatabaseFieldRow(label = stringResource(R.string.dbset_field_compression), value = ifBlankOrUnset(uiState.compressionAlgorithm))

            SettingsToggleRow(
                title = stringResource(R.string.dbset_recycle_bin_title),
                description = stringResource(R.string.dbset_recycle_bin_desc),
                checked = uiState.recycleBinEnabled,
                onCheckedChange = onRecycleBinToggle
            )
        }
    }
}

/** 2. 密码学与 KDF 派生：加密算法、KDF 算法与 Argon2 参数入口 */
@Composable
internal fun DatabaseCryptoCard(
    uiState: SettingsUiState,
    onCipherClick: () -> Unit,
    onKdfClick: () -> Unit,
    onArgonClick: () -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DatabaseFieldRow(
                label = stringResource(R.string.dbset_field_cipher),
                // ISSUE-P2-19：值来自活动库文件头；无会话时空值显示「未设置」占位
                value = ifBlankOrUnset(uiState.encryptionAlgorithm),
                onClick = onCipherClick
            )
            DatabaseFieldRow(
                label = stringResource(R.string.dbset_field_kdf),
                value = ifBlankOrUnset(uiState.kdfAlgorithm),
                onClick = onKdfClick
            )
            DatabaseFieldRow(
                label = stringResource(R.string.dbset_field_argon2),
                value = if (uiState.argon2MemoryMb > 0L || uiState.argon2Iterations > 0L) {
                    stringResource(
                        R.string.dbset_argon2_value,
                        uiState.argon2MemoryMb,
                        uiState.argon2Iterations,
                        uiState.argon2Parallelism
                    )
                } else {
                    unsetLabel()
                },
                onClick = onArgonClick
            )
        }
    }
}

/** ISSUE-P2-19/P3-59：空值统一显示「未设置」占位，绝不留空白也不显示与实际不符的假值 */
@Composable
private fun ifBlankOrUnset(value: String): String =
    if (value.isBlank()) unsetLabel() else value

@Composable
private fun unsetLabel(): String = stringResource(R.string.dbset_value_unset)

/** 3. 条目模板库与子数据库配置 (KP2A 特性) */
@Composable
internal fun DatabaseExtensionsCard(
    childDatabasesCount: Int,
    onTemplatesClick: () -> Unit,
    onChildDbClick: () -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DatabaseActionRow(
                icon = Icons.AutoMirrored.Filled.Notes,
                title = stringResource(R.string.dbset_templates_title),
                subtitle = stringResource(R.string.dbset_templates_sub),
                onClick = onTemplatesClick
            )

            DatabaseActionRow(
                icon = Icons.Default.FolderShared,
                title = stringResource(R.string.dbset_child_db_title),
                subtitle = if (childDatabasesCount > 0) {
                    stringResource(R.string.dbset_child_db_linked, childDatabasesCount)
                } else {
                    stringResource(R.string.dbset_child_db_none)
                },
                onClick = onChildDbClick
            )
        }
    }
}

/** 4. 数据导入与导出 (KP2A 特性) */
@Composable
internal fun DatabaseImportExportCard(
    onImportClick: () -> Unit,
    onExportClick: () -> Unit,
    onKeyFileExportClick: () -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DatabaseActionRow(
                icon = Icons.Default.Download,
                title = stringResource(R.string.dbset_import_title),
                subtitle = stringResource(R.string.dbset_import_sub),
                onClick = onImportClick
            )

            DatabaseActionRow(
                icon = Icons.Default.Upload,
                title = stringResource(R.string.dbset_export_title),
                subtitle = stringResource(R.string.dbset_export_sub),
                onClick = onExportClick
            )

            DatabaseActionRow(
                icon = Icons.Default.VpnKey,
                title = stringResource(R.string.dbset_keyfile_export_title),
                subtitle = stringResource(R.string.dbset_keyfile_export_sub),
                onClick = onKeyFileExportClick
            )
        }
    }
}

/**
 * 5. 完整性与高级规则 (KP2A 特性)：TAN 一次性失效与重复 UUID 检查
 *
 * ISSUE-P3-65 整改：两个开关此前可拨动但既无持久化也无任何行为消费方（假开关）。
 * 在补齐真实语义前如实禁用交互并以「即将支持」标注，checked 恒为 false，
 * 不再呈现「已开启」的假状态。
 */
@Composable
internal fun DatabaseIntegrityCard() {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsToggleRow(
                title = stringResource(R.string.dbset_tan_title),
                description = stringResource(R.string.dbset_tan_desc),
                checked = false,
                onCheckedChange = {},
                enabled = false
            )

            SettingsToggleRow(
                title = stringResource(R.string.dbset_uuid_title),
                description = stringResource(R.string.dbset_uuid_desc),
                checked = false,
                onCheckedChange = {},
                enabled = false
            )
        }
    }
}

/** 操作结果反馈文本：失败呈错误色，可附带点击回调（如清除导出反馈） */
@Composable
internal fun DatabaseFeedbackItem(
    message: UiMessage,
    isError: Boolean,
    onClick: (() -> Unit)? = null
) {
    Text(
        text = message.resolveText(),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 4.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    )
}
