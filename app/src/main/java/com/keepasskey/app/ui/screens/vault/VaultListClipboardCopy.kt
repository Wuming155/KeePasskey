package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.security.tryWrite
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.clipboardCopyFailedMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 列表页**剪贴板复制**动作簇（ISSUE-P2-353 自 `VaultListActionController` 下沉——
 * 控制器行数贴近 tier2 预算（400~500 棘轮），执行体按仓内惯例落独立文件；
 * 对外仍经控制器门面调用，调用方与接线守卫均无感知）。
 *
 * ISSUE-P2-353 AC① 统一口径：复制**仅在剪贴板实际写入成功后**才报成功——
 * 通道缺失（null）或写入抛异常一律经 [onMessage] 如实报失败（[R.string.clipboard_copy_failed]），
 * 不再无条件发「已复制」。密码仍走 CharArray 借用通道、用毕清零（ISSUE-P2-15）。
 *
 * HOTP 硬拒绝留在控制器门面（`VaultListActionController.copyTotpCode` 的第二道防线，
 * 由 `OneTapInteractionWiringTest` 源码守卫钉住），本类只承担「取值 → 写入 → 如实回报」。
 */
internal class VaultListClipboardCopy(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    private val clipboard: ClipboardSecurityChannel?,
    private val onMessage: (UiMessage) -> Unit
) {

    // ISSUE-P3-553：写入成败裁决收口为 [com.keepasskey.app.security.tryWrite]
    // （详情页 `EntryDetailCopyCoordinator` 原本有一份逐字相同的私有实现，同批合并）

    /**
     * 复制密码：按需解密后写入受保护剪贴板。
     * M1 整改：列表投影不携带密码明文，复制时按需单条解密；
     * ISSUE-P2-15：读取与写入全程走 CharArray 借用通道，不经不可擦 String 中转；
     * ISSUE-P2-353 AC①：取不到值 / 通道缺失 / 写入异常一律如实报失败。
     */
    fun copyPassword(entry: UiVaultEntry) {
        scope.launch {
            val chars = repository.getEntryPasswordChars(entry.id)
            if (chars == null) {
                onMessage(clipboardCopyFailedMessage())
                return@launch
            }
            val copied = try {
                clipboard.tryWrite { copySensitiveChars(entry.title, chars) }
            } finally {
                chars.fill('0')
            }
            onMessage(
                if (copied) UiMessage(R.string.vault_copy_password_done, listOf(entry.title))
                else clipboardCopyFailedMessage()
            )
        }
    }

    /** 复制用户名（明文通道）；ISSUE-P2-353 AC①：通道缺失 / 写入异常不得报成功。 */
    fun copyUsername(entry: UiVaultEntry) {
        if (entry.username.isBlank()) {
            onMessage(UiMessage(R.string.vault_copy_username_missing))
            return
        }
        val copied = clipboard.tryWrite { copyPlainText(entry.title, entry.username) }
        onMessage(
            if (copied) UiMessage(R.string.vault_copy_username_done, listOf(entry.username))
            else clipboardCopyFailedMessage()
        )
    }

    /**
     * 复制条目**当前 TOTP 验证码**（ISSUE-P3-184 语义，逐字迁移）：
     * 取值优先走仓库按需通道（ISSUE-P2-90：命中周期缓存时不触碰会话），仅在其不可用时
     * 回退到列表投影的码，避免复制到过期值；无码 / 计算失败如实提示；
     * ISSUE-P2-353 AC①：写入失败（通道缺失 / 异常）同样不得报「已复制」。
     */
    fun copyTotpCode(entry: UiVaultEntry) {
        scope.launch {
            val code = repository.calculateEntryTotp(entry.id)?.code?.takeIf { it.isNotBlank() }
                ?: entry.totpCode?.takeIf { it.isNotBlank() }
            if (code == null) {
                onMessage(UiMessage(R.string.vault_copy_totp_missing))
                return@launch
            }
            val copied = clipboard.tryWrite { copySensitiveText(entry.title, code) }
            onMessage(
                if (copied) UiMessage(R.string.vault_copy_totp_done, listOf(entry.title))
                else clipboardCopyFailedMessage()
            )
        }
    }
}
