package com.keepasskey.app.passkey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 拒绝原因页的**就地补救**描述（ISSUE-P3-221）。
 *
 * 刻意与 [CredentialRejectionAction] 解耦——本类只承载已解析好的文案与执行回调，
 * 「什么原因给什么动作」的判定留在调用方。
 *
 * @param description 说明句：点名调用方应用、解释「它还不在名单里」（应用名取自系统背书包）
 * @param actionLabel 动作按钮文案（**无插值**，不得写应用名）
 * @param successMessage 授权成功后的就地反馈
 * @param failureMessage 授权失败（读不到签名等 fail-closed 情形）后的就地反馈
 * @param perform 实际执行授权；完成后以 `true`=已生效 / `false`=未生效 回调，**回调须在主线程**
 */
internal class CredentialRejectionRemedy(
    val description: String,
    val actionLabel: String,
    val successMessage: String,
    val failureMessage: String,
    val perform: (onOutcome: (Boolean) -> Unit) -> Unit
)

/**
 * 凭据创建链路 fail-closed 拒绝的原因呈现页（ISSUE-P2-220）。
 *
 * ## 为何是**整页**而非对话框
 *
 * 本页直接渲染在 [BaseCredentialActivity] 自己那个已施加 `FLAG_SECURE` +
 * `HIDE_OVERLAY_WINDOWS` 的**受保护窗口**内：Compose 的 `AlertDialog` 会另开一个对话框窗口，
 * 其 `FLAG_SECURE` 不会从 Activity 窗口传播（见 `SecureDialog`），整页渲染则无此缺口。
 *
 * ## 语义
 *
 * 只做一件事：把 [message]（预定义、无插值的拒绝原因文案）如实呈现给用户。
 *
 * **ISSUE-P3-221 增补**：仅当拒绝原因**确有用户可执行的解法**时，才给出 [remedy]——
 * 用户可**就地**把调用方加入特权名单，无需跳转。动作不改变**本次**请求必然失败的事实
 * （origin 已固定，授权只影响下一次发起，见 [CredentialRejectionAction]）；
 * [remedy] 为 `null` 时布局与 ISSUE-P2-220 原状**逐字一致**。
 */
@Composable
internal fun CredentialRejectionScreen(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    remedy: CredentialRejectionRemedy? = null
) {
    // STATUS_*：等待执行 / 执行中 / 已授权 / 授权失败（状态只在本页存活，无需外提）
    var status by remember { mutableIntStateOf(STATUS_IDLE) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (remedy != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = remedy.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (status == STATUS_FAILED) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = remedy.failureMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            RejectionActionButtons(
                confirmText = confirmText,
                onConfirm = onConfirm,
                remedy = remedy,
                status = status,
                onPerform = {
                    status = STATUS_WORKING
                    remedy?.perform { ok ->
                        status = if (ok) STATUS_ADDED else STATUS_FAILED
                    }
                }
            )
        }
    }
}

@Composable
private fun RejectionActionButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    remedy: CredentialRejectionRemedy?,
    status: Int,
    onPerform: () -> Unit
) {
    when {
        // 授权成功：就地确认 + 单一「完成」（收尾后回浏览器重新发起）
        remedy != null && status == STATUS_ADDED -> {
            Text(
                text = remedy.successMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(confirmText)
            }
        }

        // 有待执行的补救动作：主动作「加入名单」，次动作「退出」
        remedy != null -> {
            Button(
                onClick = onPerform,
                modifier = Modifier.fillMaxWidth(),
                enabled = status != STATUS_WORKING
            ) {
                if (status == STATUS_WORKING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(remedy.actionLabel)
                    }
                } else {
                    Text(remedy.actionLabel)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = status != STATUS_WORKING
            ) {
                Text(confirmText)
            }
        }

        // 无补救：维持 ISSUE-P2-220 原布局
        else -> {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(confirmText)
            }
        }
    }
}

private const val STATUS_IDLE = 0
private const val STATUS_WORKING = 1
private const val STATUS_ADDED = 2
private const val STATUS_FAILED = 3

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "凭据创建拒绝页 - 浅色", showBackground = true)
@Composable
internal fun CredentialRejectionScreenPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CredentialRejectionScreen(
            title = "无法创建通行密钥",
            message = "预览拒绝原因文案：无法确认调用应用对本站点的归属声明，已拒绝创建通行密钥。",
            confirmText = "知道了",
            onConfirm = {}
        )
    }
}

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI
@androidx.compose.ui.tooling.preview.Preview(name = "凭据创建拒绝页 - 两选择", showBackground = true)
@Composable
internal fun CredentialRejectionChoicesPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        CredentialRejectionScreen(
            title = "无法创建通行密钥",
            message = "预览拒绝原因文案：无法确认调用应用对本站点的归属声明，已拒绝创建通行密钥。",
            confirmText = "退出",
            onConfirm = {},
            remedy = CredentialRejectionRemedy(
                description = "预览说明句：「Via」尚未加入特权浏览器白名单。将其加入后，回到浏览器重新发起创建即可。",
                actionLabel = "添加到特权名单",
                successMessage = "已添加「Via」。请回到浏览器重新发起创建。",
                failureMessage = "添加失败：无法读取该应用的签名信息，请稍后在设置中重试。"
            ) { it(false) }
        )
    }
}