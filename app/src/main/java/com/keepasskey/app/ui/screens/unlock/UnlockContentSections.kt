package com.keepasskey.app.ui.screens.unlock

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keepasskey.app.R
import com.keepasskey.app.ui.components.SecurePasswordField
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.resolveText
import com.keepasskey.app.ui.theme.CapsuleShape
import com.keepasskey.app.ui.theme.HeroTitleStyle

/**
 * 密码库锁 Logo 与呼吸光晕底座
 */
@Composable
internal fun UnlockVaultLogo(uiState: UnlockUiState) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (!uiState.hasDatabase) Icons.Default.Lock else if (uiState.unlockMode == UnlockMode.QUICK_UNLOCK) Icons.Default.FlashOn else Icons.Default.Lock,
                contentDescription = stringResource(R.string.cd_vault_locked),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
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

/**
 * 主密码输入框的 supporting 槽位（§280 规模门禁同批自 [UnlockStandardUnlockContent] 逐字迁出，
 * 结构性拆分：渲染语义零变化）。
 *
 * ISSUE-P2-355 AC②：锁定期倒计时由 throttleLockoutRemainingMs 状态直驱、每秒刷新——
 * 一次性快照会随用户输入（onPasswordChangeSecure 清提示）消失，直驱行冲不掉；
 * 下方 errorMessage 的锁定快照与此行同源，锁定期内不重复渲染。
 *
 * ISSUE-P3-457（**单节点契约**）：本函数是 `OutlinedTextField.supportingText` 槽的内容，而该槽在
 * Material3 侧落在 `Box(Modifier.layoutId(SupportingId))` 内部（`TextFieldImpl.kt` 的
 * `TextFieldLayout` / `CutoutTextFieldLayout` 两处同形）——**Box 的同层兄弟互相叠放**，
 * 而非上下流动；且外层只 `heightIn(min = MinSupportingTextLineHeight).wrapContentHeight()`，
 * 量到的是**最高子节点**而非子节点之和。⇒ 本槽只能发射**一个**节点：此前四行 Text 平铺直出，
 * 任意两行同时成立即压在同一行上（真机实证：生物识别失败文案「生物识别验证未通过或已取消」
 * 与「已自动载入记住的密钥文件: usr.dat」叠成一行乱码；「主密码错误…」+「剩余 N 次尝试」
 * 同样命中——后者是开启了失败节流时**每次输错都会出现**的常态组合）。
 * 此槽的叠放风险由 `tools/doc/check_box_slot_children.py` 机检（含框架槽与「多发射助手函数」）。
 */
@Composable
private fun UnlockPasswordSupportingText(uiState: UnlockUiState) {
    // ISSUE-P3-457：Column 是**契约**而非排版偏好——它把四行文案从「Box 同层兄弟」变成
    // 「Column 顺序子节点」，逐行上下分离；单行成立时渲染结果与平铺直出逐像素等价。
    Column {
        val lockoutMs = uiState.throttleLockoutRemainingMs
        val countdownShown = lockoutMs > 0L
        if (countdownShown) {
            Text(
                text = lockoutUiMessage(lockoutMs).resolveText(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        uiState.errorMessage?.let { message ->
            if (!(countdownShown && message.isLockoutCountdown())) {
                Text(
                    text = message.resolveText(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        // ISSUE-P2-355 AC②：失败提示附「剩余 N 次尝试」（节流关闭 / 锁定态 / 已输入时为 null 不呈现）
        if (uiState.errorMessage != null) {
            uiState.throttleAttemptsRemaining?.let { remaining ->
                Text(
                    text = stringResource(R.string.unlock_attempts_remaining, remaining),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        uiState.infoMessage?.let { message ->
            Text(
                text = message.resolveText(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * 完整主密码解锁区（含密钥文件、只读开关与解锁主操作）
 */
@Composable
internal fun UnlockStandardUnlockContent(
    uiState: UnlockUiState,
    onPasswordChange: (CharArray) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSelectKeyFile: () -> Unit,
    onClearKeyFile: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onUnlock: () -> Unit,
    onSwitchMode: (UnlockMode) -> Unit
) {
    // 完整主密码输入框（SecurePasswordField：显示 String 仅存活于组件内部，CharArray 直达 ViewModel）
    SecurePasswordField(
        label = stringResource(R.string.unlock_master_password),
        placeholder = stringResource(R.string.unlock_master_password_hint),
        onPasswordChanged = onPasswordChange,
        isError = uiState.errorMessage != null,
        supportingText = { UnlockPasswordSupportingText(uiState) },
        isPasswordVisible = uiState.isPasswordVisible,
        onToggleVisibility = onTogglePasswordVisibility,
        onDone = onUnlock,
        // ISSUE-P1-04：失败/锁定后令牌递增，驱动输入框擦除显示态，与 VM 主密码清零同步
        wipeToken = uiState.clearPasswordFieldToken,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // ISSUE-P3-445 AC①：密钥文件高困惑点一次性可关闭提示（关闭态持久化，非模态）
    com.keepasskey.app.ui.components.DismissibleHelpTip(
        tip = com.keepasskey.app.ui.components.HelpTip.UNLOCK_KEYFILE
    )

    Spacer(modifier = Modifier.height(12.dp))

    // 密钥文件行（§186 拆至同包 UnlockStandardUnlockSections.kt；判定与绘制逐字保留）
    UnlockKeyFileRow(
        hasKeyFile = uiState.hasKeyFile,
        keyFileName = uiState.keyFileName,
        onSelectKeyFile = onSelectKeyFile,
        onClearKeyFile = onClearKeyFile
    )

    Spacer(modifier = Modifier.height(12.dp))

    // H4-只读整改：只读打开开关（§186 拆出）
    UnlockReadOnlyRow(
        openReadOnly = uiState.openReadOnly,
        onToggleReadOnly = onToggleReadOnly
    )

    Spacer(modifier = Modifier.height(16.dp))

    // 解锁主操作按钮：无密码且无密钥文件时禁用（密钥文件单独解锁时允许空密码）
    UnlockSubmitButton(
        enabled = !uiState.isLoading && (uiState.hasPassword || uiState.hasKeyFile),
        isLoading = uiState.isLoading,
        onUnlock = onUnlock
    )

    // ISSUE-P3-368 AC②：主密码路径的打开进度（确定段 0..1 / 不确定段跑马灯）
    UnlockLoadProgressSection(uiState)

    if (uiState.isQuickUnlockAvailable) {
        UnlockSwitchToQuickEntry(onSwitchToQuickUnlock = { onSwitchMode(UnlockMode.QUICK_UNLOCK) })
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
