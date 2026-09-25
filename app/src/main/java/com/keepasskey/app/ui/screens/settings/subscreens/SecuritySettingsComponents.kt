package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.security.RuntimeRiskLevel
import com.keepasskey.app.ui.components.BentoCard

@Composable
internal fun SecuritySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    // ISSUE-P2-212：等待生物识别验证期间禁用（默认可用，既有调用点行为不变）
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/** [SecurityChoiceChips] 的单个选项：秒值 + 标签资源（选项表与控件同处一文件，避免调用点各写一份）。 */
internal data class SecurityChoice(@StringRes val labelRes: Int, val seconds: Int)

/**
 * 自动锁定超时选项（0 = 立即锁定，-1 = 永不）。
 * 含 120 秒一档：`autoLockTimeoutLabelRes` 早已支持该值，若选项表不含它，
 * 旧版本遗留的 120 会在新控件上**无任何选中项**（比原来的弹窗更难理解）。
 */
internal val AUTO_LOCK_TIMEOUT_CHOICES = listOf(
    SecurityChoice(R.string.sec_lock_now, 0),
    SecurityChoice(R.string.sec_30s, 30),
    SecurityChoice(R.string.sec_1min, 60),
    SecurityChoice(R.string.sec_2min, 120),
    SecurityChoice(R.string.sec_5min, 300),
    SecurityChoice(R.string.sec_15min, 900),
    SecurityChoice(R.string.sec_lock_never, -1)
)

/** 剪贴板清空倒计时选项（-1 = 不清空）。 */
internal val CLIPBOARD_TIMEOUT_CHOICES = listOf(
    SecurityChoice(R.string.sec_clip_15s, 15),
    SecurityChoice(R.string.sec_clip_30s, 30),
    SecurityChoice(R.string.sec_1min, 60),
    SecurityChoice(R.string.sec_2min, 120),
    SecurityChoice(R.string.sec_clip_no_clear, -1)
)

/**
 * 解锁失败重试的**最长锁定时长**选项（秒）。语义是指数退避的封顶值，非固定锁定时长；
 * 域界由 `UnlockThrottleConfigProvider.MIN/MAX_LOCKOUT_SECONDS`（60 ~ 86400）界定，
 * 本表恰为其内的 7 档枚举值。
 */
internal val LOCKOUT_MAX_DURATION_CHOICES = listOf(60, 300, 900, 1800, 3600, 21600, 86400)
    .map { seconds -> SecurityChoice(R.string.sec_throttle_minutes_value, seconds) }

/**
 * 单值设置的**就地**选择控件：一组 `FilterChip`，**一次点击即生效**。
 *
 * 立规缘由：安全设置页的三处单值设置（自动锁定超时 / 最长锁定时长 / 剪贴板清空倒计时）
 * 原本是「可点行 → 弹窗 → 选中」＝ 2 次点击外加一次模态打断；而同应用的同类单值设置
 * （TOTP 刷新周期与位数用 `SingleChoiceSegmentedButtonRow`、外观模式与主题调色盘用卡片直选）
 * 都是**一次点击**。同一类交互在应用内存在两套成本，本控件把这三处对齐到低成本的那一套。
 *
 * 用 `FlowRow` 而非 `SegmentedButtonRow`：三项的选项数分别为 7 / 7 / 5 档，
 * 分段控件在 360dp 下会溢出，流式换行才能完整承载。
 *
 * 选中即回调，**不设确认步骤**——这些偏好均可即时回改，与既有弹窗「选中即 `onSelect` + 关闭」同义。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SecurityChoiceChips(
    title: String,
    options: List<SecurityChoice>,
    selectedValue: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selectedValue == option.seconds,
                    onClick = { onSelect(option.seconds) },
                    label = { Text(text = stringResource(option.labelRes)) }
                )
            }
        }
    }
}

/**
 * ISSUE-P2-08 (ZT-13)：运行环境完整性风险提示卡。
 * 命中可疑 / 攻击特征时明确告知用户当前生效的降级策略，杜绝静默放行。
 *
 * ISSUE-P2-227：[reasons] 非空时改为**逐条点名当前命中项**（由 `RuntimeIntegrityPolicy` 与等级同源产出，
 * 无第二数据源），取代整改前那句「可调试构建**或**非受信任安装来源」的笼统枚举；
 * 清单为空（异常装配）时回落原等级文案，绝不凭空造原因。
 */
@Composable
internal fun IntegrityRiskCard(
    level: RuntimeRiskLevel,
    reasons: List<com.keepasskey.app.security.IntegrityBlockReason> = emptyList()
) {
    val messageRes = if (level == RuntimeRiskLevel.COMPROMISED) {
        R.string.sec_integrity_risk_compromised
    } else {
        R.string.sec_integrity_risk_elevated
    }
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.sec_integrity_risk_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (reasons.isEmpty()) {
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 18.sp
                )
            } else {
                Text(
                    text = stringResource(R.string.sec_integrity_risk_reasons_prefix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 18.sp
                )
                reasons.forEach { reason ->
                    Text(
                        text = "· " + stringResource(reason.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：SecuritySwitchRow 需要 ImageVector 入参（图标为扩展属性，
// 无法以全限定名构造），故预览本文件中仅依赖枚举、可全限定名构造的 IntegrityRiskCard
// 为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "运行环境完整性风险卡 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "运行环境完整性风险卡 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SecuritySettingsComponentsPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        IntegrityRiskCard(
            level = com.keepasskey.app.security.RuntimeRiskLevel.ELEVATED
        )
    }
}
