package com.keepasskey.app.ui.screens.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.CustomIconItem
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 凭据编辑页的基础表单分节（所属分组 / 只读横幅 / 基本信息 / 账户与密码 / 安全备注）。
 *
 * ISSUE-P3-31 批次 C：由 `EntryEditScreen.kt`（原 712 行）按**纯结构性拆分**搬出。
 * 全部分节声明为 `ColumnScope` 扩展，使其内部子元素仍是父 `Column` 的**直接子节点**——
 * 因此 `verticalArrangement = spacedBy(16.dp)` 的分节间距与拆分前完全一致；
 * 密码生成器所在的 `AnimatedVisibility` 亦保留在原 `BentoCard { Column { … } }` 内，
 * `ColumnScope` 重载解析（expandVertically/shrinkVertically 默认动画）不变。
 */

/** 所属群组 / 文件夹选择（回收站分组不作为可选目标）。 */
@Composable
internal fun ColumnScope.EntryEditGroupSection(
    uiState: EntryEditUiState,
    onGroupChange: (String?) -> Unit
) {
    if (uiState.availableGroups.isEmpty()) return

    Text(
        text = stringResource(R.string.edit_group_label),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = uiState.groupId == null,
                onClick = { onGroupChange(null) },
                label = { Text(stringResource(R.string.edit_group_root)) },
                shape = CapsuleShape,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
        items(uiState.availableGroups.filter { !it.isRecycleBin }) { grp ->
            val selected = uiState.groupId == grp.id
            FilterChip(
                selected = selected,
                onClick = { onGroupChange(grp.id) },
                label = { Text(grp.name) },
                shape = CapsuleShape,
                leadingIcon = {
                    Icon(
                        imageVector = getVaultIcon(grp.iconName),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

/** H4-只读整改：只读会话提示横幅。 */
@Composable
internal fun ColumnScope.EntryEditReadOnlyBanner(isReadOnly: Boolean) {
    if (!isReadOnly) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.readonly_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/** 基本信息（图标选择入口 + 标题 + URL）。 */
@Composable
internal fun ColumnScope.EntryEditBasicInfoSection(
    uiState: EntryEditUiState,
    customIconOptions: List<CustomIconItem>,
    onIconClick: () -> Unit,
    onTitleChange: (String) -> Unit,
    onUrlChange: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_basic_info),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onIconClick),
                    contentAlignment = Alignment.Center
                ) {
                    // TASK-15：选中自定义图标时渲染位图，否则回退标准图标
                    val selectedCustom = customIconOptions.firstOrNull { it.id == uiState.customIconId }
                    if (selectedCustom != null) {
                        Image(
                            bitmap = selectedCustom.bitmap,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp)
                        )
                    } else {
                        Icon(
                            imageVector = getVaultIcon(uiState.iconName),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.edit_title_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                )
            }

            OutlinedTextField(
                value = uiState.url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.edit_url_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 账户与密码（用户名 + 安全密码输入 + 强度条 + 生成器微件）。
 *
 * M1 整改：密码输入走 [SecurePasswordField]——显示用 String 仅存活于组件内部，
 * CharArray 直达 ViewModel；既有密码经 [loadedPassword] 一次性预填。
 */
@Composable
internal fun ColumnScope.EntryEditAccountSection(
    uiState: EntryEditUiState,
    loadedPassword: CharArray?,
    onUsernameChange: (String) -> Unit,
    onPasswordChangeSecure: (CharArray) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onToggleGenerator: () -> Unit,
    onPassLengthChange: (Float) -> Unit,
    onGeneratePassword: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit
) {
    Text(
        text = stringResource(R.string.edit_account_pwd),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = uiState.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.edit_username_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )

            SecurePasswordField(
                label = stringResource(R.string.edit_password_hint),
                onPasswordChanged = onPasswordChangeSecure,
                isPasswordVisible = uiState.isPasswordVisible,
                initialPassword = loadedPassword,
                initialKey = uiState.entryId ?: "new-entry",
                trailingIcon = {
                    Row {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                imageVector = if (uiState.isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_password_visibility),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onToggleGenerator) {
                            Icon(
                                imageVector = Icons.Default.ElectricBolt,
                                contentDescription = stringResource(R.string.cd_password_generator),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )

            if (uiState.passwordLength > 0) {
                PasswordStrengthBar(
                    entropyBits = (uiState.passwordLength * PASSWORD_BITS_PER_CHAR).toInt(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 密码生成器模块
            AnimatedVisibility(visible = uiState.showGenerator) {
                PasswordGeneratorWidget(
                    passLength = uiState.passLength,
                    useUpper = uiState.useUpper,
                    useLower = uiState.useLower,
                    useDigits = uiState.useDigits,
                    useSymbols = uiState.useSymbols,
                    onPassLengthChange = onPassLengthChange,
                    onRegenerate = onGeneratePassword,
                    onToggleUpper = onToggleUpper,
                    onToggleLower = onToggleLower,
                    onToggleDigits = onToggleDigits,
                    onToggleSymbols = onToggleSymbols
                )
            }
        }
    }
}

/** 安全备注（多行）。 */
@Composable
internal fun ColumnScope.EntryEditNotesSection(
    notes: String,
    onNotesChange: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_notes),
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.edit_notes_hint)) },
            minLines = 3,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 编辑页强度条的**长度启发式**系数（每字符约 4.5 bit）。
 *
 * ISSUE-P3-31 批次 C：原为 `EntryEditScreen.kt` 内的裸字面量 `4.5`，搬出时按工程规则
 * 「禁止魔法数字」具名化。**数值与语义未变**：此处刻意只用长度估算，
 * 因为编辑页的密码明文按敏感数据铁律不回流到 UI 状态（只有 `passwordLength`），
 * 故这里不是、也不应是真实熵；详情页的真实熵评估见 `EntryDetailViewModel`。
 */
private const val PASSWORD_BITS_PER_CHAR = 4.5
