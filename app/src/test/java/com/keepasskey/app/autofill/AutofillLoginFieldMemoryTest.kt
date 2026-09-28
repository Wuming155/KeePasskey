package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AutofillLoginFieldMemory] 与 [AutofillLoginFieldRecovery] 单元测试（ISSUE-P3-372 AC③）。
 */
class AutofillLoginFieldMemoryTest {

    private lateinit var memory: AutofillLoginFieldMemory

    @Before
    fun setUp() {
        memory = AutofillLoginFieldMemory()
    }

    // ===== 记忆仓库 =====

    @Test
    fun `记忆写读回环与覆盖`() {
        memory.remember("pkg|domain", "u1", "p1")
        val recalled = memory.recall("pkg|domain")
        assertEquals("u1", recalled?.usernameKey)
        assertEquals("p1", recalled?.passwordKey)

        // 同键重写覆盖旧值
        memory.remember("pkg|domain", "u2", "p2")
        assertEquals("u2", memory.recall("pkg|domain")?.usernameKey)
        assertEquals("p2", memory.recall("pkg|domain")?.passwordKey)
    }

    @Test
    fun `上下文键彼此隔离且未知键返回空`() {
        memory.remember("pkgA|d", "u", "p")
        assertNull(memory.recall("pkgB|d"))
        assertNull(memory.recall("pkgA|other"))
    }

    @Test
    fun `空白与双空条目不落记`() {
        memory.remember("k1", null, null)
        assertNull(memory.recall("k1"))
        memory.remember("k2", "  ", null)
        assertNull(memory.recall("k2"))
        memory.remember("k3", null, "p")
        assertEquals("p", memory.recall("k3")?.passwordKey)
    }

    @Test
    fun `容量超限按插入序淘汰最旧上下文`() {
        for (i in 0 until AutofillLoginFieldMemory.MAX_CONTEXTS) {
            memory.remember("ctx$i", "u$i", "p$i")
        }
        memory.remember("overflow", "u", "p")

        assertNull("最旧上下文应被淘汰", memory.recall("ctx0"))
        assertNotNull(memory.recall("overflow"))
        assertNotNull(memory.recall("ctx${AutofillLoginFieldMemory.MAX_CONTEXTS - 1}"))
        // 总量守恒：除被淘汰的 ctx0 外，其余 MAX_CONTEXTS-1 个旧上下文全部仍在
        val survivors = (1 until AutofillLoginFieldMemory.MAX_CONTEXTS).count { memory.recall("ctx$it") != null }
        assertEquals(AutofillLoginFieldMemory.MAX_CONTEXTS - 1, survivors)
    }

    @Test
    fun `锁定回调清空全部记忆`() {
        memory.remember("k", "u", "p")
        memory.onSessionLocked()
        assertNull(memory.recall("k"))
        // 幂等：连续两次清空不抛异常
        memory.onSessionLocked()
        assertNull(memory.recall("k"))
    }

    @Test
    fun `clear 与锁定回调同语义`() {
        memory.remember("k", "u", "p")
        memory.clear()
        assertNull(memory.recall("k"))
    }

    // ===== 回补裁决（纯函数） =====

    private val rememberedBoth = RememberedLoginFields(usernameKey = "u1", passwordKey = "p1")

    @Test
    fun `记忆全键在场且缺密码时回补密码侧`() {
        val recovered = AutofillLoginFieldRecovery.recover(
            remembered = rememberedBoth,
            availableKeys = setOf("u1", "p1", "other"),
            hasCurrentUsername = true,
            hasCurrentPassword = false
        )

        assertNotNull(recovered)
        assertNull("账号侧已在场不重复回补", recovered?.usernameKey)
        assertEquals("p1", recovered?.passwordKey)
    }

    @Test
    fun `当前密码健在时绝不回补`() {
        assertNull(
            AutofillLoginFieldRecovery.recover(
                remembered = rememberedBoth,
                availableKeys = setOf("u1", "p1"),
                hasCurrentUsername = true,
                hasCurrentPassword = true
            )
        )
    }

    @Test
    fun `任一记忆键缺席则整体不回补`() {
        assertNull(
            "密码键缺席 ⇒ 不得只回补账号（防注入失效视图 id）",
            AutofillLoginFieldRecovery.recover(
                remembered = rememberedBoth,
                availableKeys = setOf("u1"),
                hasCurrentUsername = true,
                hasCurrentPassword = false
            )
        )
        assertNull(
            "账号键缺席 ⇒ 同样整体放弃",
            AutofillLoginFieldRecovery.recover(
                remembered = rememberedBoth,
                availableKeys = setOf("p1"),
                hasCurrentUsername = false,
                hasCurrentPassword = false
            )
        )
    }

    @Test
    fun `两侧俱缺时回补两侧`() {
        val recovered = AutofillLoginFieldRecovery.recover(
            remembered = rememberedBoth,
            availableKeys = setOf("u1", "p1"),
            hasCurrentUsername = false,
            hasCurrentPassword = false
        )
        assertEquals("u1", recovered?.usernameKey)
        assertEquals("p1", recovered?.passwordKey)
    }

    @Test
    fun `空记忆与无缺失侧返回空`() {
        assertNull(
            AutofillLoginFieldRecovery.recover(
                remembered = RememberedLoginFields(null, null),
                availableKeys = setOf("x"),
                hasCurrentUsername = false,
                hasCurrentPassword = false
            )
        )
        assertNull(
            "账号侧是仅存记忆且已在场 ⇒ 无可回补内容",
            AutofillLoginFieldRecovery.recover(
                remembered = RememberedLoginFields(usernameKey = "u1", passwordKey = null),
                availableKeys = setOf("u1"),
                hasCurrentUsername = true,
                hasCurrentPassword = false
            )
        )
    }

    @Test
    fun `单侧记忆只在该侧缺失时回补`() {
        val passwordOnly = RememberedLoginFields(usernameKey = null, passwordKey = "p1")
        val recovered = AutofillLoginFieldRecovery.recover(
            remembered = passwordOnly,
            availableKeys = setOf("p1"),
            hasCurrentUsername = true,
            hasCurrentPassword = false
        )
        assertNull(recovered?.usernameKey)
        assertTrue(recovered?.passwordKey == "p1")
    }
}
