package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 条目卡片的专用版式（ISSUE-P3-02 从 VaultEntryRows 拆出，保持单文件行数阈值内）。
 *
 * 两版式均按 [icon] 绘制图标：绑定了自定义 PNG 图标时优先绘制位图，
 * 缺图/解码失败时绘制缺图占位，未绑定时回退各自的标准矢量图标。
 *
 * ISSUE-P3-17：[densitySpec] 驱动行密度；[groupPath] 非空时展示所属分组完整路径
 * （仅搜索结果且开关开启时由状态层下发）。
 */

/**
 * 银行卡/信用卡拟真卡片视图 (Monica 灵感)
 */
@Composable
internal fun CreditCardLayout(
    entry: UiVaultEntry,
    icon: BitmapEntryIcon,
    isBatchMode: Boolean,
    isSelected: Boolean,
    densitySpec: ListDensitySpec,
    groupPath: String?,
    onCopyNumber: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = densitySpec.rowHorizontalPaddingDp.dp,
            vertical = densitySpec.rowVerticalPaddingDp.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Box(
            modifier = Modifier
                .size(densitySpec.iconContainerSizeDp.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            EntryIconContent(
                icon = icon,
                tint = MaterialTheme.colorScheme.secondary,
                placeholderIcon = Icons.Default.CreditCard,
                contentSize = densitySpec.iconContentSizeDp.dp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = densitySpec.titleFontSizeSp.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (groupPath != null) {
                Spacer(modifier = Modifier.height(2.dp))
                GroupPathLine(groupPath = groupPath, fontSizeSp = densitySpec.secondaryFontSizeSp)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.cardNumberMasked ?: "**** **** **** ****",
                style = MonospacePasswordStyle.copy(
                    fontSize = densitySpec.secondaryFontSizeSp.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.cardrow_card_holder, entry.cardHolder ?: entry.username),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.cardExpiry?.let { exp ->
                    Text(
                        text = stringResource(R.string.cardrow_expiry, exp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!isBatchMode) {
            IconButton(onClick = onCopyNumber) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cardrow_copy_number), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 安全便签卡片视图 (Monica 灵感)
 *
 * [notesText] 为展示侧文案（字段引用已展开、受保护字段掩码），由状态层装配后传入。
 */
@Composable
internal fun SecureNoteLayout(
    entry: UiVaultEntry,
    icon: BitmapEntryIcon,
    notesText: String,
    isBatchMode: Boolean,
    isSelected: Boolean,
    densitySpec: ListDensitySpec,
    groupPath: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = densitySpec.rowHorizontalPaddingDp.dp,
            vertical = densitySpec.rowVerticalPaddingDp.dp
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        Box(
            modifier = Modifier
                .size(densitySpec.iconContainerSizeDp.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            EntryIconContent(
                icon = icon,
                tint = MaterialTheme.colorScheme.tertiary,
                placeholderIcon = Icons.Default.Description,
                contentSize = densitySpec.iconContentSizeDp.dp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = densitySpec.titleFontSizeSp.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (groupPath != null) {
                Spacer(modifier = Modifier.height(2.dp))
                GroupPathLine(groupPath = groupPath, fontSizeSp = densitySpec.secondaryFontSizeSp)
            }
            if (notesText.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = notesText.replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = densitySpec.secondaryFontSizeSp.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
