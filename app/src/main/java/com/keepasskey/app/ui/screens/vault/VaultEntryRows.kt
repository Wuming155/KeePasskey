package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.settings.ListDensity

/**
 * 现代多形态条目卡片分发器 (普通密码、通行密钥、银行卡、安全便签)
 *
 * ISSUE-P3-02：[decorations] 为状态层装配的展示装饰——自定义图标已解码位图 +
 * Notes/URL 字段引用展开文案（受保护字段恒为掩码）；缺省时回退标准图标与条目原文。
 *
 * ISSUE-P3-17：[densitySpec] 驱动行密度；[groupPath] 非空时展示所属分组完整路径
 * （状态层仅在「搜索中 + showGroupInSearchResult 开启」时下发，UI 不做路径计算）。
 *
 * ISSUE-P3-29：本文件原含 `KeePassGroupRow`（已迁 `VaultGroupRow.kt`）与
 * `StandardEntryLayout` / `GroupPathLine`（已迁 `VaultEntryRowLayouts.kt`），
 * 本次为**纯结构性拆分**，本文件仅保留分发器本体。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedVaultEntryRow(
    entry: UiVaultEntry,
    isRecycled: Boolean,
    isBatchMode: Boolean,
    isSelected: Boolean,
    showUsername: Boolean,
    showOtp: Boolean,
    showPasskeyBadge: Boolean,
    showUrl: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCopyPassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    densitySpec: ListDensitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL),
    groupPath: String? = null,
    decorations: EntryDecorations = EntryDecorations.EMPTY,
    modifier: Modifier = Modifier
) {
    // M3 色调层级：常态用 surfaceContainerLow 表达容器感，描边仅保留给选中态强强调
    val isSelectedBorder = if (isSelected) {
        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    val icon = decorations.iconOf(entry)
    val textDisplay = decorations.textOf(entry)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(isSelectedBorder),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        when (entry.category) {
            EntryCategory.CARD -> {
                CreditCardLayout(
                    entry = entry,
                    icon = icon,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    densitySpec = densitySpec,
                    groupPath = groupPath,
                    onCopyNumber = onCopyPassword
                )
            }
            EntryCategory.NOTE -> {
                SecureNoteLayout(
                    entry = entry,
                    icon = icon,
                    notesText = textDisplay.notes,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    densitySpec = densitySpec,
                    groupPath = groupPath
                )
            }
            else -> {
                StandardEntryLayout(
                    entry = entry,
                    icon = icon,
                    urlText = textDisplay.url,
                    isRecycled = isRecycled,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    showUsername = showUsername,
                    showOtp = showOtp,
                    showPasskeyBadge = showPasskeyBadge,
                    showUrl = showUrl,
                    densitySpec = densitySpec,
                    groupPath = groupPath,
                    onCopyPassword = onCopyPassword,
                    onCopyUsername = onCopyUsername,
                    onRestore = onRestore,
                    onPurge = onPurge
                )
            }
        }
    }
}
