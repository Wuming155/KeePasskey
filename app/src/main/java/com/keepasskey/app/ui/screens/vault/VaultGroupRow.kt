package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.EntryIconContent
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.screens.settings.ListDensity

/**
 * 文件夹行组件（ISSUE-P3-29：自 `VaultEntryRows.kt` 拆出，同包同可见性，纯结构性拆分）。
 *
 * ISSUE-P3-22：分组图标与条目侧共用同一投影结果（[EntryIconPresenter] 同一缓存）。
 * - 命中图标池 → 绘制自定义位图；
 * - 池中缺失（引用残留）→ 缺图占位，**不**回退标准图标谎报状态；
 * - 未绑定（[icon] 为 null 或 [EntryIcon.Default]）→ 既有标准矢量图标语义。
 *
 * ISSUE-P3-17：[densitySpec] 驱动行高 / 内边距 / 字号，数值只在 [ListDensityPresenter] 定义。
 */
@Composable
fun KeePassGroupRow(
    group: VaultGroup,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onChangeIcon: () -> Unit,
    onDelete: () -> Unit,
    icon: BitmapEntryIcon? = null,
    densitySpec: ListDensitySpec = ListDensityPresenter.specOf(ListDensity.NORMAL),
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = densitySpec.rowHorizontalPaddingDp.dp,
                vertical = densitySpec.rowVerticalPaddingDp.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(densitySpec.iconContainerSizeDp.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                EntryIconContent(
                    icon = icon ?: EntryIcon.Default(group.iconName),
                    tint = MaterialTheme.colorScheme.primary,
                    placeholderIcon = if (group.isRecycleBin) Icons.Default.Delete else getVaultIcon(group.iconName),
                    contentSize = densitySpec.iconContentSizeDp.dp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = densitySpec.titleFontSizeSp.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (!group.isRecycleBin) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.btn_more), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vault_folder_rename)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { showMenu = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vault_folder_change_icon)) },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            onClick = { showMenu = false; onChangeIcon() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vault_folder_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = stringResource(R.string.cd_enter_group),
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
