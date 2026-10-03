package com.keepasskey.app.ui.screens.unlock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.DismissibleHelpTip
import com.keepasskey.app.ui.components.HelpTip
import com.keepasskey.app.ui.theme.CapsuleShape

/**
 * 「高级认证凭证」折叠卡段落（§436 规模拆分：自 `UnlockContentSections.kt` 逐字迁出，
 * 使后者退出 tier1；渲染语义零变化）。
 *
 * 头部栏〔图标 + 标题 + 密钥就绪徽章 + 展开箭头〕，展开抽屉内承载密钥文件提示条 /
 * 密钥文件行 / 只读开关行——功能入口与改版前一致，仅布局收拢。
 *
 * [expanded] / [onToggleExpand] 状态外提（ISSUE-P3-340 口径：折叠态必须可被 @Preview 覆盖，
 * 两态预览见 `UnlockScreenPreviews.kt`）。
 */
@Composable
internal fun UnlockAdvancedAuthCard(
    hasKeyFile: Boolean,
    keyFileName: String,
    keyFileSourcePath: String?,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    openReadOnly: Boolean,
    onToggleReadOnly: () -> Unit,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.unlock_advanced_credentials_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                if (hasKeyFile) {
                    UnlockKeyFileReadyBadge()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.cd_toggle_advanced_credentials),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(14.dp)
                ) {
                    // ISSUE-P3-445 AC①：密钥文件高困惑点一次性可关闭提示（关闭态持久化，非模态）
                    DismissibleHelpTip(tip = HelpTip.UNLOCK_KEYFILE)
                    Spacer(modifier = Modifier.height(10.dp))
                    // 密钥文件行（§186 拆至同包 UnlockStandardUnlockSections.kt；判定与绘制逐字保留）
                    UnlockKeyFileRow(
                        hasKeyFile = hasKeyFile,
                        keyFileName = keyFileName,
                        // §433（ISSUE-P3-448 走查续）：加载来源真实路径（默认中间省略 + 按钮展开完整）
                        keyFileSourcePath = keyFileSourcePath,
                        onSelectKeyFile = onSelectKeyFile,
                        onClearKeyFile = onClearKeyFile
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    // H4-只读整改：只读打开开关（§186 拆出）
                    UnlockReadOnlyRow(
                        openReadOnly = openReadOnly,
                        onToggleReadOnly = onToggleReadOnly
                    )
                }
            }
        }
    }
}

/** 「密钥已就绪」徽章：仅在已加载密钥文件时呈现（原型头部右侧的状态胶囊） */
@Composable
private fun UnlockKeyFileReadyBadge() {
    Surface(
        shape = CapsuleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = stringResource(R.string.unlock_keyfile_ready_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
