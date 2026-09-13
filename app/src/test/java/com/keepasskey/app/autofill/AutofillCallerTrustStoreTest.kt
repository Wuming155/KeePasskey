package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutofillCallerTrustStore] 单元测试（ISSUE-P1-24 AC②/AC④）。
 *
 * 以 `context = null` 的内存语义覆盖「首次绑定显式授权」判定：
 * 首次出现 → 未授权；显式授权后命中；**同包名换签名（证书摘要不同）重新视为首次**；
 * 包名非法 fail-closed；撤销（取消勾选）对称生效。
 */
class AutofillCallerTrustStoreTest {

    private val store = AutofillCallerTrustStore(context = null)

    @Test
    fun `首次出现的调用方未授权`() {
        assertFalse(store.isTrusted("com.example.app", "ABCD1234"))
    }

    @Test
    fun `显式授权后同包名同签名命中`() {
        assertTrue(store.trust("com.example.app", "ABCD1234"))
        assertTrue(store.isTrusted("com.example.app", "ABCD1234"))
    }

    @Test
    fun `同包名换签名重新视为首次出现`() {
        // 重打包 / 换签名是对抗模型的现实路径：证书摘要参与信任键
        store.trust("com.example.app", "ABCD1234")

        assertFalse(store.isTrusted("com.example.app", "FEDC4321"))
    }

    @Test
    fun `证书摘要不可读时退化为仅包名记录`() {
        store.trust("com.example.app", null)

        assertTrue(store.isTrusted("com.example.app", null))
        // 摘要后续可读（如包可见性恢复）时按「包名+摘要」键判定，视为首次
        assertFalse(store.isTrusted("com.example.app", "ABCD1234"))
    }

    @Test
    fun `跨包名不互通`() {
        store.trust("com.example.app", "ABCD1234")

        assertFalse(store.isTrusted("com.other.app", "ABCD1234"))
    }

    @Test
    fun `包名非法时 fail-closed 永不信任`() {
        assertFalse(store.trust("not-a-package", "ABCD1234"))
        assertFalse(store.isTrusted("not-a-package", "ABCD1234"))
    }

    @Test
    fun `撤销授权对称生效`() {
        store.trust("com.example.app", "ABCD1234")

        assertTrue(store.untrust("com.example.app", "ABCD1234"))
        assertFalse(store.isTrusted("com.example.app", "ABCD1234"))
        // 重复撤销：本就未授权，如实返回 false
        assertFalse(store.untrust("com.example.app", "ABCD1234"))
    }
}
