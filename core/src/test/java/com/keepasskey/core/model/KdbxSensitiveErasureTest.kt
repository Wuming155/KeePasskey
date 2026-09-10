package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-06 回归：copy-on-write 场景下「锁定不等于销毁」的定点擦除语义。
 *
 * 旧树下线节点的受保护字段必须清零；但新树仍以同一对象引用共享的
 * ProtectedString / 附件**绝不能**被误擦（否则数据丢失）。
 */
class KdbxSensitiveErasureTest {

    private fun uuid(seed: Byte) = KdbxUuid(ByteArray(16) { seed })

    private fun entry(
        seed: Byte,
        password: ProtectedString,
        history: List<KdbxEntry> = emptyList()
    ): KdbxEntry = KdbxEntry(
        id = uuid(seed),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("title-$seed", isProtected = false),
            KdbxConstants.Fields.PASSWORD to password
        ),
        history = history
    )

    /** 已清零的 ProtectedString 读取会抛 IllegalStateException，此处归一为 null 便于断言 */
    private fun readOrNull(value: ProtectedString): String? =
        try {
            value.readString()
        } catch (_: IllegalStateException) {
            null
        }

    @Test
    fun `下线旧节点的自有受保护字段被擦除`() {
        val oldPassword = ProtectedString("old-secret", isProtected = true)
        val oldEntry = entry(1, oldPassword)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(oldEntry))

        // 模拟 saveEntry：同一 id 被全新节点替换
        val replacement = entry(1, ProtectedString("new-secret", isProtected = true))
        val newRoot = oldRoot.copy(entries = listOf(replacement))

        oldRoot.clearSupersededSensitiveData(newRoot)

        assertNull("下线旧节点的密码密文必须被擦除", readOrNull(oldPassword))
        assertEquals("new-secret", replacement.password!!.readString())
    }

    @Test
    fun `copy-on-write 未修改条目共享引用不得被误擦`() {
        val sharedPassword = ProtectedString("shared-secret", isProtected = true)
        val untouched = entry(2, sharedPassword)
        val oldRoot = KdbxGroup(name = "root", entries = listOf(untouched))

        // 仅新增条目 → 新树与旧树共享 untouched 的同一实例
        val newRoot = oldRoot.copy(
            entries = listOf(untouched, entry(3, ProtectedString("added", isProtected = true)))
        )

        oldRoot.clearSupersededSensitiveData(newRoot)

        assertEquals("共享条目的字段不得被误擦", "shared-secret", sharedPassword.readString())
        assertEquals("shared-secret", untouched.password!!.readString())
    }

    @Test
    fun `移动条目 copy 共享全部字段实例不得被误擦`() {
        val movedPassword = ProtectedString("moved-secret", isProtected = true)
        val original = entry(4, movedPassword)
        val source = KdbxGroup(name = "src", entries = listOf(original))

        // batchMoveEntries 语义：copy(parentGroupId = ...) 后旧/新节点共享全部字段实例
        val moved = original.copy(parentGroupId = uuid(9))
        val destination = KdbxGroup(name = "dst", entries = listOf(moved))
        val newRoot = source.copy(subgroups = listOf(destination))

        source.clearSupersededSensitiveData(newRoot)

        assertEquals("move 共享字段不得被误擦", "moved-secret", movedPassword.readString())
    }

    @Test
    fun `等值但不同实例的下线旧字段仍被擦除`() {
        val oldPassword = ProtectedString("same-secret", isProtected = true)
        val newPassword = ProtectedString("same-secret", isProtected = true)
        assertTrue("两个实例等值（HMAC 标签相同）", oldPassword == newPassword)

        val oldRoot = KdbxGroup(name = "root", entries = listOf(entry(10, oldPassword)))
        val newRoot = KdbxGroup(name = "root", entries = listOf(entry(10, newPassword)))

        oldRoot.clearSupersededSensitiveData(newRoot)

        assertNull("身份判定必须擦除被替换的旧实例，而非按内容相等跳过", readOrNull(oldPassword))
        assertEquals("same-secret", newPassword.readString())
    }

    @Test
    fun `下线 history 项被擦除而新树共享的 history 保留`() {
        val historyPassword = ProtectedString("hist-secret", isProtected = true)
        val historyEntry = entry(5, historyPassword)
        val currentEntry = entry(6, ProtectedString("cur-secret", true), history = listOf(historyEntry))
        val oldRoot = KdbxGroup(name = "root", entries = listOf(currentEntry))

        // 新树仍引用同一 historyEntry → 必须保留
        val keepRoot = oldRoot.copy(entries = listOf(currentEntry.copy(parentGroupId = uuid(9))))
        oldRoot.clearSupersededSensitiveData(keepRoot)
        assertEquals("共享 history 项不得被误擦", "hist-secret", historyPassword.readString())

        // 新树不再含该 historyEntry → 下线，必须擦除
        val dropRoot = KdbxGroup(name = "root", entries = listOf(currentEntry.copy(history = emptyList())))
        oldRoot.clearSupersededSensitiveData(dropRoot)
        assertNull("下线 history 项必须被擦除", readOrNull(historyPassword))
    }

    @Test
    fun `clearOwnSensitiveData 只清自身字段不触碰 history`() {
        val historyPassword = ProtectedString("hist-own", isProtected = true)
        val historyEntry = entry(7, historyPassword)
        val own = entry(8, ProtectedString("own-secret", true), history = listOf(historyEntry))

        own.clearOwnSensitiveData()

        assertNull(readOrNull(own.password!!))
        assertEquals("history 不在无条件擦除范围（copy 会共享）", "hist-own", historyPassword.readString())
    }

    @Test
    fun `子树下线节点整体被擦除`() {
        val childPassword = ProtectedString("child-secret", isProtected = true)
        val child = entry(11, childPassword)
        val subgroup = KdbxGroup(name = "sub", entries = listOf(child))
        val oldRoot = KdbxGroup(name = "root", subgroups = listOf(subgroup))

        val newRoot = KdbxGroup(name = "root") // 子树整体下线

        oldRoot.clearSupersededSensitiveData(newRoot)

        assertNull(readOrNull(childPassword))
    }
}
