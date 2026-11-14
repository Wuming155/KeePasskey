package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用关联写回判定内核的正反两态锁定（[AutofillAppBindingWriteBackPolicy]）。
 *
 * 现行口径（PD-83 改写）：写入 KeePassDX `AndroidApp*` 自定义字段、**不触碰 `URL`**，
 * 故「条目网址非空」「表单带自报域」由旧版否决改为**仍询问**；保留三条 fail-closed
 * （只读 / 包名非法 / 摘要不可读）与「该包名已绑定」的幂等去重。
 */
class AutofillAppBindingWriteBackPolicyTest {

    private fun shouldOffer(
        callingPackage: String = "com.tencent.mobileqq",
        callerDigestsReadable: Boolean = true,
        webDomain: String? = null,
        entryUrl: String = "",
        sessionReadOnly: Boolean = false,
        alreadyBound: Boolean = false
    ): Boolean = AutofillAppBindingWriteBackPolicy.shouldOffer(
        callingPackage = callingPackage,
        callerDigestsReadable = callerDigestsReadable,
        webDomain = webDomain,
        entryUrl = entryUrl,
        sessionReadOnly = sessionReadOnly,
        alreadyBound = alreadyBound
    )

    // ---------- proposedBindingPackage ----------

    @Test
    fun `写入目标是归一化后的纯包名_不带scheme`() {
        assertEquals(
            "com.tencent.mobileqq",
            AutofillAppBindingWriteBackPolicy.proposedBindingPackage("com.tencent.mobileqq")
        )
        assertEquals(
            "com.tencent.mobileqq",
            AutofillAppBindingWriteBackPolicy.proposedBindingPackage("  COM.Tencent.MobileQQ ")
        )
    }

    @Test
    fun `非法包名为null_不产生降级写入`() {
        assertNull(AutofillAppBindingWriteBackPolicy.proposedBindingPackage("bad"))
        assertNull(AutofillAppBindingWriteBackPolicy.proposedBindingPackage(""))
        assertNull(AutofillAppBindingWriteBackPolicy.proposedBindingPackage("a"))
    }

    // ---------- shouldOffer 正例 ----------

    @Test
    fun `纯App表单_空URL_摘要可读_非只读_询问`() {
        assertTrue(shouldOffer())
    }

    @Test
    fun `已有网址仍询问_自定义字段不覆盖URL`() {
        assertTrue(shouldOffer(entryUrl = "https://example.com"))
        assertTrue(shouldOffer(entryUrl = "android://com.other.app"))
    }

    @Test
    fun `表单带自报webDomain仍询问_网页表单同样可写包名`() {
        assertTrue(shouldOffer(webDomain = "example.com"))
        assertTrue(shouldOffer(webDomain = "example.com", entryUrl = "https://example.com"))
    }

    @Test
    fun `URL含REF引用仍询问_引用原文字段不被改写`() {
        assertTrue(shouldOffer(entryUrl = "{REF:P@I:C\$1234ABCD}"))
    }

    @Test
    fun `URL已是旧形态同包名绑定仍询问_旧数据只兼容读取不迁移`() {
        assertTrue(shouldOffer(entryUrl = "android://com.tencent.mobileqq"))
    }

    // ---------- shouldOffer 反例（三条 fail-closed + 幂等去重） ----------

    @Test
    fun `只读会话一票否决`() {
        assertFalse(shouldOffer(sessionReadOnly = true))
    }

    @Test
    fun `包名非法不询问_fail_closed`() {
        assertFalse(shouldOffer(callingPackage = "bad"))
    }

    @Test
    fun `签名摘要不可读不询问_不产生仅包名的库内绑定`() {
        assertFalse(shouldOffer(callerDigestsReadable = false))
    }

    @Test
    fun `该包名已绑定不再重复询问`() {
        assertFalse(shouldOffer(alreadyBound = true, entryUrl = "https://example.com"))
    }

    @Test
    fun `否决优先级_只读与摘要不可读在已有网址时同样生效`() {
        assertFalse(shouldOffer(sessionReadOnly = true, entryUrl = "https://example.com"))
        assertFalse(shouldOffer(callerDigestsReadable = false, webDomain = "example.com"))
    }
}
