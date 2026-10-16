package com.keepasskey.app.sync

import com.keepasskey.sync.engine.SyncCache
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeriodicSyncPendingUploadStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `密文快照及提交元数据在新实例中可恢复并验证`() {
        val vaultIdentity = File(tempFolder.root, "vault.kdbx").absolutePath
        val payload = "synthetic encrypted KDBX bytes".toByteArray()
        val directory = File(tempFolder.root, "pending")
        val first = PeriodicSyncPendingUploadStore(directory)
        val staged = requireNotNull(
            first.stage(
                vaultIdentity = vaultIdentity,
                targetIdentity = TARGET_IDENTITY,
                remotePath = "/vault.kdbx",
                expectedEtag = "etag-a",
                rollbackVaultScope = ROLLBACK_SCOPE,
                input = ByteArrayInputStream(payload)
            )
        )

        val afterRestart = PeriodicSyncPendingUploadStore(directory)
        val recovered = requireNotNull(afterRestart.read(vaultIdentity))
        assertEquals(staged, recovered)
        assertEquals(SyncCache.sha256Hex(payload), recovered.contentDigest)
        assertArrayEquals(payload, afterRestart.readPayload(vaultIdentity, recovered))
        assertNull("另一个库身份不得访问该队列", afterRestart.read("different-vault"))
    }

    @Test
    fun `损坏的载荷不能通过摘要核验并可安全清理`() {
        val vaultIdentity = "vault-id"
        val directory = File(tempFolder.root, "pending")
        val store = PeriodicSyncPendingUploadStore(directory)
        val record = requireNotNull(
            store.stage(
                vaultIdentity = vaultIdentity,
                targetIdentity = TARGET_IDENTITY,
                remotePath = "/vault.kdbx",
                expectedEtag = "etag-a",
                rollbackVaultScope = ROLLBACK_SCOPE,
                input = ByteArrayInputStream("encrypted snapshot".toByteArray())
            )
        )

        File(directory, record.payloadFileName).writeBytes("tampered".toByteArray())

        assertNull("摘要不匹配时不得上传", store.readPayload(vaultIdentity, record))
        assertTrue(store.clear(vaultIdentity))
        assertNull(store.read(vaultIdentity))
        assertFalse(File(directory, record.payloadFileName).exists())
    }

    private companion object {
        const val TARGET_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ROLLBACK_SCOPE = "00112233445566778899AABBCCDDEEFF"
    }
}
