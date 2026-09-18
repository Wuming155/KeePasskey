package com.keepasskey.app.autofill

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

/**
 * 填充确认页的**调用方归属块**（§164 自 `AutofillConfirmActivity.kt` 纯结构性搬家，组件体逐字未改）。
 *
 * 这里是自动填充链路上「不可伪造锚点」的唯一渲染点：调用方包名取自系统结构树、签名 SHA-256 不可读时
 * **如实标注而不以占位冒充**、目标域为归属校验后的值；且**首次出现的显式授权勾选**门控确认按钮
 * （`trustChecked` 由宿主传入，本组件不自持该状态，避免门控条件在两处漂移）。
 *
 * 原为同文件 `private`，本批放宽为 `internal`（仅同模块可见）。宿主侧三处安全接线判据
 * （`AutofillAuthResultWiringTest` 读目标框 id / `AutofillConfirmDeliveryLockTest` 交付锁 /
 * `ObscuredTouchWiringTest` 窗口遮挡）均锚在 Activity 本体，不随本组件搬家 ⇒ 无需扩任何扫描清单。
 */
/**
 * 确认页的调用方归属信息块（ISSUE-P1-24 AC①）。
 *
 * 强制展示不可伪造锚点：调用方包名（系统结构树提供）+ 签名证书 SHA-256（本应用读取；
 * 不可读时如实标注，不以占位冒充）+ 归属校验后的目标域。首次出现的目标（AC②）另附
 * 显式授权勾选——勾选前确认按钮不可用（[trustChecked] 由宿主门控 [CredentialFillConfirmScreen]
 * 的 confirmEnabled）。
 */
@Composable
internal fun AutofillCallerAttributionBlock(
    attribution: AutofillCallerAttribution,
    showTrustCheckbox: Boolean,
    trustChecked: Boolean,
    onTrustCheckedChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.autofill_confirm_caller_package, attribution.packageName),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = attribution.certSha256Hex
                ?.let { stringResource(R.string.autofill_confirm_caller_cert, it) }
                ?: stringResource(R.string.autofill_confirm_cert_unreadable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = attribution.webDomain
                ?.let { stringResource(R.string.autofill_confirm_caller_domain, it) }
                ?: stringResource(R.string.autofill_confirm_domain_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (showTrustCheckbox) {
            Text(
                text = stringResource(R.string.autofill_confirm_first_occurrence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Checkbox(checked = trustChecked, onCheckedChange = onTrustCheckedChange)
                Text(
                    text = stringResource(R.string.autofill_confirm_remember_trust),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Text(
                text = stringResource(R.string.autofill_confirm_already_trusted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI。
// 只预览确认页的无状态归属块；Activity 本体（Hilt 注入 / 生物识别 / 真实窗口）不可预览
@androidx.compose.ui.tooling.preview.Preview(name = "填充确认调用方归属块 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "填充确认调用方归属块 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AutofillCallerAttributionBlockPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AutofillCallerAttributionBlock(
            attribution = AutofillCallerAttribution(
                packageName = "com.example.preview",
                certSha256Hex = "预览签名摘要（占位，非真实证书）",
                webDomain = "example.com",
                firstOccurrence = true
            ),
            showTrustCheckbox = true,
            trustChecked = false,
            onTrustCheckedChange = {}
        )
    }
}
