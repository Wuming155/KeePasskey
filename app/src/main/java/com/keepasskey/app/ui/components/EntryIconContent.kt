package com.keepasskey.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.EntryIcon

/** 条目图标默认绘制边长（列表行图标内容区） */
val DefaultEntryIconSize: Dp = 20.dp

/**
 * 条目图标绘制槽（ISSUE-P3-02 / TASK-49）。
 *
 * 只做纯绘制，不做任何 IO/解码：自定义位图与失败占位均由状态层投影（见
 * [com.keepasskey.app.ui.model.EntryIcon]）后传入。
 *
 * - [EntryIcon.Custom] 且有载荷 → 绘制位图；
 * - [EntryIcon.Custom] 无载荷（解码中/解码失败）或 [EntryIcon.Missing] → 缺图占位；
 * - [EntryIcon.Default] → [placeholderIcon]（各版式各自的既有标准图标语义）。
 */
@Composable
fun EntryIconContent(
    icon: BitmapEntryIcon,
    tint: Color,
    placeholderIcon: ImageVector,
    modifier: Modifier = Modifier,
    contentSize: Dp = DefaultEntryIconSize
) {
    val bitmap = (icon as? EntryIcon.Custom)?.bitmap
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(contentSize)
        )

        icon is EntryIcon.Custom || icon is EntryIcon.Missing -> Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = stringResource(R.string.vault_custom_icon_missing),
            tint = tint,
            modifier = modifier.size(contentSize)
        )

        else -> Icon(
            imageVector = placeholderIcon,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(contentSize)
        )
    }
}
