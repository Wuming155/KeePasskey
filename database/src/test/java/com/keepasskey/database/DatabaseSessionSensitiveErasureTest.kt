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
 * ISSUE-P2-06：DatabaseSession 写路径 copy-on-write 的敏感数据擦除回归。
 *
 * 说明：真正的「替换前调用」接线位于 DatabaseSession（由 A 号代理负责），
 * 本测试锁定 [com.keepasskey.core.model.KdbxGroup.clearSupersededSensitiveData] 的擦除语义，
 * 以及「进程级密钥不可按锁定边界轮换」的边界证据。
 */
class DatabaseSessionSensitiveErasureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun uuid(seed: Byte) = KdbxUuid(ByteArray(16) { seed })

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
            file = File(tempFolder.root, "vault_${System.nanoTime()}.kdbx"),
            name = "Vault",
            passwordChars = "SessionErasure#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue(result.isSuccess)
        return session
    }

    @Test
    fun `lock 只擦除会话树_树外持有的实例仍需进程级密钥`() = runBlocking {
        val session = openedSession()
        // 模拟 SyncCoordinator 同步快照 / 生物快速解锁缓存等“不由会话树独占”的实例
        val externallyHeld = ProtectedString("outside-session-secret", isProtected = true)

        session.lock()

        assertEquals(
            "锁定只擦除会话树；进程级密钥必须继续服务树外实例，故按锁定边界轮换会破坏存活数据",
            "outside-session-secret",
            externallyHeld.readString()
        )
        externallyHeld.clear()
    }

    @Test
    fun `lock 擦除当前会话树内的受保护字段`() = runBlocking {
        val session = openedSession()
        val db = session.databaseFlow.value!!
        val inTreePassword = ProtectedString("in-tree-secret", isProtected = true)
        val entry = KdbxEntry(
            id = uuid(3),
            fields = mapOf(KdbxConstants.Fields.PASSWORD to inTreePassword)
        )
        session.setDatabaseForTesting(db.copy(rootGroup = db.rootGroup.copy(entries = listOf(entry))))

        session.lock()

        assertNull("锁定必须擦除会话树内受保护字段", readOrNull(inTreePassword))
    }

    @Test
    fun `copy-on-write 下线节点定点擦除且共享条目不受影响`() = runBlocking {
        val session = openedSession()

        val oldPassword = ProtectedString("old-pass", isProtected = true)
        val sharedPassword = ProtectedString("shared-pass", isProtected = true)
        val oldEntry = KdbxEntry(
            id = uuid(1),
            fields = mapOf(KdbxConstants.Fields.PASSWORD to oldPassword)
        )
        val sharedEntry = KdbxEntry(
            id = uuid(2),
            fields = mapOf(KdbxConstants.Fields.PASSWORD to sharedPassword)
        )
        val oldRoot = session.databaseFlow.value!!.rootGroup
            .copy(entries = listOf(oldEntry, sharedEntry))

        // saveEntry 语义：同 id 新节点替换旧节点；sharedEntry 保持同一实例引用
        val replacement = oldEntry.withField(
            KdbxConstants.Fields.PASSWORD,
            ProtectedString("new-pass", isProtected = true)
        )
        val newRoot = oldRoot.copy(entries = listOf(replacement, sharedEntry))

        oldRoot.clearSupersededSensitiveData(newRoot)

        assertNull("下线旧节点密文必须被擦除", readOrNull(oldPassword))
        assertEquals("共享条目不得被误擦", "shared-pass", sharedPassword.readString())
        assertEquals("new-pass", replacement.password!!.readString())
        session.close()
    }

    /**
     * 回归锁（集成阶段实测缺陷）：删除路径不得做身份擦除。
     *
     * 真实调用序列（RecycleBinCoordinator 软删）：先 deleteEntry(id)，随后用与旧条目
     * **共享同一 ProtectedString 实例**的 moved 副本（仅改 parentGroupId）重新 saveEntry。
     * 若删除路径按「新树未包含 = 已下线」擦除，moved 尚未入树，其共享字段会被误清，
     * 条目成空壳且后续 save() 序列化抛 IllegalStateException（保存整体失败、回收站元数据
     * 无法落盘——app 层 RealVaultRepositoryTest 的真实回归即由此产生）。
     */
    @Test
    fun `删除后再以共享字段副本重新插入不得被误擦且可落盘重开`() = runBlocking {
        val file = File(tempFolder.root, "erase_reinsert.kdbx")
        val session = DatabaseSession()
        assertTrue(
            session.create(
                file = file,
                name = "Vault",
                passwordChars = "EraseReinsert#2026".toCharArray(),
                useArgon2 = false
            ).isSuccess
        )

        val binId = uuid(9)
        session.saveGroup(KdbxGroup(id = binId, parentGroupId = session.databaseFlow.value!!.rootGroup.id, name = "回收站", iconId = 43))
        session.updateDatabaseMeta { it.copy(recycleBinUuid = binId, recycleBinEnabled = true) }

        val entryId = uuid(4)
        val secret = ProtectedString("soft-delete-secret", isProtected = true)
        session.saveEntry(
            KdbxEntry(
                id = entryId,
                parentGroupId = session.databaseFlow.value!!.rootGroup.id,
                fields = mapOf(KdbxConstants.Fields.PASSWORD to secret)
            )
        )

        // 软删：moved 与旧条目共享同一 ProtectedString 实例（copy 语义）
        val moved = session.databaseFlow.value!!.rootGroup.allEntries().first { it.id == entryId }
            .copy(parentGroupId = binId)
        session.deleteEntry(entryId)
        assertEquals("删除不得擦除即将复用的共享字段", "soft-delete-secret", secret.readString())
        session.saveEntry(moved)

        val inTree = session.databaseFlow.value!!.rootGroup.allEntries().first { it.id == entryId }
        assertEquals(binId, inTree.parentGroupId)
        assertEquals("重新插入后条目密文必须仍可读", "soft-delete-secret", inTree.password!!.readString())

        // 必须能真实落盘（序列化不会因清零抛异常）
        assertTrue("删除后再插入必须可正常保存", session.save().isSuccess)
        session.close()

        val reopened = DatabaseSession()
        assertTrue(reopened.open(file, "EraseReinsert#2026".toCharArray()).isSuccess)
        val reloaded = reopened.databaseFlow.value!!
        assertEquals("重开后回收站元数据必须保留", binId, reloaded.recycleBinUuid)
        assertEquals(
            "重开后条目密文必须无损",
            "soft-delete-secret",
            reloaded.rootGroup.allEntries().first { it.id == entryId }.password!!.readString()
        )
        reopened.close()
    }
}
