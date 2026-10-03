package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.LocalReduceAnimations

/**
 * 密码库锁 Logo 与呼吸光晕底座。
 *
 * §436 重绘为原型的「方形锁块」形态：圆角方块（surfaceContainerLow 底 +
 * outlineVariant 描边）内嵌 primary 锁形图标，右下角叠加一颗 primary 呼吸点
 * （原圆形 + 径向渐变光晕形态退役）。呼吸动画受 [LocalReduceAnimations] 降级偏好裁决。
 *
 * §436 走查回执①：锁块与标题同行（标题左侧）并缩小——由「居中大 Logo」改为「行内小锁块」。
 */
@Composable
internal fun UnlockVaultLogo(uiState: UnlockUiState) {
    Box(
        modifier = Modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (!uiState.hasDatabase) Icons.Default.Lock else if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) Icons.Default.FlashOn else Icons.Default.Lock,
                contentDescription = stringResource(R.string.cd_vault_locked),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            // §436：右下角呼吸点——「库已锁定」的状态化视觉锚
            UnlockPingDot(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
            )
        }
    }
}

/** 呼吸点：外圈 primary 光斑做 alpha / scale 循环，实心点常驻（动效降级时只保留实心点） */
@Composable
private fun UnlockPingDot(modifier: Modifier = Modifier) {
    val dotColor = MaterialTheme.colorScheme.primary
    val reduceAnimations = LocalReduceAnimations.current
    Box(modifier = modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (!reduceAnimations) {
            val transition = rememberInfiniteTransition(label = "UnlockPing")
            // §436：循环周期收敛到 AppNavigationMotion.UNLOCK_PING_CYCLE_MS 具名 token
            // （常驻氛围动效，文件级 tween 豁免已登记理由，见 AppNavigationMotionTest）
            val pingAlpha by transition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = com.keepasskey.app.ui.navigation.AppNavigationMotion.UNLOCK_PING_CYCLE_MS,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "UnlockPingAlpha"
            )
            val pingScale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 2.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = com.keepasskey.app.ui.navigation.AppNavigationMotion.UNLOCK_PING_CYCLE_MS,
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Restart
                ),
                label = "UnlockPingScale"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = pingAlpha
                        scaleX = pingScale
                        scaleY = pingScale
                    }
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

/**
 * ISSUE-P3-368 AC②：打开链进度条。
 *
 * [progress] 非空按 0..1 确定进度渲染；null（KDF 派生等分段不确定段 / 尚无进度）渲染跑马灯。
 * 仅由调用方在 `isLoading` 期间挂出——按钮内嵌进度圈与 isLoading 语义保持原样不回归。
 */
@Composable
internal fun UnlockLoadProgressIndicator(progress: Float?) {
    val barModifier = Modifier
        .fillMaxWidth()
        .height(4.dp)
    if (progress == null) {
        LinearProgressIndicator(modifier = barModifier)
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = barModifier
        )
    }
}

/**
 * ISSUE-P3-368 AC②：加载中的打开进度段（未加载时零渲染，不改动默认态布局）。
 * 解锁页两条路径（快速解锁卡 / 主密码区）共用，收敛调用点行数（long_functions 闸门）。
 *
 * ISSUE-P3-437 AC①：进度条下叠加阶段文案（[UnlockUiState.loadStage]）——
 * 「正在派生密钥… / 正在读取密钥文件… / 正在解封验证…」，让秒级起步的解锁等待全程可解释。
 */
@Composable
internal fun UnlockLoadProgressSection(uiState: UnlockUiState) {
    if (!uiState.isLoading) return
    Spacer(modifier = Modifier.height(6.dp))
    UnlockLoadProgressIndicator(uiState.loadProgress)
    uiState.loadStage?.let { stage ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(stage.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

/**
 * QuickUnlock 卡片区域 (KP2A / KeePassDX 风格)
 */
@Composable
internal fun UnlockQuickUnlockCard(
    uiState: UnlockUiState,
    onBiometricUnlock: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit,
    onToggleReadOnly: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ISSUE-P1-22：本机快速解锁封印为软件密钥时的常驻声明（AC②：
            // UI 常驻声明「不提供硬件级保护」，与降级确认记录绑定，非一次性提示）
            if (uiState.quickUnlockDowngraded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.quick_unlock_software_key_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ISSUE-P2-343：只读开关此前**只画在口令解锁页**，而指纹路径又不消费它
            // ⇒ 用户看不到"这次会怎样"，还会以为先前打开的选择被记住了。现在两条路径共用同一个
            // 开关与同一个真相源（`completeBiometricUnlock` 已改为透传 `openReadOnly`）。
            UnlockReadOnlyRow(
                openReadOnly = uiState.openReadOnly,
                onToggleReadOnly = onToggleReadOnly
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ISSUE-P2-355 AC①：快速解锁卡错误槽位——失败文案不再因卡片无渲染点而静默
            // （回落 STANDARD 由各失败路径负责；本槽位兜住「错误已置、模式仍为 QUICK」的一切窗口）
            uiState.errorMessage?.let { message ->
                Text(
                    text = message.resolveText(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ISSUE-P1-08 统一快速解锁：仅 Class 3 强生物识别经硬件密钥解封（锁屏凭据不再可解封）——
            // 认证入口由系统 BiometricPrompt 承载，不再提供自研 PIN 输入
            Button(
                onClick = onBiometricUnlock,
                enabled = !uiState.isLoading,
                shape = CapsuleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.unlock_biometric_primary_btn),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            UnlockLoadProgressSection(uiState)

            TextButton(
                onClick = { onSwitchMode(UnlockMode.STANDARD) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.unlock_switch_to_full), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "快速解锁卡片 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "快速解锁卡片 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun UnlockQuickUnlockCardPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Column {
            UnlockQuickUnlockCard(
                uiState = UnlockUiState().copy(
                    hasDatabase = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK
                ),
                onBiometricUnlock = {},
                onSwitchMode = {},
                onToggleReadOnly = {}
            )
            // ISSUE-P3-340 规则条文：只读开关新增到本卡片后，两态都要画
            // ——只读态下指纹按钮的语义不同（解开后写操作一律被拒），不能只呈现默认态。
            UnlockQuickUnlockCard(
                uiState = UnlockUiState().copy(
                    hasDatabase = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK,
                    openReadOnly = true
                ),
                onBiometricUnlock = {},
                onSwitchMode = {},
                onToggleReadOnly = {}
            )
            // ISSUE-P2-355 AC①：错误槽位新增后补该态预览——失败文案的间距/换行
            // 此前在编译期与预览导出图里都不可见，只有真机肉眼能看见
            UnlockQuickUnlockCard(
                uiState = UnlockUiState().copy(
                    hasDatabase = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK,
                    errorMessage = UiMessage(R.string.sec_biometric_auth_failed)
                ),
                onBiometricUnlock = {},
                onSwitchMode = {},
                onToggleReadOnly = {}
            )
        }
    }
}

// ISSUE-P3-368：新增「加载中 + 打开进度」可见态后补两态预览——
// 不确定段（null 跑马灯）与确定段（0..45% 实进度条）的间距/高度在默认态预览中不可见
@androidx.compose.ui.tooling.preview.Preview(name = "解锁加载进度（不确定段）- 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "解锁加载进度（确定段）- 浅色", showBackground = true)
@Composable
internal fun UnlockLoadingProgressPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        Column {
            UnlockQuickUnlockCard(
                uiState = UnlockUiState().copy(
                    hasDatabase = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK,
                    isLoading = true,
                    loadProgress = null
                ),
                onBiometricUnlock = {},
                onSwitchMode = {},
                onToggleReadOnly = {}
            )
            UnlockQuickUnlockCard(
                uiState = UnlockUiState().copy(
                    hasDatabase = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK,
                    isLoading = true,
                    loadProgress = 0.45f
                ),
                onBiometricUnlock = {},
                onSwitchMode = {},
                onToggleReadOnly = {}
            )
            // ISSUE-P3-437 AC④：阶段文案可见态预览——派生段文案的间距/换行在无阶段预览中不可见
            UnlockQuickUnlockCard(
                uiState = UnlockUiState().copy(
                    hasDatabase = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK,
                    isLoading = true,
                    loadProgress = null,
                    loadStage = UnlockStage.UNSEALING
                ),
                onBiometricUnlock = {},
                onSwitchMode = {},
                onToggleReadOnly = {}
            )
        }
    }
}
