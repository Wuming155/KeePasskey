package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ISSUE-P3-156 回归：会话写路径改用**增量定点擦除**后的擦除语义。
 *
 * 与 `DatabaseSessionSensitiveErasureTest`（ISSUE-P2-06，通用实现）互补：本文件锁定
 * `saveEntry` / `saveGroup` / `batchMoveEntries` 三条增量路径「不削弱擦除、不误擦存活实例」，
 * 并以一条 `updateDatabaseMeta` 用例锁定通用实现仍按原语义工作。
 */
class DatabaseSessionIncrementalErasureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun uuid(seed: Byte) = KdbxUuid(ByteArray(16) { seed })

    private fun entry(idSeed: Byte, parentGroupId: KdbxUuid?, secret: ProtectedString) = KdbxEntry(
        id = uuid(idSeed),
        parentGroupId = parentGroupId,
        fields = mapOf(KdbxConstants.Fields.PASSWORD to secret)
    )

    /** 已清零的 ProtectedString 读取会抛 IllegalStateException，此处归一为 null 便于断言 */
    private fun readOrNull(value: ProtectedString): String? =
        try {
            value.readString()
        } catch (_: IllegalStateException) {
            null
        }

    private suspend fun openedSession(): DatabaseSession {
        val session = DatabaseSession()
        val result = session.create(
            file = File(tempFolder.root, "incremental_${System.nanoTime()}.kdbx"),
            name = "Vault",
            passwordChars = "IncrementalErasure#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue(result.isSuccess)
        return session
    }

    private fun DatabaseSession.root(): KdbxGroup = databaseFlow.value!!.rootGroup

    private fun DatabaseSession.findEntry(id: KdbxUuid): KdbxEntry? = root().findEntry(id)

    @Test
    fun `saveEntry 替换既有条目时被替换旧节点的密文被清零且同组其他条目不受影响`() = runBlocking {
        val session = openedSession()
        val rootId = session.root().id
        val replacedSecret = ProtectedString("replaced-secret", isProtected = true)
        val siblingSecret = ProtectedString("sibling-secret", isProtected = true)
        session.saveEntry(entry(1, rootId, replacedSecret))
        session.saveEntry(entry(2, rootId, siblingSecret))

        // 同 id 全新实例替换（不共享承载容器、无历史快照）
        val freshSecret = ProtectedString("fresh-secret", isProtected = true)
        session.saveEntry(entry(1, rootId, freshSecret))

        assertNull("被替换旧节点的密文必须清零", readOrNull(replacedSecret))
        assertEquals("上线条目密文可读", "fresh-secret", freshSecret.readString())
        assertEquals("未命中条目的密文不得受影响", "sibling-secret", siblingSecret.readString())
        session.close()
    }

    @Test
    fun `克隆体按引用共享的字段在源条目被替换后仍可读`() = runBlocking {
        val session = openedSession()
        val rootId = session.root().id
        val sharedSecret = ProtectedString("clone-shared", isProtected = true)
        val source = entry(1, rootId, sharedSecret)
        session.saveEntry(source)
        // EntryDuplicateCoordinator.toFreshClone 语义：copy 换 id，字段按引用共享
        session.saveEntry(source.copy(id = uuid(2)))

        // 源条目被全新实例替换：旧密文仅剩克隆体一个持有者
        session.saveEntry(entry(1, rootId, ProtectedString("fresh", isProtected = true)))

        assertEquals("克隆体仍持有 ⇒ 全局存活判定必须保留", "clone-shared", sharedSecret.readString())
        assertEquals("clone-shared", session.findEntry(uuid(2))!!.password!!.readString())
        session.close()
    }

    @Test
    fun `批量移动条目不得误擦 moved 副本共享的字段`() = runBlocking {
        val session = openedSession()
        val rootId = session.root().id
        val subId = uuid(5)
        session.saveGroup(KdbxGroup(id = subId, parentGroupId = rootId, name = "sub"))
        val secret = ProtectedString("move-secret", isProtected = true)
        session.saveEntry(entry(1, rootId, secret))

        session.batchMoveEntries(setOf(uuid(1)), subId)

        val moved = session.findEntry(uuid(1))!!
        assertEquals("条目必须落在目标分组", subId, moved.parentGroupId)
        assertEquals("moved 副本共享的实例仍可读", "move-secret", secret.readString())
        assertEquals("move-secret", moved.password!!.readString())
        session.close()
    }

    @Test
    fun `分组重命名不得误擦既有子项密文`() = runBlocking {
        val session = openedSession()
        val rootId = session.root().id
        val groupId = uuid(5)
        val childSecret = ProtectedString("child-secret", isProtected = true)
        session.saveGroup(
            KdbxGroup(
                id = groupId,
                parentGroupId = rootId,
                name = "sub",
                entries = listOf(entry(1, groupId, childSecret))
            )
        )

        // VaultGroupCoordinator 语义：基于既有分组 copy 仅改名称（子项按引用共享）
        val existing = session.root().findGroup(groupId)!!
        session.saveGroup(existing.copy(name = "renamed"))

        assertEquals("renamed", session.root().findGroup(groupId)!!.name)
        assertEquals("既有子项与其密文必须原样存活", "child-secret", childSecret.readString())
        assertEquals("child-secret", session.findEntry(uuid(1))!!.password!!.readString())
        session.close()
    }

    @Test
    fun `updateDatabaseMeta 的任意整库变换仍按通用实现擦除下线实例`() = runBlocking {
        val session = openedSession()
        val rootId = session.root().id
        val secret = ProtectedString("meta-dropped", isProtected = true)
        session.saveEntry(entry(1, rootId, secret))

        // 整树丢弃：无法定位替换位置 ⇒ 走通用实现（整棵新树身份集合）
        session.updateDatabaseMeta { db -> db.copy(rootGroup = db.rootGroup.copy(entries = emptyList())) }

        assertNull("通用实现仍须清零下线实例", readOrNull(secret))
        session.close()
    }
}