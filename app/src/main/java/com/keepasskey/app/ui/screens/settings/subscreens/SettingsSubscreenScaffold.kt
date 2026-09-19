package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.keepasskey.app.R

/**
 * 二级设置页共用骨架（ISSUE-P3-188 剩余清单第 6 项 / §188）。
 *
 * 收敛的是**逐字相同**的那 20 行：`Scaffold(modifier.fillMaxSize(), containerColor = background)` +
 * `TopAppBar(titleLarge·Bold 标题, ArrowBack + cd_back, topAppBarColors(surface / onSurface))`。
 *
 * **刻意不强行统一的部分以参数暴露**：各页 `snackbarHost` 有无不一（如 `TotpSettingsScreen` 有），
 * 内容区的 `contentWindowInsets` 与 padding 也各自不同 ⇒ 由调用方继续持有，
 * 本骨架不预设 `contentWindowInsets`（沿用 `Scaffold` 默认值，与被替换的各处现状一致）。
 *
 * 顶栏配色同样**存在真实差异**（§189 用 `scaffold_block_fingerprint.py` 分桶查出，见 `ISSUE-P3-195`）：
 * 九页是 `surface` + `onSurface`，`PrivilegedBrowserSettingsScreen` 只有 `containerColor = background`、
 * **不传** `titleContentColor`（标题色由 `TopAppBar` 按 `contentColorFor(background)` 自行推导）。
 * 故二者皆以参数暴露，默认值复刻九页现状；[topBarTitleContentColor] 传 `null` 即「不传该参数」——
 * 不能拿 `Color.Unspecified` 顶替，那会显式写入一个未定色而非沿用推导链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubscreenScaffold(
    @StringRes titleRes: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    topBarContainerColor: Color = MaterialTheme.colorScheme.surface,
    topBarTitleContentColor: Color? = MaterialTheme.colorScheme.onSurface,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(titleRes),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = if (topBarTitleContentColor == null) {
                    // 复刻「未传 titleContentColor」的原形态：让 TopAppBar 走自身推导链
                    TopAppBarDefaults.topAppBarColors(containerColor = topBarContainerColor)
                } else {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarContainerColor,
                        titleContentColor = topBarTitleContentColor
                    )
                }
            )
        }
    ) { innerPadding ->
        content(innerPadding)
    }
}
