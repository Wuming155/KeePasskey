package com.keepasskey.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 长操作的过程反馈段（ISSUE-P3-437 AC②）：不确定段线性进度条 + 可选阶段文案。
 *
 * 「同一过程反馈通道」的共用形态：解锁页（[com.keepasskey.app.ui.screens.unlock.UnlockLoadProgressIndicator]
 * 同款条形进度语义）、云同步状态卡与导入导出卡均挂本组件，阶段型起步——
 * 有阶段性文案就展示，没有时只出跑马灯，绝不显示任何敏感载荷（AC③）。
 */
@Composable
fun OperationProgressSection(label: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        if (!label.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
