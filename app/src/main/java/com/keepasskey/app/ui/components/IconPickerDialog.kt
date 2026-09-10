package com.keepasskey.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R

data class VaultIconItem(
    val id: String,
    val icon: ImageVector
)

val availableVaultIcons = listOf(
    VaultIconItem("folder", Icons.Default.Folder),
    VaultIconItem("key", Icons.Default.Key),
    VaultIconItem("lock", Icons.Default.Lock),
    VaultIconItem("vpn_key", Icons.Default.VpnKey),
    VaultIconItem("public", Icons.Default.Public),
    VaultIconItem("email", Icons.Default.Email),
    VaultIconItem("credit_card", Icons.Default.CreditCard),
    VaultIconItem("cloud", Icons.Default.Cloud),
    VaultIconItem("dns", Icons.Default.Dns),
    VaultIconItem("terminal", Icons.Default.Terminal),
    VaultIconItem("database", Icons.Default.Storage),
    VaultIconItem("security", Icons.Default.Security),
    VaultIconItem("wifi", Icons.Default.Wifi),
    VaultIconItem("phone", Icons.Default.PhoneAndroid),
    VaultIconItem("description", Icons.Default.Description),
    VaultIconItem("work", Icons.Default.Work),
    VaultIconItem("code", Icons.Default.Code),
    VaultIconItem("account_balance", Icons.Default.AccountBalance),
    VaultIconItem("forum", Icons.Default.Forum),
    VaultIconItem("delete", Icons.Default.Delete)
)

/**
 * 图标 id 到本地化无障碍标签资源的映射，
 * 展示时经 stringResource 解析，避免硬编码单一语言文案
 */
private val iconLabelResMap: Map<String, Int> = mapOf(
    "folder" to R.string.icon_label_folder,
    "key" to R.string.icon_label_key,
    "lock" to R.string.icon_label_lock,
    "vpn_key" to R.string.icon_label_passkey,
    "public" to R.string.icon_label_website,
    "email" to R.string.icon_label_email,
    "credit_card" to R.string.icon_label_credit_card,
    "cloud" to R.string.icon_label_cloud,
    "dns" to R.string.icon_label_infra,
    "terminal" to R.string.icon_label_terminal,
    "database" to R.string.icon_label_database,
    "security" to R.string.icon_label_security,
    "wifi" to R.string.icon_label_wifi,
    "phone" to R.string.icon_label_phone,
    "description" to R.string.icon_label_note,
    "work" to R.string.icon_label_work,
    "code" to R.string.icon_label_code,
    "account_balance" to R.string.icon_label_bank,
    "forum" to R.string.icon_label_social,
    "delete" to R.string.icon_label_recycle
)

/** 按图标 id 取标签资源，未匹配时回退到钥匙凭据 */
private fun iconLabelResOf(id: String): Int = iconLabelResMap[id] ?: R.string.icon_label_key

fun getVaultIcon(name: String): ImageVector {
    return availableVaultIcons.find { it.id == name }?.icon ?: Icons.Default.Key
}

/** TASK-15：自定义图标条目（已解码位图 + 库内 UUID hex） */
data class CustomIconItem(
    val id: String,
    val bitmap: ImageBitmap
)

@Composable
fun IconPickerDialog(
    selectedIconName: String,
    onSelectIcon: (String) -> Unit,
    onDismiss: () -> Unit,
    // TASK-15：自定义图标扩展段（默认空 = 沿用纯标准图标行为，分组弹窗等既有调用点零变更）
    customIcons: List<CustomIconItem> = emptyList(),
    selectedCustomIconId: String? = null,
    onSelectCustomIcon: (String) -> Unit = {},
    onUploadClick: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.icon_picker_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // TASK-15：自定义图标区（库内 PNG 图标 + 系统相册上传入口）
                if (customIcons.isNotEmpty() || onUploadClick != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.icon_custom_section),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onUploadClick != null) {
                            TextButton(onClick = onUploadClick) {
                                Icon(
                                    imageVector = Icons.Default.AddAPhoto,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.icon_custom_upload))
                            }
                        }
                    }
                    if (customIcons.isNotEmpty()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (customIcons.size > 8) 200.dp else 100.dp)
                        ) {
                            items(customIcons, key = { it.id }) { item ->
                                val isSelected = item.id == selectedCustomIconId
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerLow
                                        )
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onSelectCustomIcon(item.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = item.bitmap,
                                        contentDescription = stringResource(R.string.icon_custom_section),
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                ) {
                    items(availableVaultIcons) { item ->
                        val isSelected = item.id == selectedIconName
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onSelectIcon(item.id)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = stringResource(iconLabelResOf(item.id)),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_close))
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
