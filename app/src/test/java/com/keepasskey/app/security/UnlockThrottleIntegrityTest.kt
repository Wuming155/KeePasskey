package com.keepasskey.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁节流记录完整性（ISSUE-P3-54 / ISSUE-P2-45）单元测试。
 *
 * 覆盖：MAC 载荷编码的字段隔离、完整性校验的「通过 / 篡改被拒 / MAC 缺失被拒」语义，
 * 以及 ISSUE-P2-45 的**存在性标记别名**推导（定长、稳定、随库区分、且与 MAC 密钥别名不同域）。
 * 生产用 AndroidKeyStore 实现无法在 JVM 直测，此处以固定密钥的等价假实现
 * [FakeUnlockThrottleIntegrity] 验证 [UnlockThrottleIntegrity] 契约。
 */
class UnlockThrottleIntegrityTest {

    @Test
    fun `MAC 载荷按字段隔离编码`() {
        val base = UnlockThrottleMacPayload.encode("db", UnlockThrottleRecord(1, 100L))
        assertFalse(base.contentEquals(UnlockThrottleMacPayload.encode("db", UnlockThrottleRecord(2, 100L))))
        assertFalse(base.contentEquals(UnlockThrottleMacPayload.encode("db", UnlockThrottleRecord(1, 200L))))
        assertFalse(base.contentEquals(UnlockThrottleMacPayload.encode("db2", UnlockThrottleRecord(1, 100L))))
    }

    @Test
    fun `MAC 可校验原始记录并拒绝被篡改记录`() {
        val integrity = FakeUnlockThrottleIntegrity()
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
        assertFalse(FakeUnlockThrottleIntegrity().verify("db", UnlockThrottleRecord(1, 1L), null))
    }

    @Test
    fun `完整性标记不参与载荷编码`() {
        val record = UnlockThrottleRecord(failureCount = 1, lockoutUntilEpochMs = 1L)
        assertArrayEquals(
            UnlockThrottleMacPayload.encode("db", record),
            UnlockThrottleMacPayload.encode("db", record.copy(integrityIntact = false))
        )
    }

    // ── ISSUE-P2-45：存在性标记别名推导 ────────────────────────────────

    @Test
    fun `存在性标记别名对同一库稳定且对不同库互不相同`() {
        val a1 = UnlockThrottleExistenceMarkerAlias.of("db_personal")
        val a2 = UnlockThrottleExistenceMarkerAlias.of("db_personal")
        val b = UnlockThrottleExistenceMarkerAlias.of("db_work")

        assertEquals("同一 databaseId 必须恒定映射到同一别名", a1, a2)
        assertNotEquals("不同库不得共用标记条目", a1, b)
    }

    @Test
    fun `存在性标记别名定长且字符集安全`() {
        // databaseId 形态不受控（UUID / 路径 / URI / 超长串），别名必须压成定长且仅含 [0-9a-f]
        val aliases = listOf(
            "",
            "db",
            "/storage/emulated/0/Documents/passwords.kdbx",
            "content://com.example.provider/vault/1",
            "库".repeat(500)
        ).map { UnlockThrottleExistenceMarkerAlias.of(it) }

        aliases.forEach { alias ->
            assertTrue("别名必须以专用前缀开头：$alias", alias.startsWith("com.keepasskey.unlock_throttle_seen_"))
            assertTrue("别名后缀必须为定长十六进制：$alias", Regex("^[0-9a-f]+$").matches(alias.removePrefix("com.keepasskey.unlock_throttle_seen_")))
        }
        assertEquals("别名长度必须与 databaseId 形态无关", 1, aliases.map { it.length }.distinct().size)
    }

    @Test
    fun `存在性标记别名不与MAC密钥别名同域`() {
        // 老版本安装只有 MAC 密钥条目、没有任何标记条目；两者若同域会把升级用户误判为
        // 「记录被删除」并触发一次无谓的有界锁定（ISSUE-P2-45 的升级兼容前提）。
        assertFalse(
            UnlockThrottleExistenceMarkerAlias.of("db_personal")
                .startsWith("com.keepasskey.unlock_throttle_integrity")
        )
    }
}
