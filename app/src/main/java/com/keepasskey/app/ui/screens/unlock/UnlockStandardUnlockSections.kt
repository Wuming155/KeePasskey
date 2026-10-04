package com.keepasskey.app.ui.screens.unlock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.disabledPrimaryButtonBorder
import com.keepasskey.app.ui.components.disabledPrimaryButtonColors
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * `UnlockStandardUnlockContent` 的段落组件（ISSUE-P3-188 第 3 目 / 第二档：§186 自
 * `UnlockContentSections.kt` 拆出，**纯结构性改动**）。
 *
 * 口径同 §156 / §159 / §175 / §178 / §179 / §183：窄参数、不读 `UiState`、不自持状态——
 * 主密码输入框 `SecurePasswordField`（含 `wipeToken` 擦除链）**刻意留在原函数内**，
 * 本文件只承接密钥文件行、只读开关行、解锁主按钮与「切回快速解锁」四处呈现。
 */

/**
 * 附加密钥文件（KeePassDX 风格一体化行）：
 * 1. 标题固定为「密钥文件」+ 状态圆点（已就绪/未关联）；
 * 2. 默认以密码圆点掩码隐藏路径（平时不显示敏感绝对路径）；
 * 3. 右侧眼睛按钮控制路径显隐，独立清除按钮直接清除；
 * 4. 点击行主体唤起 SAF 选择器重新选择。
 */
@Composable
internal fun UnlockKeyFileRow(
    hasKeyFile: Boolean,
    keyFileName: String,
    keyFileSourcePath: String?,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPathVisible by remember { mutableStateOf(false) }
    UnlockKeyFileRowContent(
        hasKeyFile = hasKeyFile,
        keyFileName = keyFileName,
        keyFileSourcePath = keyFileSourcePath,
        isPathVisible = isPathVisible,
        onTogglePathVisibility = { isPathVisible = !isPathVisible },
        onSelectKeyFile = onSelectKeyFile,
        onClearKeyFile = onClearKeyFile,
        modifier = modifier
    )
}

/**
 * 密钥文件行内容呈现（状态外提，供 @Preview 覆盖掩码与展开两态）。
 */
@Composable
internal fun UnlockKeyFileRowContent(
    hasKeyFile: Boolean,
    keyFileName: String,
    keyFileSourcePath: String?,
    isPathVisible: Boolean,
    onTogglePathVisibility: () -> Unit,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectKeyFile)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = stringResource(R.string.unlock_keyfile),
                tint = if (hasKeyFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        UnlockKeyFileTextSection(
            hasKeyFile = hasKeyFile,
            keyFileName = keyFileName,
            keyFileSourcePath = keyFileSourcePath,
            isPathVisible = isPathVisible,
            modifier = Modifier.weight(1f)
        )
        UnlockKeyFileTrailingActions(
            hasKeyFile = hasKeyFile,
            isPathVisible = isPathVisible,
            onTogglePathVisibility = onTogglePathVisibility,
            onClearKeyFile = onClearKeyFile
        )
    }
}

@Composable
private fun UnlockKeyFileTextSection(
    hasKeyFile: Boolean,
    keyFileName: String,
    keyFileSourcePath: String?,
    isPathVisible: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.unlock_keyfile),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hasKeyFile) {
                Spacer(modifier = Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.unlock_keyfile_ready_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (hasKeyFile) {
            val displayPath = keyFileSourcePath?.takeIf { it.isNotBlank() }
                ?: keyFileName.takeIf { it.isNotBlank() }
                ?: ""
            if (isPathVisible) {
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = Int.MAX_VALUE,
                    softWrap = true
                )
            } else {
                Text(
                    text = "••••••••••••••••••••••••",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        } else {
            Text(
                text = stringResource(R.string.unlock_keyfile_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UnlockKeyFileTrailingActions(
    hasKeyFile: Boolean,
    isPathVisible: Boolean,
    onTogglePathVisibility: () -> Unit,
    onClearKeyFile: () -> Unit
) {
    if (hasKeyFile) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onTogglePathVisibility,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPathVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.cd_toggle_keyfile_path),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = onClearKeyFile,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_clear_keyfile),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    } else {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}


/** H4-只读整改：只读打开开关（KeePassDX/KP2A 同款能力，方案 A 4合1 通栏规整对齐） */
@Composable
internal fun UnlockReadOnlyRow(
    openReadOnly: Boolean,
    onToggleReadOnly: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleReadOnly)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.unlock_readonly),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.unlock_readonly),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.unlock_readonly_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.material3.Switch(
            checked = openReadOnly,
            onCheckedChange = { onToggleReadOnly() }
        )
    }
}

/**
 * 解锁主操作按钮：无密码且无密钥文件时禁用（密钥文件单独解锁时允许空密码）。
 *
 * ISSUE-P3-132 ③：禁用态走共用配色 / 描边（`ButtonStyles.kt`）——本处是那两条判据的**唯一使用点**，
 * `UiMd3AlignmentWiringTest` 因此按「原函数文件 + 本文件」**并集**扫描（§186）。
 */
@Composable
internal fun UnlockSubmitButton(
    enabled: Boolean,
    isLoading: Boolean,
    onUnlock: () -> Unit
) {
    Button(
        onClick = onUnlock,
        enabled = enabled,
        shape = CapsuleShape,
        // ISSUE-P3-132 ③：§95 只把禁用态显式化为 MD3 默认（onSurface @12%）；
        // 该取值落在 background 画布上实测仅 1.28:1，按钮形同消失。
        // 现改用 surfaceContainerHighest 填充 + 1dp outline 边界（共用组件，见 ButtonStyles.kt）。
        colors = disabledPrimaryButtonColors(),
        border = disabledPrimaryButtonBorder(),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.5.dp
            )
        } else {
            Text(
                text = stringResource(R.string.unlock_btn_unlock),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

/**
 * 「切回快速解锁」入口：仅在存在可用快速解锁凭据时呈现。
 *
 * §436 重绘为原型的次级胶囊按钮形态（⚡ 图标 + 全宽 capsule + outlineVariant 描边），
 * 替换原先居中的 TextButton；触控目标仍保持 ≥ 48dp。
 */
@Composable
internal fun UnlockSwitchToQuickEntry(
    onSwitchToQuickUnlock: () -> Unit
) {
    Spacer(modifier = Modifier.height(10.dp))
    androidx.compose.material3.Surface(
        shape = CapsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(CapsuleShape)
            .clickable(onClick = onSwitchToQuickUnlock)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FlashOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.unlock_switch_back_quick),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
