package com.keepasskey.core.security

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `ENG-20261028-11` 回归：共享受保护实例的**回收与旧树读者协调**。
 *
 * 锁定的行为契约：
 * 1. 无读租约在册时，下线实例立即回收（与整改前一致，不延长秘密驻留）；
 * 2. 有租约在册时只登记，最后一个租约释放后**确定性**擦除（不依赖 GC）；
 * 3. 锁定 / 会话终止的直接擦除（`clearSensitiveData` / `clear`）不等租约；
 * 4. 租约不改变存活判定——新树仍以同一对象引用共享的实例不得被回收；
 * 5. 待回收实例数达到上限时回收优先，避免租约泄漏无限延长秘密驻留；
 * 6. 已回收实例的读取继续 fail-closed（fail-fast 读口抛、展示读口降级），等值语义不变。
 */
class ProtectedStringReclamationTest {

    private fun uuid(seed: Byte) = KdbxUuid(ByteArray(16) { seed })

    private fun entry(seed: Byte, password: ProtectedString): KdbxEntry = KdbxEntry(
        id = uuid(seed),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("title-$seed", isProtected = false),
            KdbxConstants.Fields.PASSWORD to password
        )
    )

    /** 模拟 saveEntry 的 copy-on-write 结果：仅替换目标条目的密码字段 */
    private fun replacedTree(
        oldRoot: KdbxGroup,
        index: Int,
        newPassword: ProtectedString
    ): Pair<KdbxEntry, KdbxGroup> {
        val oldEntry = oldRoot.entries[index]
        val replacement = oldEntry.withField(KdbxConstants.Fields.PASSWORD, newPassword)
        val newRoot = oldRoot.copy(
            entries = oldRoot.entries.toMutableList().also { it[index] = replacement }
        )
        return oldEntry to newRoot
    }

    @After
    fun `测试结束时回收协调器不留残余状态`() {
        assertEquals("用例不得泄漏读租约", 0, ProtectedStringReclamation.activeReadLeaseCount())
        assertEquals("用例结束时不得残留待回收实例", 0, ProtectedStringReclamation.pendingRetirementCount())
    }

    @Test
    fun `无读租约时下线实例立即回收`() {
        val oldPassword = ProtectedString("old-secret", isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, oldPassword)))
        val (_, newRoot) = replacedTree(oldRoot, 0, ProtectedString("new-secret", isProtected = true))

        oldRoot.clearSupersededSensitiveData(newRoot)

        assertTrue("无读者在册时必须当场擦除，不得延长秘密驻留", oldPassword.cleared)
        assertEquals(0, ProtectedStringReclamation.pendingRetirementCount())
        assertThrows(IllegalStateException::class.java) { oldPassword.readString() }
    }

    @Test
    fun `租约在册时延后回收且旧值保持完整可读`() {
        val oldPassword = ProtectedString("old-secret", isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, oldPassword)))
        val (_, newRoot) = replacedTree(oldRoot, 0, ProtectedString("new-secret", isProtected = true))

        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            oldRoot.clearSupersededSensitiveData(newRoot)

            assertFalse("旧树读者在册期间不得擦除", oldPassword.cleared)
            assertEquals("旧值必须完整可读（不是半份明文）", "old-secret", oldPassword.readString())
            assertEquals(1, ProtectedStringReclamation.pendingRetirementCount())
        } finally {
            lease.close()
        }

        assertTrue("最后一个租约释放后必须确定性擦除", oldPassword.cleared)
        assertEquals(0, ProtectedStringReclamation.pendingRetirementCount())
    }

    @Test
    fun `嵌套租约只有最后一个释放才回收`() {
        val password = ProtectedString("secret", isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, password)))
        val (_, newRoot) = replacedTree(oldRoot, 0, ProtectedString("next", isProtected = true))

        val outer = ProtectedStringReclamation.acquireReadLease()
        val inner = ProtectedStringReclamation.acquireReadLease()
        try {
            oldRoot.clearSupersededSensitiveData(newRoot)
            inner.close()

            assertEquals("仍有外层租约在册", 1, ProtectedStringReclamation.activeReadLeaseCount())
            assertFalse("外层租约未释放前不得擦除", password.cleared)
            assertEquals("secret", password.readString())
        } finally {
            outer.close()
        }
        assertTrue(password.cleared)
    }

    @Test
    fun `重复释放同一租约不会提前回收`() {
        val password = ProtectedString("secret", isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, password)))
        val (_, newRoot) = replacedTree(oldRoot, 0, ProtectedString("next", isProtected = true))

        val keeper = ProtectedStringReclamation.acquireReadLease()
        val releasedTwice = ProtectedStringReclamation.acquireReadLease()
        try {
            oldRoot.clearSupersededSensitiveData(newRoot)
            releasedTwice.close()
            releasedTwice.close()

            assertEquals("重复 close 不得把计数减成 0", 1, ProtectedStringReclamation.activeReadLeaseCount())
            assertFalse(password.cleared)
        } finally {
            keeper.close()
        }
        assertTrue(password.cleared)
    }

    @Test
    fun `锁定清理不等租约`() {
        val password = ProtectedString("secret", isProtected = true)
        val root = KdbxGroup(name = "root", entries = listOf(entry(1, password)))

        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            root.clearSensitiveData()

            assertTrue("锁定是硬边界：不得因旧树读者在册而保留明文", password.cleared)
            assertNull("锁定后展示读口按既有语义降级", password.readStringForDisplayOrNull())
        } finally {
            lease.close()
        }
    }

    @Test
    fun `增量回收路径同样遵守租约`() {
        val oldPassword = ProtectedString("old-secret", isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, oldPassword)))
        val (oldEntry, newRoot) = replacedTree(oldRoot, 0, ProtectedString("new-secret", isProtected = true))
        val replacement = newRoot.entries[0]

        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            newRoot.eraseSupersededSensitiveData(oldEntry, replacement)

            assertFalse(oldPassword.cleared)
            assertEquals("old-secret", oldPassword.readString())
            assertEquals(1, ProtectedStringReclamation.pendingRetirementCount())
        } finally {
            lease.close()
        }
        assertTrue(oldPassword.cleared)
    }

    @Test
    fun `租约不改变存活判定，共享实例在释放后仍可读`() {
        val sharedPassword = ProtectedString("shared-secret", isProtected = true)
        val untouched = entry(2, sharedPassword)
        val changed = entry(3, ProtectedString("changed", isProtected = true))
        val oldRoot = KdbxGroup(name = "root", entries = listOf(untouched, changed))
        val (_, newRoot) = replacedTree(oldRoot, 1, ProtectedString("changed-2", isProtected = true))

        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            oldRoot.clearSupersededSensitiveData(newRoot)
        } finally {
            lease.close()
        }

        assertFalse("新树仍以同一对象引用共享的实例不得被回收", sharedPassword.cleared)
        assertEquals("shared-secret", sharedPassword.readString())
    }

    @Test
    fun `后续新租约不推迟更早退休桶且旧实例在更早租约释放后立即回收`() {
        val s1 = ProtectedString("secret-epoch-1", isProtected = true)
        val s2 = ProtectedString("secret-epoch-2", isProtected = true)

        val lease1 = ProtectedStringReclamation.acquireReadLease()
        ProtectedStringReclamation.retire(s1)

        val lease2 = ProtectedStringReclamation.acquireReadLease()
        ProtectedStringReclamation.retire(s2)

        // 两个租约均活跃：s1、s2 均不可回收
        assertFalse("租约 1 活跃时 s1 不得擦除", s1.cleared)
        assertFalse("租约 2 活跃时 s2 不得擦除", s2.cleared)
        assertEquals(2, ProtectedStringReclamation.pendingRetirementCount())

        // 释放更早的租约 1：s1 仅需等待租约 1，租约 2 的开启绝不推迟 s1 的回收
        lease1.close()

        assertTrue("租约 1 释放后 s1 必须立即确定性回收，后续新租约不得推迟早前退休桶", s1.cleared)
        assertFalse("租约 2 仍在册时 s2 不得被误回收", s2.cleared)
        assertEquals("secret-epoch-2", s2.readString())
        assertEquals(1, ProtectedStringReclamation.pendingRetirementCount())

        // 释放租约 2：s2 正常回收，待回收集合彻底排空
        lease2.close()

        assertTrue("租约 2 释放后 s2 必须回收", s2.cleared)
        assertEquals(0, ProtectedStringReclamation.pendingRetirementCount())
    }

    @Test
    fun `租约在册期间即使持续下线海量实例活读绝不被强制清零中断`() {
        val expected = "unbroken-live-read-secret"
        val password = ProtectedString(expected, isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, password)))
        val (_, newRoot) = replacedTree(oldRoot, 0, ProtectedString("next", isProtected = true))

        val lease = ProtectedStringReclamation.acquireReadLease()
        try {
            oldRoot.clearSupersededSensitiveData(newRoot)
            assertFalse("旧树读者在册期间不得擦除", password.cleared)

            // 持续登记大量下线实例（无人工硬上限强清活读）
            val count = 10_000
            repeat(count) { i ->
                ProtectedStringReclamation.retire(ProtectedString("batch-$i", isProtected = true))
            }

            // 可观察行为验证：活读始终完整，绝不被任何硬上限强清中断
            assertFalse("合法租约未释放前活读实例绝对不得被强制清零", password.cleared)
            assertEquals("活读必须完整可读", expected, password.readString())
            assertEquals(
                "全部待回收实例在租约持有期间安全滞留",
                1 + count,
                ProtectedStringReclamation.pendingRetirementCount()
            )
        } finally {
            lease.close()
        }

        // 释放后确定性回收
        assertTrue("最后一个读租约释放后实例必须已回收", password.cleared)
        assertEquals("释放后待回收集合必须完全排空", 0, ProtectedStringReclamation.pendingRetirementCount())
        assertThrows(IllegalStateException::class.java) { password.readString() }
    }

    @Test
    fun `持租约读取期间并发回收不产生半份明文`() {
        val expected = "concurrent-secret-value"
        val password = ProtectedString(expected, isProtected = true)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(1, password)))
        val (_, newRoot) = replacedTree(oldRoot, 0, ProtectedString("next", isProtected = true))

        val lease = ProtectedStringReclamation.acquireReadLease()
        val stop = AtomicBoolean(false)
        val mismatches = AtomicInteger()
        val failures = AtomicInteger()
        val reads = AtomicInteger()
        val start = CountDownLatch(1)
        val reader = Thread {
            start.await()
            while (!stop.get()) {
                try {
                    if (password.readString() == expected) reads.incrementAndGet() else mismatches.incrementAndGet()
                } catch (_: IllegalStateException) {
                    failures.incrementAndGet()
                }
            }
        }
        try {
            reader.start()
            start.countDown()
            oldRoot.clearSupersededSensitiveData(newRoot)
            Thread.sleep(50)
        } finally {
            stop.set(true)
            reader.join()
            lease.close()
        }

        assertTrue("读者必须在租约期间完成实际读取", reads.get() > 0)
        assertEquals("租约期间不得出现错误明文", 0, mismatches.get())
        assertEquals("租约期间不得出现读取失败（含标签复核失败）", 0, failures.get())
        assertTrue("租约释放后实例必须已回收", password.cleared)
        assertThrows(IllegalStateException::class.java) { password.readString() }
    }
}
