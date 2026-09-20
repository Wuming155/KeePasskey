package com.keepasskey.app.ui.screens.database

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.DATABASE_PATH_MAX_CHARS
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.middleEllipsize
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 密码库列表卡片（ISSUE-P3-31 批次 B 自 `DatabasePickerScreen.kt` 拆出，纯结构性改动）。
 *
 * 活动库以主色描边 + 容器色高亮并展示「当前活动」标签；非活动库在右侧暴露删除入口。
 */
@Composable
internal fun VaultDatabaseCard(
    database: VaultDatabaseInfo,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    // ISSUE-P3-230 AC②：缺持久化授权时的重新授权入口（重选同一个文件以重新取得长期授权）
    onRestoreAccess: () -> Unit = {}
) {
    val securityColors = LocalSecurityColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (database.isActive) 2.dp else 1.dp,
                color = if (database.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (database.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            VaultDatabaseCardHeader(database = database, onDelete = onDelete)

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    // ISSUE-P3-132 ②：中段省略保住文件名（末端省略会把最有辨识度的部分砍掉），
                    // 并保证长路径单行不折行；`Ellipsis` 仅作更窄屏幕上的最后兜底
                    text = middleEllipsize(database.path, DATABASE_PATH_MAX_CHARS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = database.lastOpenedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ISSUE-P3-230 AC②：缺持久化授权（重启后打不开）时给出可辨识状态 + 重授入口
            if (database.lacksPersistedPermission) {
                Spacer(modifier = Modifier.height(10.dp))
                VaultDatabasePermissionNotice(onRestoreAccess = onRestoreAccess)
            }
        }
    }
}

/**
 * `ISSUE-P3-230 AC②`：库卡片上的「未获长期授权」状态行。
 *
 * 本行**不是**错误提示（AC③：provider 不支持持久化授权时，本次会话的打开与保存依然正常），
 * 故以「注意」级的 `tertiaryContainer` 呈现，而非 error 色：警示图标 + 说明 + 「重新授权」动作，
 * **整行可点**（比小按钮更易命中，符合本仓 48dp 热区口径）。
 */
@Composable
private fun VaultDatabasePermissionNotice(onRestoreAccess: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .clickable { onRestoreAccess() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.vault_permission_not_persisted),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.vault_permission_restore_action),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

/**
 * 卡片头部行：库类型图标（远程云 = CloudDone / 本地 = Storage）+ 名称与元数据 +
 * 激活徽章 / 删除按钮（互斥：激活态只显示徽章，不提供删除入口）。
 * §207 自 [VaultDatabaseCard] 下沉（逐字搬动、零行为变更）。
 *
 * 路径行**未随迁**：`UiMd3AlignmentWiringTest` 的中段省略守卫锚定 `middleEllipsize(...)`
 * 在宿主文件的现场，路径行留在 [VaultDatabaseCard]。
 */
@Composable
private fun VaultDatabaseCardHeader(
    database: com.keepasskey.app.ui.model.VaultDatabaseInfo,
    onDelete: () -> Unit
) {
    val securityColors = LocalSecurityColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (database.isRemote) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (database.isRemote) Icons.Default.CloudDone else Icons.Default.Storage,
                    contentDescription = null,
                    tint = if (database.isRemote) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = database.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${database.syncType} • ${database.fileSizeFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (database.isActive) {
                Box(
                    modifier = Modifier
                        .clip(CapsuleShape)
                        .background(securityColors.success.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.db_picker_current_active),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = securityColors.success
                    )
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = stringResource(R.string.btn_delete),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@Preview(name = "密码库卡片 - 浅色", showBackground = true)
@Preview(name = "密码库卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VaultDatabaseCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VaultDatabaseCard(
                database = com.keepasskey.app.ui.model.VaultDatabaseInfo(
                    id = "preview-db-local",
                    name = "预览本地密码库",
                    path = "/storage/emulated/0/Documents/preview.kdbx",
                    isRemote = false,
                    syncType = "本地",
                    lastOpenedAt = "2026-01-02 12:00",
                    fileSizeFormatted = "128.0 KB",
                    isActive = true
                ),
                onSelect = {},
                onDelete = {}
            )
            VaultDatabaseCard(
                database = com.keepasskey.app.ui.model.VaultDatabaseInfo(
                    id = "preview-db-remote",
                    name = "预览云端密码库",
                    path = "https://dav.example.com/preview.kdbx",
                    isRemote = true,
                    syncType = "WebDAV",
                    lastOpenedAt = "2026-01-01 09:00",
                    fileSizeFormatted = "256.0 KB",
                    isActive = false
                ),
                onSelect = {},
                onDelete = {}
            )
        }
    }
}
