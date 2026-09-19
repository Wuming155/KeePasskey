package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore

/**
 * **特权浏览器白名单**管理页（CM 通道通行密钥可用性）。
 *
 * 背景：CM 通道只在调用方通过官方 `CallingAppInfo.getOrigin(allowlist)` 校验后才能拿到 web
 * origin；未列入白名单的浏览器一律退化为 `android:apk-key-hash`，于是该浏览器上
 * **通行密钥完全不出候选**。内置白名单只含已取证的浏览器（Chrome / Firefox），
 * 其余浏览器须由用户在此**逐项显式启用**——指纹取自该应用自身的签名证书
 * （非手抄常量），换签名或同包名顶替会立即不再匹配。
 *
 * 安全提示如实呈现：启用某个应用等于允许它以任意 web origin 发起凭据请求（浏览器委派的本质）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivilegedBrowserSettingsScreen(
    browsers: List<PasskeyPrivilegedBrowserStore.BrowserApp>,
    onBackClick: () -> Unit,
    onToggle: (packageName: String, enabled: Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 进入即刷新一次已安装浏览器列表（安装/卸载后回到本页能立即反映）
    LaunchedEffect(Unit) { onRefresh() }

    SettingsSubscreenScaffold(
        titleRes = R.string.settings_passkey_privileged_browsers,
        onBackClick = onBackClick,
        modifier = modifier,
        // ISSUE-P3-195：本页顶栏自始与其余九页不同——containerColor 用 background、
        // 且**不传** titleContentColor（标题色由 TopAppBar 自行推导）。§188 收敛时把它拉平
        // 成 surface/onSurface 是一处真实回归，此处按原值复原；传 null 即「不传该参数」。
        topBarContainerColor = MaterialTheme.colorScheme.background,
        topBarTitleContentColor = null,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { PrivilegedBrowserWarningCard() }

            privilegedBrowserListItems(browsers = browsers, onToggle = onToggle)

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/**
 * 特权浏览器页顶部的风险提示卡（errorContainer 底 + WarningAmber 图标 + 说明文案）。
 * §211 自 [PrivilegedBrowserSettingsScreen] 下沉（逐字搬动、零行为变更）；
 * 该页的 Scaffold 装配（含 §199 守卫锚定的顶栏参数与注释）留在宿主文件。
 */
@Composable
private fun PrivilegedBrowserWarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                text = stringResource(R.string.settings_passkey_privileged_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * 特权浏览器列表内容（空态文案 / 逐浏览器行：图标 + 名称 + 包名 + 启用开关）。
 * §211 自 [PrivilegedBrowserSettingsScreen] 下沉（逐字搬动、零行为变更）。
 */
private fun LazyListScope.privilegedBrowserListItems(
    browsers: List<PasskeyPrivilegedBrowserStore.BrowserApp>,
    onToggle: (packageName: String, enabled: Boolean) -> Unit
) {
    if (browsers.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.settings_passkey_privileged_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    } else {
        items(browsers, key = { it.packageName }) { browser ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = browser.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = browser.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = browser.enabled,
                    onCheckedChange = { enabled -> onToggle(browser.packageName, enabled) }
                )
            }
        }
    }
}
