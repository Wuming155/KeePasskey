package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-571` 方案 A 判定内核的正反两态锁定（`AutofillAppBindingWriteBackPolicy`）。
 *
 * 四否决与「仅空 URL」口径的推导见该对象 KDoc；本用例逐一锁定：
 * 只读 / 包名非法 / 摘要不可读 / 浏览器表单 / URL 非空（蕴含 `{REF}` 与「已绑定」两否决）。
 */
class AutofillAppBindingWriteBackPolicyTest {

    // ---------- proposedBindingUrl ----------

    @Test
    fun `绑定URL由包名归一化与boundUrl同源构造`() {
        assertEquals(
            "android://com.tencent.mobileqq",
            AutofillAppBindingWriteBackPolicy.proposedBindingUrl("com.tencent.mobileqq")
        )
        // 归一化（大小写 / 首尾空白）后构造，与判据侧逐字同源
        assertEquals(
            "android://com.tencent.mobileqq",
            AutofillAppBindingWriteBackPolicy.proposedBindingUrl("  COM.Tencent.MobileQQ ")
        )
    }

    @Test
    fun `非法包名的绑定URL为null_不产生降级写入`() {
        assertNull(AutofillAppBindingWriteBackPolicy.proposedBindingUrl("bad"))
        assertNull(AutofillAppBindingWriteBackPolicy.proposedBindingUrl(""))
        assertNull(AutofillAppBindingWriteBackPolicy.proposedBindingUrl("a"))
    }

    // ---------- shouldOffer 正例 ----------

    @Test
    fun `纯App表单_空URL_摘要可读_非只读_询问`() {
        assertTrue(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `URL为空白串视同空URL_仍询问`() {
        assertTrue(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = "",
                entryUrl = "   ",
                sessionReadOnly = false
            )
        )
    }

    // ---------- shouldOffer 反例（逐否决） ----------

    @Test
    fun `只读会话一票否决`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "",
                sessionReadOnly = true
            )
        )
    }

    @Test
    fun `包名非法不询问_fail_closed`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "bad",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `签名摘要不可读不询问_不产生仅包名的降级写入`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = false,
                webDomain = null,
                entryUrl = "",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `浏览器表单不询问_域维度由归属校验通道承载`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = "example.com",
                entryUrl = "",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `URL非空即否决_Web绑定条目不整段替换`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "https://example.com",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `URL已绑定其它应用即否决_不静默撤销原绑定`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "android://com.other.app",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `URL已绑定同包名即否决_已覆盖语义`() {
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "android://com.tencent.mobileqq",
                sessionReadOnly = false
            )
        )
    }

    @Test
    fun `URL含REF引用即否决_被仅空URL口径蕴含`() {
        // 仅空 URL 口径下 {REF} 不可能出现（其必附于非空 URL）——此例锁「蕴含」本身的成立
        assertFalse(
            AutofillAppBindingWriteBackPolicy.shouldOffer(
                callingPackage = "com.tencent.mobileqq",
                callerDigestsReadable = true,
                webDomain = null,
                entryUrl = "{REF:P@I:C\$1234ABCD}",
                sessionReadOnly = false
            )
        )
    }
}
