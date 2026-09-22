package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.HeroTitleStyle

/**
 * 空状态：当前未配置或选择任何密码库
 */
@Composable
internal fun UnlockEmptyVaultContent(
    uiState: UnlockUiState,
    onNavigateToDatabasePicker: () -> Unit,
    onOpenExistingVault: () -> Unit
) {
    Text(
        text = stringResource(R.string.unlock_empty_vault_title),
        style = HeroTitleStyle,
        color = MaterialTheme.colorScheme.onBackground
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = stringResource(R.string.unlock_empty_vault_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )

    Spacer(modifier = Modifier.height(24.dp))

    uiState.errorMessage?.let { message ->
        Text(
            text = message.resolveText(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 12.dp)
        )
    }

    EmptyVaultActionCard(
        icon = Icons.Default.Add,
        iconTint = MaterialTheme.colorScheme.primary,
        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
        title = stringResource(R.string.unlock_empty_create_btn),
        description = stringResource(R.string.unlock_empty_create_desc),
        onClick = onNavigateToDatabasePicker
    )

    Spacer(modifier = Modifier.height(12.dp))

    EmptyVaultActionCard(
        icon = Icons.Default.FolderOpen,
        iconTint = MaterialTheme.colorScheme.secondary,
        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        title = stringResource(R.string.unlock_empty_open_title),
        description = stringResource(R.string.unlock_empty_open_desc),
        onClick = onOpenExistingVault
    )
}

/**
 * 空状态开始方式卡片（符合 M3 Tonal 色阶与 Bento 结构规范）
 */
@Composable
private fun EmptyVaultActionCard(
    icon: ImageVector,
    iconTint: Color,
    iconContainerColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.large
    BentoCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "空状态引导 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "空状态引导 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockEmptyVaultContentPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UnlockEmptyVaultContent(
                uiState = UnlockUiState(hasDatabase = false),
                onNavigateToDatabasePicker = {},
                onOpenExistingVault = {}
            )
        }
    }
}
