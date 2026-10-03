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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * §433（ISSUE-P3-448 走查续）：已选且已知**加载来源**（[keyFileSourcePath]，私有目录副本绝对路径
 * 或 SAF Uri）时，在行下方另起一行呈现来源路径——默认**中间省略**、点右侧眼睛展开完整路径。
 * 该行刻意落在**可点击行之外**：整行点击是「清除密钥文件」，若把来源行纳入同一手势，
 * 用户想看清路径就会把密钥文件清掉。
 */
@Composable
internal fun UnlockKeyFileRow(
    hasKeyFile: Boolean,
    keyFileName: String,
    keyFileSourcePath: String?,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
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
        if (hasKeyFile && !keyFileSourcePath.isNullOrBlank()) {
            KeyFileSourceRow(path = keyFileSourcePath)
        }
    }
}

/**
 * §433（ISSUE-P3-448 走查续）：密钥文件**加载来源**行。
 *
 * 回答用户走查原话「有没有导入私有目录我看不出来」——显示本次加载的**真实路径**
 * （私有目录收编副本为绝对路径，SAF 来源为 Uri）：默认**中间省略**（不整段铺开），
 * 点右侧按钮展开完整路径，再次点击收起。
 */
@Composable
private fun KeyFileSourceRow(path: String) {
    var expanded by remember { mutableStateOf(false) }
    KeyFileSourceRowContent(path = path, expanded = expanded, onToggle = { expanded = !expanded })
}

/**
 * §434 装机回执修正：**展开态必须真的能看全**。
 *
 * 原实现展开后仍是单行 + `Ellipsis`——固定行宽下超长路径（真机 118 字符）必然截尾，
 * 用户回执「完整的路径在当前空间根本看不全」正是指此。现改为：折叠态单行**中间省略**，
 * 展开态**换行铺满**（路径含 `/`，系统排版会在斜杠后断行，天然可读），不再截断。
 *
 * 状态外提为 [expanded] / [onToggle] 而非内持 `remember`，是为了让展开态**能被 @Preview 覆盖**
 * ——本态此前只能靠真机撞见，正是「新态无预览」的再现（ISSUE-P3-340 口径）；
 * 两态预览见 `UnlockScreenPreviews.kt`。
 */
@Composable
internal fun KeyFileSourceRowContent(
    path: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 左缩进对齐上行文字列（图标 20dp + 间隔 10dp + 行内水平内边距 4dp）
            .padding(start = 34.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.unlock_keyfile_source_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (expanded) path else abbreviatePath(path),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 展开：不限行数换行铺满（完整可读）；折叠：单行 + 省略号
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = stringResource(R.string.cd_toggle_keyfile_path),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * §433（ISSUE-P3-448 走查续）：路径**中间省略**（默认折叠态）——首尾保留、中段以 `…` 代。
 *
 * 纯函数，供 JVM 单测直断言（不依赖 Compose 排版）；超长路径（如 64 位哈希文件名）由此收敛，
 * 用户需要看全时经 [KeyFileSourceRow] 的展开按钮切换。
 */
internal fun abbreviatePath(path: String, max: Int = PATH_ABBREV_MAX_CHARS): String {
    if (path.length <= max) return path
    val head = (max - 1) / 2
    val tail = max - 1 - head
    return path.take(head) + "…" + path.takeLast(tail)
}

/** 折叠态路径长度上限（首尾对称保留，中段省略） */
private const val PATH_ABBREV_MAX_CHARS = 40

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
