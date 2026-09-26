package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.fieldref.FieldReferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 详情页复制 / 取码动作簇（ISSUE-P3-188 自 `EntryDetailViewModel` 结构性下沉，零行为变更）。
 *
 * 覆盖密码 / 用户名 / 受保护字段 / TOTP 的剪贴板交付与 HOTP 取码；语义（引用解析、
 * 敏感通道选择、不谎报成功）逐条保持，见各方法 KDoc。
 */
internal class EntryDetailCopyCoordinator(
    private val vaultRepository: VaultRepository,
    private val clipboardSecurityManager: ClipboardSecurityChannel?,
    private val scope: CoroutineScope,
    private val currentEntryId: () -> String?,
    private val isReadOnly: () -> Boolean,
    private val entryTitle: () -> String,
    private val passwordCopyMessage: () -> UiMessage,
    private val liveTotpCode: () -> String?,
    private val projectedTotpCode: () -> String?,
    private val showMessage: (UiMessage) -> Unit
) {

    /**
     * 复制密码：按需解密后写入受保护剪贴板（M1 整改：不再从条目投影取明文）。
     */
    fun copyPassword(title: String) {
        val entryId = currentEntryId() ?: return
        scope.launch {
            // TASK-17：复制前解析 {REF:...} 引用（密码可能指向其他条目的字段）。
            // ISSUE-P2-15：先经 CharArray 借用通道读取；{REF:...} 引擎为 String 文本语义，
            // 此处的 String 物化属引用解析边界，副本已即时清零
            // ISSUE-P0-08：口令消费点声明 P 面（白名单放行受保护引用展开）
            val raw = vaultRepository.getEntryPasswordChars(entryId).toDisplayString().orEmpty()
            val password = vaultRepository.resolveFieldReferences(
                entryId, raw,
                FieldReferenceEngine.RefField.PASSWORD
            ) ?: raw
            clipboardSecurityManager?.copySensitiveText(title, password)
            showMessage(passwordCopyMessage())
        }
    }

    fun copyUsername(title: String, username: String) {
        val entryId = currentEntryId() ?: return
        scope.launch {
            // TASK-17：用户名可能为 {REF:U@...} 引用，复制前解析
            // ISSUE-P0-08：非口令消费点声明 U 面——UserName 中的 {REF:P@…} 掩码输出，
            // 被引用条目的口令明文绝不写入剪贴板
            val resolved = vaultRepository.resolveFieldReferences(
                entryId, username,
                FieldReferenceEngine.RefField.USER_NAME
            ) ?: username
            // ISSUE-P1-25 AC①：UserName 含口令面引用（{REF:P@…} 或检索面为 P）时，
            // 即便引擎已掩码输出，复制通道仍按敏感数据处理（EXTRA_IS_SENSITIVE + 调度自动擦除）
            if (FieldReferenceEngine.containsPasswordFaceReference(username)) {
                clipboardSecurityManager?.copySensitiveText(title, resolved)
            } else {
                clipboardSecurityManager?.copyPlainText(title, resolved)
            }
            showMessage(UiMessage(R.string.detail_username_copied_short))
        }
    }

    /**
     * 复制受保护自定义字段（F2 整改）：按需解密后写入受保护剪贴板，
     * 不再依赖条目投影中的明文（投影层受保护字段恒为空）。
     */
    fun copyCustomField(fieldId: String, fieldKey: String) {
        val entryId = currentEntryId() ?: return
        scope.launch {
            // TASK-10 + ISSUE-P2-15：仓库读取走 CharArray 独占副本，并直通受保护剪贴板的
            // CharArray 通道（不经中间 String），副本用毕清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entryId, fieldKey)
            if (chars != null) {
                try {
                    clipboardSecurityManager?.copySensitiveChars(fieldKey, chars)
                } finally {
                    chars.fill('0')
                }
                showMessage(UiMessage(R.string.detail_field_copied, listOf(fieldKey)))
            }
        }
    }

    /**
     * ISSUE-P3-49：HOTP 取码——推进计数器（**先落库成功**）并把本次所出之码写入受保护剪贴板。
     *
     * 语义对齐 KeePassXC：只有计数器成功推进后才交付验证码；失败如实上浮，
     * **绝不**产出「未推进」的码（否则同一计数器会被重复使用）。只读会话 / 缺条目 id 为 no-op。
     */
    fun advanceHotp() {
        val entryId = currentEntryId() ?: return
        if (isReadOnly()) return
        scope.launch {
            when (val result = vaultRepository.advanceEntryHotpCounter(entryId)) {
                is KdbxResult.Success -> {
                    val code = result.data.code
                    clipboardSecurityManager?.copySensitiveText(entryTitle(), code)
                    showMessage(UiMessage(R.string.detail_hotp_copied, listOf(code)))
                }
                is KdbxResult.Failure ->
                    showMessage(UiMessage(R.string.op_failed, listOf(result.message)))
            }
        }
    }

    /**
     * ISSUE-P3-184：TOTP 取码——把**当前有效验证码**写入受保护剪贴板。
     *
     * 修复前本入口不存在：详情页 TOTP 卡片的复制按钮只弹「已复制」提示而不写剪贴板
     * （谎报成功，用户粘贴会贴出上一条目的内容）。本方法补齐该写入通道，
     * 与 [copyPassword] 同口径（受保护剪贴板 + 调度自动擦除）。
     *
     * 与 HOTP 的分工（有意差异）：HOTP 之码由**持久化计数器**决定，「复制而不推进」会让同一
     * 计数器被重复使用，故 HOTP 只有取下一个码（[advanceHotp]）而无复制入口；TOTP 之码由时间
     * 决定、天然按周期失效，无此约束 ⇒ 只复制、不推进任何状态。
     *
     * 取值优先级与卡片显示同源（`liveTotpCode ?: entry.totpCode`），并优先走仓库按需通道
     * （ISSUE-P2-90：命中周期缓存时不触碰会话）以取到**当拍**之码；全部取不到时**不谎报成功**。
     *
     * 只读会话不设门槛：本动作是**纯读**（与 [copyPassword] 一致），`isReadOnly` 约束的是编辑入口。
     */
    fun copyTotpCode() {
        val entryId = currentEntryId() ?: return
        scope.launch {
            val code = vaultRepository.calculateEntryTotp(entryId)?.code?.takeIf { it.isNotBlank() }
                ?: liveTotpCode()?.takeIf { it.isNotBlank() }
                ?: projectedTotpCode()?.takeIf { it.isNotBlank() }
            if (code == null) {
                showMessage(UiMessage(R.string.detail_totp_copy_failed))
                return@launch
            }
            clipboardSecurityManager?.copySensitiveText(entryTitle(), code)
            showMessage(UiMessage(R.string.detail_totp_copied))
        }
    }
}
