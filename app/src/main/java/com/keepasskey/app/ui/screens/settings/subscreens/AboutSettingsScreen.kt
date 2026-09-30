package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 关于 KeePasskey 二级页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSubscreenScaffold(
        titleRes = R.string.settings_about,
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
            // 应用品牌大卡片
            item {
                AboutBrandCard(
                    appVersion = uiState.appVersion,
                    buildNumber = uiState.buildNumber
                )
            }

            item {
                Text(
                    text = stringResource(R.string.about_section_spec),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) { AboutSpecSection() }
            }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) { AboutPrivacyCard() }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "关于页面 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "关于页面 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun AboutSettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        AboutSettingsScreen(
            // 与 SettingsUiState 默认值保持一致，避免设置列表与关于页版本号对不上
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState(),
            onBackClick = {}
        )
    }
}
