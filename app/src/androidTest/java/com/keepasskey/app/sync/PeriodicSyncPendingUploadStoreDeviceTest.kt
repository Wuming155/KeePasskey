package com.keepasskey.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.sync.engine.SyncCache
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android 私有存储上验证锁态待传 KDBX 密文队列的原子落盘与重建读取。 */
@RunWith(AndroidJUnit4::class)
class PeriodicSyncPendingUploadStoreDeviceTest {

    @Test
    fun Android私有文件系统保存快照并在新实例中恢复() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vaultIdentity = File(context.filesDir, "periodic-queue-synthetic-vault.kdbx").absolutePath
        val queueDir = File(context.filesDir, PeriodicSyncPendingUploadStore.DIRECTORY_NAME)
        val store = PeriodicSyncPendingUploadStore(queueDir)
        val payload = "synthetic encrypted KDBX queue bytes".toByteArray()

        try {
            val staged = assertNotNullRecord(
                store.stage(
                    vaultIdentity = vaultIdentity,
                    targetIdentity = TARGET_IDENTITY,
                    remotePath = "/synthetic-vault.kdbx",
                    expectedEtag = "etag-base",
                    rollbackVaultScope = ROLLBACK_SCOPE,
                    input = ByteArrayInputStream(payload)
                )
            )
            val recovered = assertNotNullRecord(PeriodicSyncPendingUploadStore(queueDir).read(vaultIdentity))

            assertEquals(staged.contentDigest, recovered.contentDigest)
            assertEquals(SyncCache.sha256Hex(payload), recovered.contentDigest)
            assertArrayEquals(payload, PeriodicSyncPendingUploadStore(queueDir).readPayload(vaultIdentity, recovered))
        } finally {
            assertTrue("设备端测试必须清理本轮密文快照", store.clear(vaultIdentity))
        }
    }

    private fun assertNotNullRecord(record: PendingEncryptedUpload?): PendingEncryptedUpload =
        requireNotNull(record) { "Android 私有文件系统中的密文队列记录缺失" }

    private companion object {
        const val TARGET_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ROLLBACK_SCOPE = "00112233445566778899AABBCCDDEEFF"
    }
}
