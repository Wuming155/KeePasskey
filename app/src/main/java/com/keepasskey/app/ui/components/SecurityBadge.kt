package com.keepasskey.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalSecurityColors

/**
 * 通行密钥 Passkey 专属视觉徽章
 */
@Composable
fun PasskeyBadge(
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    Row(
        modifier = modifier
            .clip(CapsuleShape)
            .background(securityColors.passkeyContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Key,
            contentDescription = "Passkey",
            tint = securityColors.passkey,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Passkey",
            style = MaterialTheme.typography.labelMedium,
            color = securityColors.passkey
        )
    }
}

/**
 * 密码强度指示条
 */
@Composable
fun PasswordStrengthBar(
    entropyBits: Int,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    val (color, label, progress) = when {
        entropyBits >= 100 -> Triple(securityColors.success, "极强 ($entropyBits bits)", 1.0f)
        entropyBits >= 64 -> Triple(securityColors.warning, "中等 ($entropyBits bits)", 0.65f)
        else -> Triple(securityColors.danger, "较弱 ($entropyBits bits)", 0.35f)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CapsuleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(CapsuleShape)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

/**
 * TOTP 动态倒计时微型进度环
 */
@Composable
fun TotpMiniGauge(
    remainingSeconds: Int,
    totalSeconds: Int = 30,
    modifier: Modifier = Modifier
) {
    val securityColors = LocalSecurityColors.current
    val progress by animateFloatAsState(
        targetValue = remainingSeconds.toFloat() / totalSeconds.toFloat(),
        label = "TotpProgress"
    )
    val gaugeColor = if (remainingSeconds <= 5) securityColors.danger else securityColors.success

    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(24.dp),
            color = gaugeColor,
            strokeWidth = 2.5.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
        Text(
            text = "$remainingSeconds",
            style = MaterialTheme.typography.labelMedium,
            color = gaugeColor
        )
    }
}
