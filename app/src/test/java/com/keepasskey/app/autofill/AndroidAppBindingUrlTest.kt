package com.keepasskey.app.autofill

import com.keepasskey.app.passkey.DomainMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目「关联应用」绑定串的**写入 ↔ 判据**一致性（TASK-139）。
 *
 * 立规缘由：应用选择器把用户选中的应用写成条目 URL（`android://<包名>`），而填充侧只认
 * `DomainMatcher` 的严格 `android://` 判据。两侧若是各写一份字面量，任一侧改动都会造成
 * **写进去却永远匹配不上**的静默失效（用户以为绑好了，实际候选恒不入选）。
 * 本用例以「构造 → 提取 → 匹配」全链路往返锁死该契约。
 */
class AndroidAppBindingUrlTest {

    @Test
    fun `boundUrl 产出判据原样认得的绑定串`() {
        val packageName = "com.example.bank"
        val bound = AutofillPackageNames.boundUrl(packageName)

        assertEquals("android://com.example.bank", bound)
        assertEquals("提取结果必须等于原包名", packageName, DomainMatcher.extractAndroidBoundPackage(bound))
        assertTrue("绑定串必须通过填充链路的 android 包名判据", DomainMatcher.isAndroidPackageMatch(bound, packageName))
    }

    @Test
    fun `绑定串不会被当成 Web 域命中`() {
        // 反向：Web 绑定条目不得被同形包名冒领（既有 P2-40 语义在本契约下继续成立）
        assertFalse(DomainMatcher.isAndroidPackageMatch("https://com.example.bank", "com.example.bank"))
        assertFalse(DomainMatcher.isAndroidPackageMatch(AutofillPackageNames.boundUrl("com.example.bank"), "evil.com.example.bank"))
    }

    @Test
    fun `绑定 scheme 字面量与判据侧一致`() {
        // scheme 写错（如 android:/ 少一个斜杠）时提取返回 null —— 本断言把该形态固定
        assertEquals("android", AutofillPackageNames.BINDING_SCHEME)
        assertTrue(
            "scheme 变更必须同时改判据侧，否则绑定整体失效",
            AutofillPackageNames.boundUrl("com.example.app")
                .startsWith("${AutofillPackageNames.BINDING_SCHEME}://")
        )
    }
}
