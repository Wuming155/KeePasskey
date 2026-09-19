package com.keepasskey.app.ui.screens.conflict

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 冲突页段落组件（§192 自 `ConflictResolutionScreen.kt` 下沉，窄参数、不读 UiState、不自持状态）。
 *
 * 页面根函数保留 `ApplyObscuredTouchFilter()` 首条语句与全部状态编排（`ObscuredTouchWiringTest`
 * 的锚点即在此），本文件只承载纯呈现段。
 */

/** 底部「稍后处理 / 合并并推送」操作条（原 `Scaffold.bottomBar` 内容原样搬移）。 */
@Composable
internal fun ConflictResolutionBottomBar(
    isResolving: Boolean,
    onLaterClick: () -> Unit,
    onMergeClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onLaterClick,
                shape = CapsuleShape,
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text(stringResource(R.string.conflict_btn_later))
            }

            Button(
                onClick = onMergeClick,
                enabled = !isResolving,
                shape = CapsuleShape,
                modifier = Modifier.weight(1.5f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isResolving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.conflict_btn_merging))
                } else {
                    Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.conflict_btn_merge_push))
                }
            }
        }
    }
}

/** 顶部警示说明卡片：冲突条数如实取真实列表长度，不以 0 冒充「无冲突」。 */
@Composable
internal fun ConflictWarningCard(conflictCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.conflict_detected_count, conflictCount),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.conflict_instruction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/** 快捷一键批量选择行（全选本地 / 全选云端）。 */
@Composable
internal fun ConflictBulkChoiceRow(
    onSelectAllLocal: () -> Unit,
    onSelectAllRemote: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onSelectAllLocal,
            modifier = Modifier.weight(1f),
            shape = CapsuleShape
        ) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.conflict_keep_all_local), fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = onSelectAllRemote,
            modifier = Modifier.weight(1f),
            shape = CapsuleShape
        ) {
            Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.conflict_keep_all_remote), fontSize = 12.sp)
        }
    }
}

/**
 * 单侧取值选项行（§192）：本地与云端两行的**唯一差异**是 `choice`、标签文案、标签配色与取值，
 * 原实现把同一结构逐字写了两遍（29 行 × 2）。合并为一份后差异全部走参数，选中态判定
 * （`selectedChoice == choice`）与整行可点 + 单选钮双入口的行为逐字保留。
 */
@Composable
internal fun ConflictFieldChoiceRow(
    choice: FieldChoice,
    labelRes: Int,
    labelColor: Color,
    value: String,
    selectedChoice: FieldChoice,
    isSensitive: Boolean,
    onChoiceSelected: (FieldChoice) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChoiceSelected(choice) }
            .background(
                if (selectedChoice == choice) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selectedChoice == choice,
            onClick = { onChoiceSelected(choice) }
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = labelColor
            )
            Text(
                text = value,
                style = if (isSensitive) MonospacePasswordStyle else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
