package com.keepasskey.app.ui.screens.settings.subscreens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.keepasskey.app.ui.components.BentoCard

/**
 * 关于页的段落组件（ISSUE-P3-188 第 3 目：§187 自 `AboutSettingsScreen.kt` 拆出，**纯结构性改动**）。
 *
 * 口径同 §156 / §159 / §175 / §178 / §183：窄参数、不读 `UiState`、不自持状态——
 * 版本与构建号由页面本体从 `SettingsUiState` 取出后按字符串传入，本文件不碰状态容器。
 */

/** 应用品牌大卡片：圆形 Logo 徽标 + 名称 + 版本号 + 构建号 */
@Composable
internal fun AboutBrandCard(
    appVersion: String,
    buildNumber: String
) {
    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Logo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "KeePasskey for Android",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = appVersion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = buildNumber,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 「技术规格」段：标题 + 三条规格行（加密 / 通行密钥 / 架构） */
@Composable
internal fun AboutSpecSection() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AboutSpecRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.about_spec_enc_title),
            description = stringResource(R.string.about_spec_enc_desc)
        )

        AboutSpecRow(
            icon = Icons.Default.VerifiedUser,
            title = "FIDO2 / WebAuthn Passkey",
            description = stringResource(R.string.about_spec_passkey_desc)
        )

        AboutSpecRow(
            icon = Icons.Default.Code,
            title = stringResource(R.string.about_spec_arch_title),
            description = stringResource(R.string.about_spec_arch_desc)
        )
    }
}

/** 隐私声明卡片 */
@Composable
internal fun AboutPrivacyCard() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.about_privacy_title),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.about_privacy_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}

/** 规格行（§187 随「技术规格」段一并迁入本文件，仍为 `private`：仅被 [AboutSpecSection] 使用） */
@Composable
private fun AboutSpecRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
