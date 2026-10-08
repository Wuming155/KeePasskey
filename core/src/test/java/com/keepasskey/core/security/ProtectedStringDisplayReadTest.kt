package com.keepasskey.core.security

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
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

    /**
     * `ISSUE-P2-534`（§473 复核 #3）：锁定「等值标签复核」的**前提成立性**。
     *
     * 驻留加密是 AES/CTR（无认证标签），`clear()` 又会先填零密文、后置标志；并发读取因此可能
     * 通过 `checkNotCleared` 却拿到**正在被填零**的密文 —— CTR 只会把它解成密钥流垃圾明文而不报错。
     * `ProtectedString.plainBytes()` 正是靠「解出的明文重算标签 ≠ 驻留标签」把该形态转成 fail-closed。
     * 本用例复演「密文被填零而 IV 仍在」，断言标签**必然对不上** ——
     * 否则复核就是空守卫（这正是 `ISSUE-P3-303` 那类「闸门存在 ≠ 闸门有效」的教训）。
     */
    @Test
    fun `等值标签能识别被填零的驻留密文（防静默垃圾明文）`() {
        val plain = "correct horse battery staple".toByteArray(Charsets.UTF_8)
        val sealed = InMemoryCipher.seal(plain)
        assertTrue(
            "前提：正常密封标签必须与明文重算的标签一致",
            InMemoryCipher.tagsEqual(sealed.tag, InMemoryCipher.equalityTag(plain))
        )

        sealed.data.fill(0)
        val garbage = InMemoryCipher.unseal(sealed.iv, sealed.data)
        try {
            assertFalse(
                "被填零的密文解出的结果必须与驻留标签不符（否则复核形同虚设）",
                InMemoryCipher.tagsEqual(sealed.tag, InMemoryCipher.equalityTag(garbage))
            )
        } finally {
            garbage.fill(0)
            plain.fill(0)
        }
    }

    /**
     * `ISSUE-P2-534`：条目级展示读访问器与「已清零」判据的外显行为。
     *
     * ① `displayTitle/displayUserName/displayUrl/displayNotes` 与裸 getter 语义相同（正向对照）；
     * ② 字段实例被擦后降级为空串而**不抛**（§473 崩溃点即 `getUserName`）；
     * ③ `hasClearedFields()` 供交付 / 凭据面预判中止（只读 `cleared`，不物化明文）。
     */
    @Test
    fun `KdbxEntry 展示读访问器降级且 hasClearedFields 如实上报`() {
        val title = ProtectedString("标题", isProtected = true)
        val userName = ProtectedString("alice", isProtected = true)
        val url = ProtectedString("https://example.test", isProtected = true)
        val notes = ProtectedString("备注", isProtected = true)
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to title,
                KdbxConstants.Fields.USER_NAME to userName,
                KdbxConstants.Fields.URL to url,
                KdbxConstants.Fields.NOTES to notes
            )
        )

        // 正向对照：未清零时展示读口与裸 getter 逐字一致
        assertEquals(entry.title, entry.displayTitle())
        assertEquals(entry.userName, entry.displayUserName())
        assertEquals(entry.url, entry.displayUrl())
        assertEquals(entry.notes, entry.displayNotes())
        assertFalse("未清零时不得误报", entry.hasClearedFields())

        listOf(title, userName, url, notes).forEach { it.clear() }

        assertEquals("", entry.displayTitle())
        assertEquals("", entry.displayUserName())
        assertEquals("", entry.displayUrl())
        assertEquals("", entry.displayNotes())
        assertTrue("字段已清零时必须如实上报，供交付面预判中止", entry.hasClearedFields())
        // 裸 getter 的 fail-fast 语义**逐字未放宽**（写路径的最后防线）
        val failure = runCatching { entry.userName }.exceptionOrNull()
        assertTrue("裸 getter 必须仍抛，实际：$failure", failure is IllegalStateException)
    }
}
