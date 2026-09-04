package com.keepasskey.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R

data class VaultIconItem(
    val id: String,
    val label: String,
    val icon: ImageVector
)

val availableVaultIcons = listOf(
    VaultIconItem("folder", "文件夹", Icons.Default.Folder),
    VaultIconItem("key", "钥匙凭据", Icons.Default.Key),
    VaultIconItem("lock", "安全锁", Icons.Default.Lock),
    VaultIconItem("vpn_key", "通行密钥", Icons.Default.VpnKey),
    VaultIconItem("public", "网站/域名", Icons.Default.Public),
    VaultIconItem("email", "电子邮箱", Icons.Default.Email),
    VaultIconItem("credit_card", "信用卡/支付", Icons.Default.CreditCard),
    VaultIconItem("cloud", "云端存储", Icons.Default.Cloud),
    VaultIconItem("dns", "基础设施", Icons.Default.Dns),
    VaultIconItem("terminal", "终端指令", Icons.Default.Terminal),
    VaultIconItem("database", "数据库", Icons.Default.Storage),
    VaultIconItem("security", "盾牌安全", Icons.Default.Security),
    VaultIconItem("wifi", "无线网络", Icons.Default.Wifi),
    VaultIconItem("phone", "移动设备", Icons.Default.PhoneAndroid),
    VaultIconItem("description", "安全便签", Icons.Default.Description),
    VaultIconItem("work", "工作生产力", Icons.Default.Work),
    VaultIconItem("code", "研发代码", Icons.Default.Code),
    VaultIconItem("account_balance", "银行金融", Icons.Default.AccountBalance),
    VaultIconItem("forum", "通讯社交", Icons.Default.Forum),
    VaultIconItem("delete", "回收归档", Icons.Default.Delete)
)

fun getVaultIcon(name: String): ImageVector {
    return availableVaultIcons.find { it.id == name }?.icon ?: Icons.Default.Key
}

@Composable
fun IconPickerDialog(
    selectedIconName: String,
    onSelectIcon: (String) -> Unit,
    onDismiss: () -> Unit
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
                                contentDescription = item.label,
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
