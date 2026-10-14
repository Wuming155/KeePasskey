package com.keepasskey.app.security

import com.keepasskey.app.data.logger.DebugLogBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ISSUE-P1-592`：独立加密恢复副本存储的落盘 / 发现 / 完整性 / 清理单测。
 * 只验证存储层契约（载荷原样回读、索引提交、完整性校验、换密清理），不涉解密语义。
 */
class VaultRecoveryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(dir: File = tempFolder.root) =
        VaultRecoveryStore(null, DebugLogBuffer()).apply { baseDirOverride = dir }

    @Test
    fun `写入后可发现且载荷原样读回`() = runBlocking {
        val store = store()
        val payload = ByteArray(96) { (it * 7).toByte() }

        assertTrue(store.write(VAULT_ID, VAULT_NAME, VAULT_ID, payload))

        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals(VAULT_NAME, entries[0].vaultName)
        assertEquals(VAULT_ID, entries[0].pathIdentifier)
        assertEquals(payload.size.toLong(), entries[0].payloadLength)

        val read = store.readPayload(entries[0])
        assertNotNull(read)
        assertArrayEquals(payload, read)
        read!!.fill(0)
    }

    @Test
    fun `写入新代次后只保留一条索引与最新载荷`() = runBlocking {
        val store = store()
        val first = ByteArray(32) { 1 }
        val second = ByteArray(48) { 2 }

        assertTrue(store.write(VAULT_ID, VAULT_NAME, VAULT_ID, first))
        assertTrue(store.write(VAULT_ID, VAULT_NAME, VAULT_ID, second))

        val entries = store.list()
        assertEquals("同一库恒只有一条待处置索引", 1, entries.size)
        val payloads = tempFolder.root.listFiles { f -> f.name.endsWith(".kre") }
        assertEquals("更早代次载荷已被清理", 1, payloads?.size)

        val read = store.readPayload(entries[0])
        assertArrayEquals(second, read)
        read!!.fill(0)
    }

    @Test
    fun `载荷被篡改时完整性校验失败且不自动采纳`() = runBlocking {
        val store = store()
        assertTrue(store.write(VAULT_ID, VAULT_NAME, VAULT_ID, ByteArray(64) { 3 }))
        val entry = store.list().single()

        // 篡改载荷字节（内容改变 ⇒ SHA-256 不符）
        val payloadFile = tempFolder.root.listFiles { f -> f.name.endsWith(".kre") }!!.single()
        payloadFile.writeBytes(ByteArray(64) { 9 })

        assertNull("损坏副本不得被采纳", store.readPayload(entry))
    }

    @Test
    fun `非敏感索引不含载荷明文`() = runBlocking {
        val store = store()
        val marker = "SUPERSECRET-PAYLOAD-MARKER"
        val payload = marker.toByteArray()

        assertTrue(store.write(VAULT_ID, "库名", VAULT_ID, payload))

        val meta = tempFolder.root.listFiles { f -> f.name.endsWith(".krem") }!!.single()
        assertFalse("索引不得携带载荷明文", meta.readText(Charsets.UTF_8).contains(marker))
    }

    @Test
    fun `根目录不可写时写入返回 false`() = runBlocking {
        val blocked = File(tempFolder.root, "blocker").apply { writeText("x") }
        val store = store(File(blocked, "unreachable"))

        assertFalse(store.write(VAULT_ID, VAULT_NAME, VAULT_ID, ByteArray(16)))
    }

    @Test
    fun `按库身份删除后不再被发现且幂等`() = runBlocking {
        val store = store()
        assertTrue(store.write(VAULT_ID, VAULT_NAME, VAULT_ID, ByteArray(24)))
        assertTrue(store.hasAny())

        store.deleteByVaultId(VAULT_ID)
        store.deleteByVaultId(VAULT_ID)

        assertFalse(store.hasAny())
        assertEquals(0, tempFolder.root.listFiles { f -> f.name.endsWith(".kre") }?.size)
    }

    private companion object {
        const val VAULT_ID = "content://provider/vault.kdbx"
        const val VAULT_NAME = "我的密码库"
    }
}
