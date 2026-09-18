package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R

/**
 * TASK-47：泄露密码审计行的展示模型（按检测状态派生，状态与文案一一对应）
 */
internal data class LeakRowPresentation(
    val icon: ImageVector,
    val iconTint: Color,
    val subtitle: String,
    val statusText: String,
    val isWarning: Boolean
)

/**
 * TASK-47：已泄露密码检测开关行。
 *
 * 「默认关闭 + 显式开启」是可审计的隐私边界：关闭时本应用不发起任何泄露查询请求，
 * 开启时以 k-匿名方式比对（仅上送密码 SHA-1 前 5 位），说明文案如实披露数据流向。
 *
 * 本批整改：本行位于页面**最底部**（全部审计行之后），而触发扫描的「重新扫描」按钮在页面
 * **顶部**的评分卡里；开启开关后若不同时就地扫描，用户必须滚回顶部再点一次才能看到任何结果。
 * 现由 [onToggle] 的调用方在**开启方向**顺带触发一次扫描（见 `SettingsViewModel.enableBreachCheckAndScan`），
 * 并在此行内给出「正在扫描」反馈——把「滚回顶部才知道有没有在做事」的盲区收掉。
 *
 * @param isScanning 扫描进行中（就地反馈；关闭方向与未扫描时为 false）
 */
@Composable
internal fun BreachCheckToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isScanning: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.health_breach_toggle_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.health_breach_toggle_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                if (isScanning) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.health_scanning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * 单条审计项目卡片
 */
@Composable
internal fun HealthAuditRowItem(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    statusText: String,
    isWarning: Boolean,
    modifier: Modifier = Modifier
) {
    // 「需注意」属 warning 语义，不用 error 红，避免与真正的错误/危险态混淆
    val securityColors = com.keepasskey.app.ui.theme.LocalSecurityColors.current
    val warningBorder = securityColors.warning.copy(alpha = 0.35f)
    val warningChipBg = securityColors.warning.copy(alpha = 0.14f)
    val warningChipFg = securityColors.warning
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(
                width = 1.dp,
                color = if (isWarning) warningBorder
                else MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.large
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isWarning) warningChipBg
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isWarning) warningChipFg
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：HealthAuditRowItem 需要 ImageVector / Color 入参（图标为扩展属性，无法以全限定名构造），
// 故预览本文件中同样可独立渲染且无需额外依赖的 BreachCheckToggleRow
// 为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "泄露密码检测开关行 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "泄露密码检测开关行 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun HealthCheckComponentsPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        BreachCheckToggleRow(
            enabled = true,
            onToggle = {}
        )
    }
}
