package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.security.tryWrite
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.clipboardCopyFailedMessage
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.fieldref.FieldReferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.keepasskey.app.ui.model.textArg
import com.keepasskey.app.ui.model.StringsProvider

/**
 * 详情页复制 / 取码动作簇（ISSUE-P3-188 自 `EntryDetailViewModel` 结构性下沉）。
 *
 * 覆盖密码 / 用户名 / 自定义字段（受保护与非保护同通道）/ TOTP 的剪贴板交付与 HOTP 取码；
 * 引用解析与敏感通道选择逐条保持。ISSUE-P2-353 AC①②：**只在剪贴板实际写入成功后才报成功**，
 * 通道缺失 / 写入异常 / 取不到值一律如实报失败（[com.keepasskey.app.security.tryWrite] 统一裁决），见各方法 KDoc。
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
    private val showMessage: (UiMessage) -> Unit,
    // ISSUE-P3-453：错误码 → 本地化文案通道（生产由宿主显式注入真实现）
    private val strings: StringsProvider = StringsProvider { _, _ -> "" },
    /**
     * ISSUE-P3-360 AC③a：TOTP 当前剩余秒数（取详情页节拍通道的实时值）。
     * 缺省 `{ 0 }` = 未知——按非临期处理，既有纯 JVM 构造方零改即兼容。
     */
    private val totpRemainingSeconds: () -> Int = { 0 }
) {

    companion object {
        /** ISSUE-P3-360 AC③a：剩余秒数落入 1..该阈值 时，复制提示改为「即将过期」文案 */
        private const val TOTP_EXPIRING_THRESHOLD_SECONDS = 5
    }

    /**
     * ISSUE-P3-360 AC③b：敏感复制成功消息附「Ns 后自动清空」倒计时——
     * 秒数经 [ClipboardSecurityChannel.scheduledClearSeconds] 同源裁决（null = 不会自动清空，
     * 含用户关闭自动清空的情形，此时不附后缀、不虚称会清空）。
     */
    private suspend fun UiMessage.withClearHint(): UiMessage {
        val seconds = clipboardSecurityManager?.scheduledClearSeconds() ?: return this
        return copy(clipboardClearSeconds = seconds)
    }

    // ISSUE-P3-553：写入成败裁决已收口为 [com.keepasskey.app.security.tryWrite]
    // （与列表页 `VaultListClipboardCopy` 的原逐字重复实现合并为单点）

    /**
     * 复制密码：按需解密后写入受保护剪贴板（M1 整改：不再从条目投影取明文）。
     * ISSUE-P2-353 AC①：取不到密码 / 通道缺失 / 写入异常一律如实报失败，不发成功提示。
     */
    fun copyPassword(title: String) {
        val entryId = currentEntryId() ?: return
        scope.launch {
            // TASK-17：复制前解析 {REF:...} 引用（密码可能指向其他条目的字段）。
            // ISSUE-P2-15：先经 CharArray 借用通道读取；{REF:...} 引擎为 String 文本语义，
            // 此处的 String 物化属引用解析边界，副本已即时清零
            // ISSUE-P0-08：口令消费点声明 P 面（白名单放行受保护引用展开）
            val chars = vaultRepository.getEntryPasswordChars(entryId)
            if (chars == null) {
                showMessage(clipboardCopyFailedMessage())
                return@launch
            }
            val raw = chars.toDisplayString().orEmpty()
            val password = vaultRepository.resolveFieldReferences(
                entryId, raw,
                FieldReferenceEngine.RefField.PASSWORD
            ) ?: raw
            val copied = clipboardSecurityManager.tryWrite { copySensitiveText(title, password) }
            showMessage(
                if (copied) passwordCopyMessage() else clipboardCopyFailedMessage()
            )
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
            val sensitiveChannel = FieldReferenceEngine.containsPasswordFaceReference(username)
            val copied = if (sensitiveChannel) {
                clipboardSecurityManager.tryWrite { copySensitiveText(title, resolved) }
            } else {
                clipboardSecurityManager.tryWrite { copyPlainText(title, resolved) }
            }
            // ISSUE-P2-353 AC①：写入成功才报「已复制」，否则如实报失败
            // ISSUE-P3-360 AC③b：敏感通道附清空倒计时；普通通道不自动清空、如实不附
            showMessage(
                when {
                    !copied -> clipboardCopyFailedMessage()
                    sensitiveChannel -> UiMessage(R.string.detail_username_copied_short).withClearHint()
                    else -> UiMessage(R.string.detail_username_copied_short)
                }
            )
        }
    }

    /**
     * 复制自定义字段（受保护 + **非保护**同通道，ISSUE-P2-353 AC②）：
     * 按需读取 CharArray 独占副本后直通受保护剪贴板，不依赖条目投影明文
     * （投影层受保护字段恒为空；非保护字段此前只弹「已复制」却**未写剪贴板**，现同走本通道）。
     * 取不到字段值（条目 / 字段不存在）或写入失败一律如实报失败（AC②）。
     */
    fun copyCustomField(fieldId: String, fieldKey: String) {
        val entryId = currentEntryId() ?: return
        scope.launch {
            // TASK-10 + ISSUE-P2-15：仓库读取走 CharArray 独占副本，副本用毕清零
            val chars = vaultRepository.getEntryProtectedFieldChars(entryId, fieldKey)
            if (chars == null) {
                showMessage(clipboardCopyFailedMessage())
                return@launch
            }
            val copied = try {
                // ISSUE-P3-371 ③：自定义字段补 {REF:} 展开——与既有消费点同走
                // VaultEntryQueryCoordinator 单点收口的 resolveFieldReferences。
                // 非口令消费点声明 USER_NAME 面：{REF:P@…} 经引擎掩码输出，绝不物化口令明文。
                // 引擎签名为 String 文本语义（既有 4 消费点同款），此处的 String 物化属
                // 引用解析边界，副本随 [chars] 一并在 finally 清零、不落任何状态。
                val raw = chars.toDisplayString().orEmpty()
                val expanded = vaultRepository.resolveFieldReferences(
                    entryId, raw,
                    FieldReferenceEngine.RefField.USER_NAME
                ) ?: raw
                clipboardSecurityManager.tryWrite { copySensitiveText(fieldKey, expanded) }
            } finally {
                chars.fill('0')
            }
            showMessage(
                when {
                    !copied -> clipboardCopyFailedMessage()
                    // ISSUE-P3-360 AC③b：字段复制（含非保护字段同通道）附清空倒计时
                    else -> UiMessage(R.string.detail_field_copied, listOf(fieldKey)).withClearHint()
                }
            )
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
                    // ISSUE-P2-353 AC①：计数器已推进，但剪贴板写入失败时不得报「已复制」
                    val copied = clipboardSecurityManager.tryWrite { copySensitiveText(entryTitle(), code) }
                    showMessage(
                        when {
                            !copied -> clipboardCopyFailedMessage()
                            // ISSUE-P3-360 AC③b：HOTP 复制附清空倒计时
                            else -> UiMessage(R.string.detail_hotp_copied, listOf(code)).withClearHint()
                        }
                    )
                }
                is KdbxResult.Failure ->
                    showMessage(UiMessage(R.string.op_failed, listOf(result.textArg(strings)), isError = true))
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
            // ISSUE-P2-353 AC①：通道缺失 / 写入异常时不得报「已复制」
            val copied = clipboardSecurityManager.tryWrite { copySensitiveText(entryTitle(), code) }
            // ISSUE-P3-360 AC③a：剩余 ≤5s 改发「即将过期」文案（0 = 节拍未起 / 未知，按常态处理）；
            // AC③b：敏感通道附清空倒计时
            val remaining = totpRemainingSeconds()
            val successMessage = if (remaining in 1..TOTP_EXPIRING_THRESHOLD_SECONDS) {
                UiMessage(R.string.detail_totp_copied_expiring, listOf(remaining))
            } else {
                UiMessage(R.string.detail_totp_copied)
            }
            showMessage(
                if (copied) successMessage.withClearHint() else clipboardCopyFailedMessage()
            )
        }
    }
}
