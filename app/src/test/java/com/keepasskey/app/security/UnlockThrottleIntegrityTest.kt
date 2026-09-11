package com.keepasskey.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 解锁节流记录完整性（ISSUE-P3-54）单元测试。
 *
 * 覆盖：MAC 载荷编码的字段隔离 + 完整性校验的「通过 / 篡改被拒 / MAC 缺失被拒」语义。
 * 生产用 AndroidKeyStore HMAC 实现无法在 JVM 直测，此处以固定密钥的等价假实现验证
 * [UnlockThrottleIntegrity] 契约与 [UnlockThrottleMacPayload] 编码。
 */
class UnlockThrottleIntegrityTest {

    /** 固定密钥的等价 HMAC 实现（JVM 可测，语义与 AndroidKeyStore 实现一致） */
    private class FakeIntegrity : UnlockThrottleIntegrity {
        private val key = SecretKeySpec("test-throttle-integrity-key".toByteArray(), "HmacSHA256")

        override fun mac(databaseId: String, record: UnlockThrottleRecord): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(key) }
                .doFinal(UnlockThrottleMacPayload.encode(databaseId, record))

        override fun verify(databaseId: String, record: UnlockThrottleRecord, mac: ByteArray?): Boolean {
            if (mac == null) return false
            return MessageDigest.isEqual(mac(databaseId, record), mac)
        }
    }

    @Test
    fun `MAC 载荷按字段隔离编码`() {
        val base = UnlockThrottleMacPayload.encode("db", UnlockThrottleRecord(1, 100L))
        assertFalse(base.contentEquals(UnlockThrottleMacPayload.encode("db", UnlockThrottleRecord(2, 100L))))
        assertFalse(base.contentEquals(UnlockThrottleMacPayload.encode("db", UnlockThrottleRecord(1, 200L))))
        assertFalse(base.contentEquals(UnlockThrottleMacPayload.encode("db2", UnlockThrottleRecord(1, 100L))))
    }

    @Test
    fun `MAC 可校验原始记录并拒绝被篡改记录`() {
        val integrity = FakeIntegrity()
        val record = UnlockThrottleRecord(failureCount = 3, lockoutUntilEpochMs = 1_700_000_000_000L)
        val mac = integrity.mac("db", record)

        assertTrue(integrity.verify("db", record, mac))
        // 篡改计数 / 锁定截止 / databaseId 均必须被拒
        assertFalse(integrity.verify("db", record.copy(failureCount = 0), mac))
        assertFalse(integrity.verify("db", record.copy(lockoutUntilEpochMs = 0L), mac))
        assertFalse(integrity.verify("other-db", record, mac))
    }

    @Test
    fun `MAC 缺失时校验被拒`() {
        assertFalse(FakeIntegrity().verify("db", UnlockThrottleRecord(1, 1L), null))
    }

    @Test
    fun `完整性标记不参与载荷编码`() {
        val record = UnlockThrottleRecord(failureCount = 1, lockoutUntilEpochMs = 1L)
        assertArrayEquals(
            UnlockThrottleMacPayload.encode("db", record),
            UnlockThrottleMacPayload.encode("db", record.copy(integrityIntact = false))
        )
    }
}
