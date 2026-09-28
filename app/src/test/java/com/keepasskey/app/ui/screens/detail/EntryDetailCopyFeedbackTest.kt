package com.keepasskey.app.ui.screens.detail

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ISSUE-P2-353 AC①② 回归：详情页复制只在剪贴板**实际写入成功**后才报成功。
 *
 * 缺陷背景（2026-09-28 深度交互审查）：
 * 1. `copyPassword` / `copyUsername` / `copyTotpCode` 在通道缺失（null）时跳过写入却无条件
 *    发「已复制」（假成功）；
 * 2. `copyCustomField` 对**取不到值**静默无反馈，且非保护字段在 UI 层只弹消息**从未写剪贴板**
 *    （`EntryDetailSections` 的非保护分支，AC②——现两保护位统一走本协调器通道）。
 *
 * 直接构造 [EntryDetailCopyCoordinator]（同包 internal），夹具取自 [FakeVaultRepository]：
 * 条目 `1` 含非保护字段「应急联系邮箱」与受保护字段「PIN 备用码」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryDetailCopyFeedbackTest {

    private val messages = mutableListOf<UiMessage>()

    private fun TestScope.coordinator(
        repository: com.keepasskey.app.data.repository.VaultRepository,
        clipboard: ClipboardSecurityChannel?,
        entryId: String? = "1"
    ) = EntryDetailCopyCoordinator(
        vaultRepository = repository,
        clipboardSecurityManager = clipboard,
        scope = this,
        currentEntryId = { entryId },
        isReadOnly = { false },
        entryTitle = { "测试条目" },
        passwordCopyMessage = { UiMessage(R.string.vault_copy_password_done, listOf("测试条目")) },
        liveTotpCode = { null },
        projectedTotpCode = { null },
        showMessage = { messages += it }
    )

    /** ISSUE-P2-353 AC②：非保护字段复制必须**真写剪贴板**（旧实现只弹「已复制」）。 */
    @Test
    fun `非保护自定义字段复制经剪贴板通道真实落值`() = runTest {
        val channel = RecordingClipboardChannel()
        val coordinator = coordinator(FakeVaultRepository(), channel)

        coordinator.copyCustomField("f2", "应急联系邮箱")
        testScheduler.runCurrent()

        assertEquals("非保护字段必须发生剪贴板写入", 1, channel.writeCount)
        assertEquals("emergency@alex.dev", channel.lastText)
        assertEquals(R.string.detail_field_copied, messages.last().resId)
    }

    /** 受保护字段维持原通道（防本次整改顺手改坏 F2 既有行为）。 */
    @Test
    fun `受保护自定义字段复制仍经受保护通道落值`() = runTest {
        val channel = RecordingClipboardChannel()
        val coordinator = coordinator(FakeVaultRepository(), channel)

        coordinator.copyCustomField("f1", "PIN 备用码")
        testScheduler.runCurrent()

        assertEquals(1, channel.writeCount)
        assertEquals("891204", channel.lastText)
        assertEquals(R.string.detail_field_copied, messages.last().resId)
    }

    /** AC② 后半句：通道缺失不得报成功（旧 UI 分支无条件弹「已复制」）。 */
    @Test
    fun `字段复制通道缺失时如实报失败而非已复制`() = runTest {
        val coordinator = coordinator(FakeVaultRepository(), clipboard = null)

        coordinator.copyCustomField("f2", "应急联系邮箱")
        testScheduler.runCurrent()

        assertEquals(R.string.clipboard_copy_failed, messages.last().resId)
        assertNotEquals(R.string.detail_field_copied, messages.last().resId)
    }

    /** AC②：取不到字段值（条目 / 键不存在）如实报错——旧实现静默无反馈。 */
    @Test
    fun `字段取不到值时如实报失败`() = runTest {
        val channel = RecordingClipboardChannel()
        val coordinator = coordinator(FakeVaultRepository(), channel)

        coordinator.copyCustomField("missing-id", "不存在的字段")
        testScheduler.runCurrent()

        assertEquals(R.string.clipboard_copy_failed, messages.last().resId)
        assertEquals("取值失败时不得发生任何剪贴板写入", 0, channel.writeCount)
    }

    /** AC①：密码取不到（chars == null）不得报成功。 */
    @Test
    fun `复制密码取不到值时如实报失败`() = runTest {
        val channel = RecordingClipboardChannel()
        val coordinator = coordinator(FakeVaultRepository(), channel)

        coordinator.copyPassword("测试条目")
        testScheduler.runCurrent()

        assertEquals(R.string.clipboard_copy_failed, messages.last().resId)
        assertEquals(0, channel.writeCount)
    }

    /** AC①：通道缺失时即便密码可读也不得报成功（旧实现无条件发 passwordCopyMessage）。 */
    @Test
    fun `复制密码通道缺失时如实报失败`() = runTest {
        val repository = FakeVaultRepository()
        seedPassword(repository)
        val coordinator = coordinator(repository, clipboard = null)

        coordinator.copyPassword("测试条目")
        testScheduler.runCurrent()

        assertEquals(R.string.clipboard_copy_failed, messages.last().resId)
        assertNotEquals(
            R.string.vault_copy_password_done,
            messages.last().resId
        )
    }

    /** AC① 正向：可读密码 + 可用通道 → 写入成功才报成功，且通道确实收到值。 */
    @Test
    fun `复制密码写入成功后才报成功且通道收到明文`() = runTest {
        val repository = FakeVaultRepository()
        seedPassword(repository)
        val channel = RecordingClipboardChannel()
        val coordinator = coordinator(repository, channel)

        coordinator.copyPassword("测试条目")
        testScheduler.runCurrent()

        assertEquals(R.string.vault_copy_password_done, messages.last().resId)
        assertEquals("detail-copy-password", channel.lastText)
    }

    /** 为夹具条目 `1` 备好可读密码（Fake 的密码按需存储默认为空仓）。 */
    private suspend fun seedPassword(repository: FakeVaultRepository) {
        val entry = repository.getEntries().first().find { it.id == "1" }!!
        repository.saveEntry(entry, passwordChars = "detail-copy-password".toCharArray())
    }

    // ===== ISSUE-P3-371 ③：自定义字段 {REF:} 展开（USER_NAME 非口令面） =====

    /** 正向：字段值含引用 ⇒ 经 resolveFieldReferences 以 USER_NAME 面展开后才落剪贴板。 */
    @Test
    fun `自定义字段含引用时经 USER_NAME 面展开后落剪贴板`() = runTest {
        val channel = RecordingClipboardChannel()
        val resolvedFaces =
            mutableListOf<com.keepasskey.database.fieldref.FieldReferenceEngine.RefField>()
        val repository = object :
            com.keepasskey.app.data.repository.VaultRepository by FakeVaultRepository() {
            override suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? =
                if (fieldKey == "引用字段") REF_RAW.toCharArray() else null

            override suspend fun resolveFieldReferences(
                entryId: String,
                rawText: String,
                consumerField: com.keepasskey.database.fieldref.FieldReferenceEngine.RefField
            ): String? {
                resolvedFaces += consumerField
                return if (rawText == REF_RAW) EXPANDED_VALUE else rawText
            }
        }
        val coordinator = coordinator(repository, channel)

        coordinator.copyCustomField("f9", "引用字段")
        testScheduler.runCurrent()

        assertEquals("展开后的值必须真实写入剪贴板", 1, channel.writeCount)
        assertEquals(EXPANDED_VALUE, channel.lastText)
        assertEquals(R.string.detail_field_copied, messages.last().resId)
        org.junit.Assert.assertTrue(
            "自定义字段为非口令消费点：必须以 USER_NAME 面送解析（{REF:P@…} 掩码语义前提）",
            com.keepasskey.database.fieldref.FieldReferenceEngine.RefField.USER_NAME in resolvedFaces
        )
    }

    /** 兜底：解析层不可用（返回 null）时回退原文，口径与既有消费点一致（不吞字段值）。 */
    @Test
    fun `引用解析不可用时回退字段原文而非空值`() = runTest {
        val channel = RecordingClipboardChannel()
        val repository = object :
            com.keepasskey.app.data.repository.VaultRepository by FakeVaultRepository() {
            override suspend fun getEntryProtectedFieldChars(entryId: String, fieldKey: String): CharArray? =
                if (fieldKey == "引用字段") REF_RAW.toCharArray() else null

            override suspend fun resolveFieldReferences(
                entryId: String,
                rawText: String,
                consumerField: com.keepasskey.database.fieldref.FieldReferenceEngine.RefField
            ): String? = null
        }
        val coordinator = coordinator(repository, channel)

        coordinator.copyCustomField("f9", "引用字段")
        testScheduler.runCurrent()

        assertEquals(1, channel.writeCount)
        assertEquals("解析不可用 ⇒ 回退原文", REF_RAW, channel.lastText)
    }

    private companion object {
        // 虚构引用值（敏感纪律：不得使用真实凭据）
        const val REF_RAW = "{REF:U@T:target-entry}"
        const val EXPANDED_VALUE = "expanded-by-reference"
    }

    /** 记录型剪贴板通道：只记录调用与内容，不触碰 Android 剪贴板（纯 JVM）。 */
    private class RecordingClipboardChannel : ClipboardSecurityChannel {
        var writeCount = 0
            private set
        var lastText: String? = null
            private set

        override fun copySensitiveText(
            label: CharSequence,
            text: CharSequence,
            customTimeoutSeconds: Int?
        ) {
            writeCount++
            lastText = text.toString()
        }

        override fun copySensitiveChars(
            label: CharSequence,
            chars: CharArray,
            customTimeoutSeconds: Int?
        ) {
            writeCount++
            lastText = String(chars)
        }

        override fun copyPlainText(label: CharSequence, text: CharSequence) {
            writeCount++
            lastText = text.toString()
        }
    }
}
