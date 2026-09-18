package com.keepasskey.app.ui.screens.unlock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * 附加密钥文件：文件选择行为（非布尔开关）——点击唤起 SAF；已选时展示文件名并提供清除。
 */
@Composable
internal fun UnlockKeyFileRow(
    hasKeyFile: Boolean,
    keyFileName: String,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable {
                if (hasKeyFile) onClearKeyFile() else onSelectKeyFile()
            }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.AttachFile,
            contentDescription = stringResource(R.string.unlock_keyfile),
            tint = if (hasKeyFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.unlock_keyfile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hasKeyFile && keyFileName.isNotBlank()) {
                Text(
                    text = stringResource(R.string.unlock_keyfile_selected, keyFileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = stringResource(R.string.unlock_keyfile_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = if (hasKeyFile) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (hasKeyFile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** H4-只读整改：只读打开开关（KeePassDX/KP2A 同款能力） */
@Composable
internal fun UnlockReadOnlyRow(
    openReadOnly: Boolean,
    onToggleReadOnly: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.unlock_readonly),
                style = MaterialTheme.typography.bodyMedium,
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

/** 「切回快速解锁」入口：仅在存在可用快速解锁凭据时呈现 */
@Composable
internal fun UnlockSwitchToQuickEntry(
    onSwitchToQuickUnlock: () -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(
        onClick = onSwitchToQuickUnlock,
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.unlock_switch_back_quick), style = MaterialTheme.typography.labelSmall)
    }
}
