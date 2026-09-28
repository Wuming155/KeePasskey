package com.keepasskey.app.ui.screens.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.CustomIconItem
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.components.getVaultIcon

/**
 * 凭据编辑页的基础表单分节（所属分组 / 只读横幅 / 基本信息 / 账户与密码 / 安全备注）。
 *
 * ISSUE-P3-31 批次 C：由 `EntryEditScreen.kt`（原 712 行）按**纯结构性拆分**搬出。
 * 全部分节声明为 `ColumnScope` 扩展，使其内部子元素仍是父 `Column` 的**直接子节点**——
 * 因此 `verticalArrangement = spacedBy(16.dp)` 的分节间距与拆分前完全一致；
 * 密码生成器所在的 `AnimatedVisibility` 亦保留在原 `BentoCard { Column { … } }` 内，
 * `ColumnScope` 重载解析（expandVertically/shrinkVertically 默认动画）不变。
 */

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
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
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

                // ISSUE-P3-359 AC①②：Next 链首站；校验失败时 inline 错误 + 聚焦到该字段
                val titleFocusRequester = remember { FocusRequester() }
                LaunchedEffect(uiState.titleError) {
                    if (uiState.titleError) titleFocusRequester.requestFocus()
                }

                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.edit_title_hint)) },
                    singleLine = true,
                    isError = uiState.titleError,
                    supportingText = if (uiState.titleError) {
                        { Text(stringResource(R.string.edit_title_required)) }
                    } else {
                        null
                    },
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = entryEditNextKeyboardOptions,
                    keyboardActions = rememberEntryEditNextKeyboardActions(),
                    modifier = Modifier.weight(1f).focusRequester(titleFocusRequester)
                )
            }

            EntryUrlField(
                url = uiState.url,
                onUrlChange = onUrlChange
            )
        }
    }
}

/**
 * 账户与密码（用户名 + 安全密码输入 + 强度条 + 生成器微件）。
 *
 * M1 整改：密码输入走 [SecurePasswordField]——显示用 String 仅存活于组件内部，
 * CharArray 直达 ViewModel；既有密码经 [loadedPassword] 一次性预填。
 *
 * ISSUE-P3-305：原 100 行的单函数把密码生成器模块拆为 [EntryEditGeneratorSection]
 * （逐行搬运，卡片层级与 `ColumnScope` 子节点关系不变）。
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
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ISSUE-P3-359 AC①：username → 下一个可聚焦字段（密码框）的 Next 链
            OutlinedTextField(
                value = uiState.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.edit_username_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = entryEditNextKeyboardOptions,
                keyboardActions = rememberEntryEditNextKeyboardActions(),
                modifier = Modifier.fillMaxWidth()
            )

            SecurePasswordField(
                label = stringResource(R.string.edit_password_hint),
                onPasswordChanged = onPasswordChangeSecure,
                isPasswordVisible = uiState.isPasswordVisible,
                initialPassword = loadedPassword,
                initialKey = uiState.entryId ?: "new-entry",
                // ISSUE-P3-359 AC①：Done → 收起键盘（默认 `{}` 会吞掉框架收键盘行为，
                // 按完成键无反应）。不接保存：底栏/顶栏保存是既有交互语义，避免误提交
                onDone = rememberEntryEditHideKeyboard(),
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
                },
                // 与同卡片内用户名/URL 满宽对齐（Material 3 表单列等宽）
                modifier = Modifier.fillMaxWidth()
            )

            if (uiState.passwordLength > 0) {
                // ISSUE-P2-286 AC①：真实熵（crypto 内核 guessesLog10，与详情页同一实现），
                // 替代已退役的「长度 × 4.5」启发式；未评估 / 不可用时由强度条自行隐藏
                PasswordStrengthBar(
                    entropyBits = uiState.passwordEntropyBits,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ISSUE-P3-261 AC⑤：密码生成器模块整体为独立分节（动效参数取自主题 MotionScheme，
            // 见 [EntryEditGeneratorSection]）；保持 `ColumnScope` 扩展，使其仍是父 `Column` 的直接子节点
            EntryEditGeneratorSection(
                uiState = uiState,
                onPassLengthChange = onPassLengthChange,
                onGeneratePassword = onGeneratePassword,
                onToggleUpper = onToggleUpper,
                onToggleLower = onToggleLower,
                onToggleDigits = onToggleDigits,
                onToggleSymbols = onToggleSymbols
            )
        }
    }
}

/**
 * 密码生成器分节（ISSUE-P3-305：自 100 行的 [EntryEditAccountSection] 按职责搬出，逐行未改）。
 *
 * 声明为 `ColumnScope` 扩展以保持拆分前的 `ColumnScope.AnimatedVisibility` 重载解析
 * （同文件各分节的既有约束，见文件头 KDoc）。
 */
@Composable
private fun ColumnScope.EntryEditGeneratorSection(
    uiState: EntryEditUiState,
    onPassLengthChange: (Float) -> Unit,
    onGeneratePassword: () -> Unit,
    onToggleUpper: () -> Unit,
    onToggleLower: () -> Unit,
    onToggleDigits: () -> Unit,
    onToggleSymbols: () -> Unit
) {
    // ISSUE-P3-261 AC⑤：原为无 enter / exit 的默认 `expandIn` + `fadeIn`，现显式取主题
    // MotionScheme 的空间（展开/收起）与效果（淡化）spec。
    val generatorFade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val generatorSize = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    AnimatedVisibility(
        visible = uiState.showGenerator,
        enter = fadeIn(generatorFade) + expandVertically(generatorSize),
        exit = fadeOut(generatorFade) + shrinkVertically(generatorSize)
    ) {
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

/** 安全备注（多行）。 */
@Composable
internal fun ColumnScope.EntryEditNotesSection(
    notes: String,
    onNotesChange: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.edit_notes),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // ISSUE-P3-359 AC①：备注为表单末端字段 → Done 收起键盘
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.edit_notes_hint)) },
            minLines = 3,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = entryEditDoneKeyboardOptions,
            keyboardActions = rememberEntryEditDoneKeyboardActions(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

