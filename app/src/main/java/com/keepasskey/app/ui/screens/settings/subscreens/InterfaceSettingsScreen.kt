package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 界面偏好二级设置页（`ISSUE-P3-444` 修订）。
 *
 * 两项——「密码字段等宽字体」与「动效降级」——原先平铺为设置主页上的一个独立分组，
 * 现收进「界面与显示」组的**第三个入口**：二者都属「界面怎么显示」的细分表现，与
 * 「主题」「列表与导航」同族，平铺在主页会把同一族外观入口拆成不相邻的两块。
 *
 * 两开关的语义与消费方（`LocalMonospaceFields` / `LocalReduceAnimations`）逐字沿用，
 * 本页只换宿主，偏好键、默认值与运行期生效路径一概不变。
 *
 * 渲染形态对齐 `ListDisplaySettingsScreen`：`SettingsSubscreenScaffold` + `LazyColumn` +
 * `ThemeSectionTitle` + `BentoCard`。注意 `BentoCard` 的内容槽是 **`Box`**，故内部只放一个
 * `Column` —— 往 Box 槽里放第二个顶层子节点会**互相叠放**（`ISSUE-P3-337` / `ISSUE-P3-457`）。
 */
@Composable
fun InterfaceSettingsScreen(
    uiState: SettingsUiState,
    onBackClick: () -> Unit,
    onMonospaceFieldsToggle: (Boolean) -> Unit = {},
    onReduceAnimationsToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    SettingsSubscreenScaffold(
        titleRes = R.string.settings_cat_interface,
        onBackClick = onBackClick,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { ThemeSectionTitle(stringResource(R.string.interface_section_fields)) }

            item {
                BentoCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DisplayPrefRow(
                            title = stringResource(R.string.settings_monospace_fields),
                            subtitle = stringResource(R.string.settings_monospace_fields_sub),
                            checked = uiState.monospaceFieldsEnabled,
                            onCheckedChange = onMonospaceFieldsToggle
                        )

                        DisplayPrefRow(
                            title = stringResource(R.string.settings_reduce_animations),
                            subtitle = stringResource(R.string.settings_reduce_animations_sub),
                            checked = uiState.reduceAnimations,
                            onCheckedChange = onReduceAnimationsToggle
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
// 说明：为遵守「不新增 import 语句」约束，@Preview 采用全限定名写法
@androidx.compose.ui.tooling.preview.Preview(name = "界面偏好页 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "界面偏好页 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun InterfaceSettingsScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        InterfaceSettingsScreen(
            uiState = SettingsUiState(),
            onBackClick = {}
        )
    }
}
