package com.keepasskey.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P0-531`：`ProtectedString` 的**展示面**安全读取原语回归。
 *
 * 锁定三件事（三层整改的可执行判据）：
 * ① 未清零实例：展示面读与 fail-fast 读**逐字一致**——降级不得改变正常路径语义；
 * ② 已清零实例：展示面读返回确定值（`""` / null）而**不抛**——这是 UI 投影链与并发擦除
 *    撞车时的兜底（真机闪退根因，取证见 `docs/ACTIVE_ISSUES.md` 的 `ISSUE-P0-531`）；
 * ③ 已清零实例：`readString()` / `readUtf8()` / `readChars()` **仍然 fail-fast**——
 *    写路径的防线不得因本项整改被削弱（就地降级会把空值写进用户的库，属数据损坏）。
 */
class ProtectedStringDisplayReadTest {

    @Test
    fun `未清零时展示面读取与 fail-fast 读取逐字一致`() {
        val secret = ProtectedString("alice@example.com", isProtected = true)

        assertEquals(secret.readString(), secret.readStringForDisplay())
        assertEquals("alice@example.com", secret.readStringForDisplay())

        val plainBytes = secret.readUtf8()
        val displayBytes = secret.readUtf8ForDisplay()
        try {
            assertArrayEquals(plainBytes, displayBytes)
        } finally {
            displayBytes?.fill(0)
            plainBytes.fill(0)
        }
    }

    @Test
    fun `已清零时展示面读取降级为确定值不抛`() {
        val secret = ProtectedString("bob", isProtected = true)
        secret.clear()

        assertEquals("", secret.readStringForDisplay())
        assertEquals("(已擦除)", secret.readStringForDisplay("(已擦除)"))
        assertNull("已清零时字节面返回 null（空数组会被误读为「字段存在但为空」）", secret.readUtf8ForDisplay())
    }

    @Test
    fun `已清零时 fail-fast 读取仍然抛异常`() {
        val secret = ProtectedString("carol", isProtected = true)
        secret.clear()

        val stringFailure = runCatching { secret.readString() }.exceptionOrNull()
        assertTrue("readString 必须保持 fail-fast，实际：$stringFailure", stringFailure is IllegalStateException)

        val utf8Failure = runCatching { secret.readUtf8() }.exceptionOrNull()
        assertTrue("readUtf8 必须保持 fail-fast，实际：$utf8Failure", utf8Failure is IllegalStateException)

        val charsFailure = runCatching { secret.readChars() }.exceptionOrNull()
        assertTrue("readChars 必须保持 fail-fast，实际：$charsFailure", charsFailure is IllegalStateException)
    }

    @Test
    fun `共享空实例的 clear 为 no-op 且展示面与 fail-fast 读取同为空`() {
        // P3-10：EMPTY 为全局共享单例，clear 一律 no-op（防任一调用方污染后续引用者）
        // ⇒ 它**不是**「已清零」态，展示面与 fail-fast 两条读口的结果必须一致（同为空）
        val empty = ProtectedString.EMPTY
        empty.clear()

        assertFalse("EMPTY 的 clear 必须是 no-op，不得被置为已清零态", empty.cleared)
        assertEquals("", empty.readString())
        assertEquals("", empty.readStringForDisplay())
        assertTrue(empty.readUtf8ForDisplay()!!.isEmpty())
    }
}
