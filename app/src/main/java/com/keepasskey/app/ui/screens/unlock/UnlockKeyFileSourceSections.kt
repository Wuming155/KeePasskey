package com.keepasskey.app.ui.screens.unlock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * §433（ISSUE-P3-448 走查续）：密钥文件**加载来源**行。
 *
 * 回答用户走查原话「有没有导入私有目录我看不出来」——显示本次加载的**真实路径**
 * （私有目录收编副本为绝对路径，SAF 来源为 Uri）：默认**中间省略**（不整段铺开），
 * 点右侧按钮展开完整路径，再次点击收起。
 */
@Composable
internal fun KeyFileSourceRow(path: String) {
    var expanded by remember { mutableStateOf(false) }
    KeyFileSourceRowContent(path = path, expanded = expanded, onToggle = { expanded = !expanded })
}

/**
 * §434 装机回执修正（两轮）：
 *
 * 1. **展开态必须真的能看全**：原实现展开后仍是单行 + `Ellipsis`，固定行宽下 118 字符的真实
 *    路径必然截尾（用户回执「完整的路径在当前空间根本看不全」）；
 * 2. **标签 / 按钮不得悬在换行块中间**：单行 `Row` + `CenterVertically` 在展开成多行后，
 *    「来源」与眼睛会落到整块文字的**垂直中线**上，读起来像路径属于上一行的「密钥文件」
 *    （装机截图实测）；且路径只拿到「行宽 − 标签 − 按钮」而多折行。
 *
 * 故改为**上下两段**：首行＝标签 + （折叠态路径 / 展开态占位）+ 眼睛按钮；展开时路径落到
 * **下一段独占整行宽度**（少折行、彻底消除悬浮）。折叠态仍是单行中间省略。
 *
 * 状态外提为 [expanded] / [onToggle] 而非内持 `remember`，是为了让展开态**能被 @Preview 覆盖**
 * ——该态曾两度只能靠真机撞见（ISSUE-P3-340 口径）；两态预览见 `UnlockScreenPreviews.kt`。
 */
@Composable
internal fun KeyFileSourceRowContent(
    path: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 左缩进对齐上行文字列（图标 20dp + 间隔 10dp + 行内水平内边距 4dp）
            .padding(start = 34.dp, end = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.unlock_keyfile_source_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (expanded) {
                // 展开态：路径落到下一段独占整行，首行只留标签与按钮（占位撑开右侧）
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Text(
                    text = abbreviatePath(path),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.cd_toggle_keyfile_path),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (expanded) {
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 不限行数换行铺满（路径含 `/`，系统排版在斜杠后断行），完整可读、不截断
                maxLines = Int.MAX_VALUE,
                softWrap = true
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

