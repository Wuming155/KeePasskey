package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 通行密钥 (Passkey) 二级设置页（ISSUE-P3-432：自 `AutofillSettingsScreen` 拆出）。
 *
 * 承载 **CM 凭据管理器通道**（`KeePasskeyCredentialProviderService`）的四个设置项：
 * 凭据管理器总开关、Passkey 支持、DAL 站点归属校验降级（`ISSUE-P2-240`）、特权浏览器白名单。
 * 传统 AutofillService 通道的开关仍在 [AutofillSettingsScreen]——两通道系统服务与
 * 健康判项（`AutofillHealthPolicy`）相互独立，入口亦随之分开。
 *
 * 行组件（[AutofillSwitchRow] / [AutofillManageEntryRow]）与偏好键、消费方零变化，纯 UI 重组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeySettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onCredentialProviderToggle: (Boolean) -> Unit,
    onPasskeySupportToggle: (Boolean) -> Unit,
    // ISSUE-P2-240：DAL 站点归属声明校验降级（通行密钥注册面）
    onSkipDalVerificationToggle: (Boolean) -> Unit = {},
    // CM 通道特权浏览器白名单入口（让 Chrome / Firefox 之外的浏览器也能用通行密钥）
    onOpenPrivilegedBrowsers: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    SettingsSubscreenScaffold(
        titleRes = R.string.settings_passkey,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Android 系统级凭据提供程序 (Credential Provider)
            item {
                AutofillSectionHeader(R.string.passkey_section_provider)
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AutofillSwitchRow(
                            icon = Icons.Default.VpnKey,
                            title = stringResource(R.string.autofill_cm_title),
                            subtitle = stringResource(R.string.autofill_cm_sub),
                            checked = uiState.credentialProviderEnabled,
                            onCheckedChange = onCredentialProviderToggle
                        )

                        AutofillSwitchRow(
                            icon = Icons.Default.Key,
                            title = stringResource(R.string.autofill_passkey_title),
                            subtitle = stringResource(R.string.autofill_passkey_sub),
                            checked = uiState.passkeySupportEnabled,
                            onCheckedChange = onPasskeySupportToggle
                        )
                    }
                }
            }

            // 2. 注册归属校验与浏览器兼容
            item {
                AutofillSectionHeader(R.string.passkey_section_policy)
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // ISSUE-P2-240：本行的真实语义是**通行密钥注册的站点归属声明校验降级**——
                        // 消费方是 `PasskeyCreateActivity` 的 `PasskeyRegistrationGate`，决定是否对调用方执行
                        // `DigitalAssetLinksVerifier` 远程声明校验。
                        AutofillSwitchRow(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.autofill_skip_dal_title),
                            subtitle = stringResource(R.string.autofill_skip_dal_sub),
                            checked = uiState.skipDalVerification,
                            onCheckedChange = onSkipDalVerificationToggle
                        )

                        // CM 通道特权浏览器白名单：内置仅收录已取证浏览器（Chrome / Firefox），
                        // 其余浏览器须在此显式启用，否则其上的通行密钥不会出现在候选里
                        AutofillManageEntryRow(
                            icon = Icons.Default.Public,
                            title = stringResource(R.string.settings_passkey_privileged_browsers),
                            subtitle = stringResource(R.string.settings_passkey_privileged_sub),
                            onClick = onOpenPrivilegedBrowsers
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "通行密钥设置页 - 浅色", showBackground = true)
@Preview(name = "通行密钥设置页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasskeySettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        PasskeySettingsScreen(
            uiState = SettingsUiState(),
            onBackClick = {},
            onCredentialProviderToggle = {},
            onPasskeySupportToggle = {},
            onSkipDalVerificationToggle = {}
        )
    }
}
