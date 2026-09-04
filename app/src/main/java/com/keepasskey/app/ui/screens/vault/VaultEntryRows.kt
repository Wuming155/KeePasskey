package com.keepasskey.app.ui.screens.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.PasskeyBadge
import com.keepasskey.app.ui.components.TotpMiniGauge
import com.keepasskey.app.ui.components.getVaultIcon
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

/**
 * 文件夹行组件
 */
@Composable
fun KeePassGroupRow(
    group: VaultGroup,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onChangeIcon: () -> Unit,
    onDelete: () -> Unit,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (group.isRecycleBin) Icons.Default.Delete else getVaultIcon(group.iconName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
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

/**
 * 现代多形态条目卡片分发器 (普通密码、通行密钥、银行卡、安全便签)
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
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val containerBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    else MaterialTheme.colorScheme.surfaceContainerLowest

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg)
    ) {
        when (entry.category) {
            EntryCategory.CARD -> {
                CreditCardLayout(entry = entry, isBatchMode = isBatchMode, isSelected = isSelected, onCopyNumber = onCopyPassword)
            }
            EntryCategory.NOTE -> {
                SecureNoteLayout(entry = entry, isBatchMode = isBatchMode, isSelected = isSelected)
            }
            else -> {
                StandardEntryLayout(
                    entry = entry,
                    isRecycled = isRecycled,
                    isBatchMode = isBatchMode,
                    isSelected = isSelected,
                    showUsername = showUsername,
                    showOtp = showOtp,
                    showPasskeyBadge = showPasskeyBadge,
                    showUrl = showUrl,
                    onCopyPassword = onCopyPassword,
                    onCopyUsername = onCopyUsername,
                    onRestore = onRestore,
                    onPurge = onPurge
                )
            }
        }
    }
}

@Composable
private fun StandardEntryLayout(
    entry: UiVaultEntry,
    isRecycled: Boolean,
    isBatchMode: Boolean,
    isSelected: Boolean,
    showUsername: Boolean,
    showOtp: Boolean,
    showPasskeyBadge: Boolean,
    showUrl: Boolean,
    onCopyPassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp).padding(end = 4.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        val (icon, iconTint, containerColor) = when {
            entry.isPasskey -> Triple(Icons.Default.Key, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
            else -> Triple(getVaultIcon(entry.iconName), MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        }

        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(containerColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.isPasskey && showPasskeyBadge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    PasskeyBadge()
                }
            }

            if (showUsername || (showUrl && entry.url.isNotBlank())) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showUsername) {
                        Text(
                            text = if (entry.username.isNotBlank()) entry.username else stringResource(R.string.cardrow_no_username),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (showUsername && showUrl && entry.url.isNotBlank()) {
                        Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (showUrl && entry.url.isNotBlank()) {
                        Text(
                            text = entry.url.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            if (showOtp && entry.totpCode != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.totpCode,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    TotpMiniGauge(remainingSeconds = entry.totpRemainingSeconds)
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        if (!isBatchMode) {
            if (isRecycled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRestore) {
                        Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.btn_restore), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onPurge) {
                        Icon(Icons.Default.DeleteForever, contentDescription = stringResource(R.string.btn_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.username.isNotBlank()) {
                        IconButton(onClick = onCopyUsername) {
                            Icon(Icons.Default.PersonOutline, contentDescription = stringResource(R.string.cd_copy_username), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = onCopyPassword) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.cd_copy_password), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * 银行卡/信用卡拟真卡片视图 (Monica 灵感)
 */
@Composable
private fun CreditCardLayout(
    entry: UiVaultEntry,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onCopyNumber: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
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
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = entry.cardNumberMasked ?: "**** **** **** ****",
                style = MonospacePasswordStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.cardrow_card_holder, entry.cardHolder ?: entry.username),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                entry.cardExpiry?.let { exp ->
                    Text(
                        text = stringResource(R.string.cardrow_expiry, exp),
                        style = MaterialTheme.typography.bodySmall,
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
 */
@Composable
private fun SecureNoteLayout(
    entry: UiVaultEntry,
    isBatchMode: Boolean,
    isSelected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
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
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(22.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = entry.notes.replace("\n", " "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
