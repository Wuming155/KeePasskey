package com.keepasskey.app.ui.screens.settings.subscreens

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.screens.settings.SettingsUiState

/**
 * 自动填充「智能识别 / 凭证保存 / 兼容策略」分区卡（`ISSUE-P2-645`：自
 * `AutofillSettingsComponents.kt` 拆分，纯搬运；组合顺序与开关接线未变）。
 */
@Composable
internal fun AutofillCaptureCard(
    uiState: SettingsUiState,
    onOfferSaveCredentialsToggle: (Boolean) -> Unit,
    onOverrideNoAutofillToggle: (Boolean) -> Unit,
    // ISSUE-P3-376：无匹配「就地新建」入口开关（选择器空态按钮 + CM 新建 Action）
    onAutofillOfferCreateToggle: (Boolean) -> Unit,
    blockedPackages: List<String>,
    saveBlockedPackages: List<String>,
    blockedFieldCount: Int,
    onOpenBlacklist: () -> Unit,
    onOpenSaveBlacklist: () -> Unit,
    onOpenFieldBlock: () -> Unit
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AutofillSwitchRow(
                icon = Icons.Default.Save,
                title = stringResource(R.string.autofill_save_prompt_title),
                subtitle = stringResource(R.string.autofill_save_prompt_sub),
                checked = uiState.offerSaveCredentials,
                onCheckedChange = onOfferSaveCredentialsToggle
            )

            AutofillSwitchRow(
                icon = Icons.Default.Security,
                title = stringResource(R.string.autofill_override_title),
                subtitle = stringResource(R.string.autofill_override_sub),
                checked = uiState.overrideNoAutofill,
                onCheckedChange = onOverrideNoAutofillToggle
            )

            AutofillOfferCreateRow(uiState = uiState, onToggle = onAutofillOfferCreateToggle)

            // 一级：按应用屏蔽填充（TASK-44）
            AutofillManageEntryRow(
                icon = Icons.Default.Block,
                title = stringResource(R.string.autofill_blacklist_row_title),
                subtitle = if (blockedPackages.isNotEmpty()) {
                    stringResource(R.string.autofill_blacklist_count, blockedPackages.size)
                } else {
                    stringResource(R.string.autofill_blacklist_empty)
                },
                onClick = onOpenBlacklist
            )

            // 二级：按应用屏蔽**保存提示**（ISSUE-P3-43 ③，与填充屏蔽相互独立）
            AutofillManageEntryRow(
                icon = Icons.Default.Save,
                title = stringResource(R.string.autofill_save_blacklist_row_title),
                subtitle = if (saveBlockedPackages.isNotEmpty()) {
                    stringResource(R.string.autofill_save_blacklist_count, saveBlockedPackages.size)
                } else {
                    stringResource(R.string.autofill_save_blacklist_empty)
                },
                onClick = onOpenSaveBlacklist
            )

            // 三级：字段签名级屏蔽（ISSUE-P3-43 ②，写入入口在手动选择器内）
            AutofillManageEntryRow(
                icon = Icons.Default.Password,
                title = stringResource(R.string.autofill_field_block_row_title),
                subtitle = if (blockedFieldCount > 0) {
                    stringResource(R.string.autofill_field_block_count, blockedFieldCount)
                } else {
                    stringResource(R.string.autofill_field_block_empty)
                },
                onClick = onOpenFieldBlock
            )
        }
    }
}
