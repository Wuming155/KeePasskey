package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.settings.ListDensity
import com.keepasskey.app.ui.screens.settings.SearchMatchMode
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 列表与导航偏好二级设置页 (ISSUE-P3-467 自 ThemeSettingsScreen **纯迁位**拆出——
 * 「应用长什么样」（外观/调色盘/语言，留主题页）与「列表和导航怎么表现」分属两种关注点；
 * 且竞品报告 G14 已裁决界面偏好组后续加项（ISSUE-P3-444），主题页只增不减)。
 *
 * 分节函数 `themeListSection` / `themeNavSearchSection` 与内部渲染树逐字沿用
 * `ThemeSettingsListSections.kt` 既有实现（同包 `internal`，零行为变更）；
 * 状态仍走共享 `SettingsViewModel`。
 */
@Composable
fun ListDisplaySettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onShowUsernameInList: (Boolean) -> Unit = {},
    onShowOtpInList: (Boolean) -> Unit = {},
    onShowPasskeyBadge: (Boolean) -> Unit = {},
    onShowUrlInList: (Boolean) -> Unit = {},
    onHideFabOnScrollToggle: (Boolean) -> Unit = {},
    onHapticFeedbackToggle: (Boolean) -> Unit = {},
    // ISSUE-P3-443：底栏 Tab「显隐 + 排序」一体化回调（有序可见 Tab 名单）
    onBottomNavOrderChange: (List<String>) -> Unit = {},
    onShowUnlockedNotificationToggle: (Boolean) -> Unit = {},
    onShowGroupInSearchResultToggle: (Boolean) -> Unit = {},
    onShowGroupInEntryToggle: (Boolean) -> Unit = {},
    onListDensitySelected: (ListDensity) -> Unit = {},
    onAutoActivateSearchOnOpenToggle: (Boolean) -> Unit = {},
    onSearchMatchModeSelected: (SearchMatchMode) -> Unit = {},
    modifier: Modifier = Modifier
) {
    SettingsSubscreenScaffold(
        titleRes = R.string.settings_list_nav,
        onBackClick = onBackClick,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            themeListSection(
                uiState = uiState,
                onListDensitySelected = onListDensitySelected,
                onShowUsernameInList = onShowUsernameInList,
                onShowOtpInList = onShowOtpInList,
                onShowPasskeyBadge = onShowPasskeyBadge,
                onShowUrlInList = onShowUrlInList,
                onHideFabOnScrollToggle = onHideFabOnScrollToggle,
                onHapticFeedbackToggle = onHapticFeedbackToggle,
                onBottomNavOrderChange = onBottomNavOrderChange
            )

            themeNavSearchSection(
                uiState = uiState,
                onShowUnlockedNotificationToggle = onShowUnlockedNotificationToggle,
                onAutoActivateSearchOnOpenToggle = onAutoActivateSearchOnOpenToggle,
                onShowGroupInSearchResultToggle = onShowGroupInSearchResultToggle,
                onShowGroupInEntryToggle = onShowGroupInEntryToggle,
                onSearchMatchModeSelected = onSearchMatchModeSelected
            )

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "列表与导航页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "列表与导航页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ListDisplaySettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        ListDisplaySettingsScreen(
            uiState = com.keepasskey.app.ui.screens.settings.SettingsUiState(),
            onBackClick = {}
        )
    }
}
