package com.keepasskey.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.keepasskey.app.R
import com.keepasskey.app.ui.screens.detail.PasswordEntropyEstimator
import com.keepasskey.app.ui.theme.CapsuleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主口令强度门槛（`ISSUE-P2-288` AC①：建库与改密**共用的单一判据**，纯函数）。
 *
 * 门槛取值（登记 `PD-38`）：
 * - 长度下限 [MIN_LENGTH]：低于即**阻断**（官方 KeePass `MinimumLength` 硬失败同族）；
 * - 强度下限 [MIN_STRENGTH_BITS]：低于即**显式二次确认 + 留痕**
 *   （官方 `MinimumQuality` 的 AskYesNo 弱确认同族）；
 * - 强度评估走 crypto 内核 `guessesLog10`（[PasswordEntropyEstimator]，与详情页同一实现），
 *   评估不可用（null）时**不按弱处理**——强度信息缺失不构成拦截理由（如实不谎报）。
 */
object MasterPasswordPolicy {

    const val MIN_LENGTH = 8
    const val MIN_STRENGTH_BITS = 40

    /** 门槛结论（三态：阻断 / 二次确认 / 直通）。 */
    enum class Verdict {
        TOO_SHORT,
        WEAK_REQUIRES_CONFIRM,
        OK
    }

    /** 单一门槛判据（建库与改密禁各写一份）。 */
    fun verdictOf(passwordLength: Int, strengthBits: Int?): Verdict = when {
        passwordLength < MIN_LENGTH -> Verdict.TOO_SHORT
        strengthBits != null && strengthBits < MIN_STRENGTH_BITS -> Verdict.WEAK_REQUIRES_CONFIRM
        else -> Verdict.OK
    }
}

/**
 * 按当前主密码输入异步评估真实熵（crypto 内核，CPU 段下沉 `Dispatchers.Default`，§3 规则 2）。
 *
 * 输入逐键入替换（`copyOf` 新数组即触发重启，前次评估自动取消）；
 * 评估快照用毕即擦，明文不进任何状态流。返回 null = 空输入或评估不可用。
 */
@Composable
fun rememberMasterPasswordStrengthBits(passwordChars: CharArray): Int? {
    var bits by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(passwordChars) {
        val snapshot = passwordChars.copyOf()
        bits = withContext(Dispatchers.Default) {
            try {
                PasswordEntropyEstimator.estimateBits(snapshot)
            } finally {
                snapshot.fill('0')
            }
        }
    }
    return bits
}

/**
 * 弱主口令的**显式二次确认**对话框（`ISSUE-P2-288` AC②：建库与改密共用）。
 * [onConfirm] 为「仍要使用」——调用方须同时留痕（debugLog，不落明文）。
 */
@Composable
fun MasterPasswordWeakConfirmDialog(
    strengthBits: Int?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        // 与主密码输入窗同级的遮罩要求（对话框是独立窗口，须显式 SecureOn）
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(
                text = stringResource(R.string.master_pwd_weak_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.master_pwd_weak_body,
                    strengthBits ?: 0,
                    MasterPasswordPolicy.MIN_STRENGTH_BITS
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = CapsuleShape) {
                Text(stringResource(R.string.master_pwd_weak_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
