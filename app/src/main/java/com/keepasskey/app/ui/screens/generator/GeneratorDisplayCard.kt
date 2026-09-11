package com.keepasskey.app.ui.screens.generator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.BentoCard
import com.keepasskey.app.ui.components.PasswordStrengthBar
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.MonospacePasswordStyle

@Composable
internal fun GeneratorDisplayCard(
    password: String,
    strengthLabel: UiMessage,
    entropyBits: Int,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = rotationAngle,
        animationSpec = tween(durationMillis = 350),
        label = "rotate"
    )
    val haptic = LocalHapticFeedback.current

    BentoCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CapsuleShape
                ) {
                    Text(
                        text = strengthLabel.resolveText(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                IconButton(onClick = {
                    // 重新生成：ContextClick 轻震对应「新值产生」时刻，与旋转动画同步
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    rotationAngle += 360f
                    onRegenerate()
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_regenerate),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.rotate(animatedRotation)
                    )
                }
            }

            // 大字号密码展示区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = password,
                    style = MonospacePasswordStyle.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            PasswordStrengthBar(entropyBits = entropyBits, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CapsuleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.cd_copy_password), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
