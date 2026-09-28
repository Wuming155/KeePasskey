package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISSUE-P3-360 AC③ 回归：敏感复制文案的「清空倒计时 + TOTP 临期」两条契约。
 *
 * 缺陷背景：仅密码复制带「Ns 后清空」，字段 / HOTP / 生成器（本类覆盖详情侧三点）不带；
 * `copyTotpCode` 不看剩余秒数，剩 1~2 秒照样报「已复制」。
 *
 * 契约：
 * 1. 通道报出 [ClipboardSecurityChannel.scheduledClearSeconds] 时，成功消息必须携带
 *    同一秒数（`UiMessage.clipboardClearSeconds`，展示层拼后缀）；通道缺省（null）时**不附**；
 * 2. TOTP 剩余秒数落入 1..5 ⇒ 成功文案切 `detail_totp_copied_expiring`（含剩余秒插值）；
 *    0（节拍未起 / 未知）按常态 `detail_totp_copied` 处理，既有用例口径不回潮。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailCopyClearHintTest {

    private val messages = mutableListOf<UiMessage>()

    private fun TestScope.coordinator(
        channel: ClipboardSecurityChannel?,
        totpRemaining: () -> Int = { 0 },
        entryId: String = "1"
    ) = EntryDetailCopyCoordinator(
        vaultRepository = FakeVaultRepository(),
        clipboardSecurityManager = channel,
        scope = this,
        currentEntryId = { entryId },
        isReadOnly = { false },
        entryTitle = { "测试条目" },
        passwordCopyMessage = { UiMessage(R.string.vault_copy_password_done, listOf("测试条目")) },
        liveTotpCode = { null },
        projectedTotpCode = { null },
        showMessage = { messages += it },
        totpRemainingSeconds = totpRemaining
    )

    /** 剩余 ≤5s：成功文案切临期插值版，且清空秒数照附。 */
    @Test
    fun `TOTP 临期复制发 expiring 文案并携带清空秒数`() = runTest {
        // 夹具条目 `2`（FakeVaultRepository 带 TOTP）；`1` 无 TOTP 走失败路径，测不到成功文案
        val coordinator = coordinator(
            channel = ScheduledChannel(seconds = 30),
            totpRemaining = { 4 },
            entryId = "2"
        )
        coordinator.copyTotpCode()
        testScheduler.runCurrent()

        assertEquals("临期文案必须只出现一次", 1, messages.size)
        val last = messages.last()
        assertEquals("临期文案必须带剩余秒插值", R.string.detail_totp_copied_expiring, last.resId)
        assertEquals(listOf<Any>(4), last.args)
        assertEquals("清空倒计时必须与通道同源", 30, last.clipboardClearSeconds)
    }

    /** 剩余 0（未知）按常态文案；通道缺省 null 时不得附清空后缀。 */
    @Test
    fun `TOTP 常态复制不带临期文案且缺省通道不附清空秒数`() = runTest {
        val coordinator = EntryDetailCopyCoordinator(
            vaultRepository = FakeVaultRepository(),
            clipboardSecurityManager = NullSecondsChannel(),
            scope = this,
            currentEntryId = { "2" },
            isReadOnly = { false },
            entryTitle = { "测试条目" },
            passwordCopyMessage = { UiMessage(R.string.vault_copy_password_done) },
            liveTotpCode = { null },
            projectedTotpCode = { null },
            showMessage = { messages += it },
            totpRemainingSeconds = { 0 }
        )
        coordinator.copyTotpCode()
        testScheduler.runCurrent()

        val last = messages.last()
        assertEquals(R.string.detail_totp_copied, last.resId)
        assertNull("通道未报秒数时不得附清空后缀", last.clipboardClearSeconds)
    }

    /** 字段复制（受保护通道）同样携带通道报出的清空秒数。 */
    @Test
    fun `字段复制成功消息携带通道清空秒数`() = runTest {
        val coordinator = coordinator(ScheduledChannel(seconds = 60))

        coordinator.copyCustomField("f1", "PIN 备用码")
        testScheduler.runCurrent()

        val last = messages.last()
        assertEquals(R.string.detail_field_copied, last.resId)
        assertEquals(60, last.clipboardClearSeconds)
    }

    /** 通道缺省实现（scheduledClearSeconds = null）⇒ 字段消息不附秒数。 */
    @Test
    fun `缺省通道的字段复制不附清空秒数`() = runTest {
        val coordinator = coordinator(NullSecondsChannel())

        coordinator.copyCustomField("f1", "PIN 备用码")
        testScheduler.runCurrent()

        assertEquals(R.string.detail_field_copied, messages.last().resId)
        assertNull(messages.last().clipboardClearSeconds)
    }

    /** 记录型通道 + 显式报出的清空秒数（覆盖接口默认实现）。 */
    private class ScheduledChannel(private val seconds: Int) : ClipboardSecurityChannel {
        override fun copySensitiveText(label: CharSequence, text: CharSequence, customTimeoutSeconds: Int?) = Unit
        override fun copySensitiveChars(label: CharSequence, chars: CharArray, customTimeoutSeconds: Int?) = Unit
        override fun copyPlainText(label: CharSequence, text: CharSequence) = Unit
        override suspend fun scheduledClearSeconds(): Int? = seconds
    }

    /** 记录型通道 + 接口缺省（null = 不会自动清空）。 */
    private class NullSecondsChannel : ClipboardSecurityChannel {
        override fun copySensitiveText(label: CharSequence, text: CharSequence, customTimeoutSeconds: Int?) = Unit
        override fun copySensitiveChars(label: CharSequence, chars: CharArray, customTimeoutSeconds: Int?) = Unit
        override fun copyPlainText(label: CharSequence, text: CharSequence) = Unit
    }
}
