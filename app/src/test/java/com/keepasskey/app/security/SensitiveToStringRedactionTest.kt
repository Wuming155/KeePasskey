package com.keepasskey.app.security

import com.keepasskey.app.autofill.AutofillPickerViewModel
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.screens.detail.EntryDetailUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-68（审计 M6）回归：**数据类默认 `toString()` 的明文泄漏面**。
 *
 * 缺陷形态：Kotlin `data class` 的默认 `toString()` 会展开全部属性——这些类型分别持有
 * 条目明文（`revealedPassword` 等）、凭据内容（`username` / `totpCode` / `cardCvv`）、
 * 自动填充明文口令与页面字段当前值。**一次 `log("$state")`、一次异常消息插值、
 * 一次 IDE 求值**（或任何把对象塞进字符串模板的写法）就会把明文写进日志/崩溃报告/堆快照。
 *
 * 本用例以**唯一哨兵串**填充所有敏感字段，断言 `toString()` 输出中**不出现该哨兵**，
 * 从而在编译与运行两层锁住「只出结构摘要」这一契约（对齐 `KdbxEntry` / `ProtectedString`
 * 既有做法）。
 *
 * 覆盖分工（ENG-20261028-12 行为化整改）：
 * 1. JVM 纯对象层面：对 [UiVaultEntry]、[EntryDetailUiState]、[AutofillPickerViewModel.Credentials]
 *    三类数据结构通过哨兵注入执行可观测行为验证；
 * 2. 依赖 Android 平台 `AutofillId` 的 [com.keepasskey.app.autofill.ParsedAutofillNode]：
 *    已由设备用例 `AutofillPlatformContractsDeviceTest` 在真实运行时构造主线程 `AutofillId`
 *    实例验证其不泄漏哨兵且包含结构摘要，原有的源码字符串检索守卫已退役。
 */
class SensitiveToStringRedactionTest {

    /** 全字段共用的唯一哨兵：任何一处内容被展开都会命中 */
    private val sentinel = "S3NT1N3L-D0-N0T-LEAK"

    private fun assertNoSentinel(rendered: String, typeName: String) {
        assertFalse(
            "$typeName.toString() 不得展开任何条目内容/明文（实际：$rendered）",
            rendered.contains(sentinel, ignoreCase = true)
        )
    }

    @Test
    fun `UiVaultEntry 的 toString 不得泄漏条目内容`() {
        val entry = UiVaultEntry(
            id = "entry-1",
            title = sentinel,
            username = sentinel,
            passwordMasked = sentinel,
            url = sentinel,
            passkeyRpId = sentinel,
            totpCode = sentinel,
            notes = sentinel,
            updatedAt = sentinel,
            createdAt = sentinel,
            cardNumberMasked = sentinel,
            cardHolder = sentinel,
            cardExpiry = sentinel,
            cardCvv = sentinel,
            tags = listOf(sentinel),
            autoTypeSequence = sentinel,
            overrideUrl = sentinel,
            customFields = listOf(UiCustomField(id = "f1", key = sentinel, value = sentinel, isProtected = true)),
            attachments = listOf(UiAttachment(id = "a1", fileName = sentinel, fileSizeFormatted = sentinel)),
            revisions = listOf(
                UiEntryRevision(id = "r1", modifiedAt = sentinel, summary = sentinel, username = sentinel, notes = sentinel),
            ),
        )
        assertNoSentinel(entry.toString(), "UiVaultEntry")
    }

    @Test
    fun `EntryDetailUiState 的 toString 不得泄漏明文`() {
        val state = EntryDetailUiState(
            entry = UiVaultEntry(id = "entry-1", title = sentinel, username = sentinel, url = sentinel),
            groupPath = sentinel,
            revealedPassword = sentinel,
            revealedRevisionPasswords = mapOf("r1" to sentinel),
            revealedProtectedFields = mapOf("f1" to sentinel),
            liveTotpCode = sentinel,
            // 调用方包名属**非秘密元数据**（契约允许打印，用于三角定位），此处用真实形态值
            autofillBoundPackage = "com.example.caller",
        )
        val rendered = state.toString()
        assertNoSentinel(rendered, "EntryDetailUiState")
        assertTrue(
            "结构摘要仍应保留非秘密元数据（调用方包名）与条目 id，便于排障：$rendered",
            rendered.contains("com.example.caller") && rendered.contains("entry-1")
        )
    }

    @Test
    fun `AutofillPickerViewModel_Credentials 的 toString 不得泄漏用户名与口令`() {
        val credentials = AutofillPickerViewModel.Credentials(username = sentinel, password = sentinel)
        val rendered = credentials.toString()
        assertNoSentinel(rendered, "Credentials")
        assertTrue(
            "摘要应如实体现「已重写为长度描述」：$rendered",
            rendered.contains("redacted")
        )
    }
}
