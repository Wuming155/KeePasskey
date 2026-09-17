package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOTP 验证码缓存与批量取码通道（ISSUE-P2-90）。
 *
 * 缺陷背景：验证码重算的粒度原本是「每个带 TOTP 的条目各调一次、每次都重新
 * `findEntry` + Base32 解码 + HMAC」，而调用方（验证器页 / 详情页每秒、列表页每周期）
 * 的频率远高于验证码本身的变化频率（**一个周期内之码恒定**），构成持续的 O(T×N) 开销。
 *
 * 本用例以**实例身份**（`assertSame` / `assertNotSame`）判别「是否真的重算」——
 * 重算必然产出新的 [EntryTotpSnapshot] 实例，故无需统计内部调用次数即可判定：
 * 1. 同一周期内重复取码命中缓存（同一实例）；
 * 2. 跨周期必然重算（实例不同）——修正「缓存永不失效」的假绿；
 * 3. [VaultEntrySecretReader.invalidateTotpCache] 立即作废（改种子 / 落库后不得再读旧码）；
 * 4. 批量通道与单条通道共用同一缓存，且对无 OTP 的条目如实缺席；
 * 5. **HOTP 一律不入缓存**（其码由持久化计数器决定，缓存会交付一个已被推进掉的码）。
 *
 * 时钟由用例注入并固定在一个已算好的周期窗口内，故周期边界可被确定性地跨过，
 * 不存在「真实墙钟恰好跨周期」造成的偶发红。
 */
class TotpCodeCacheTest {

    private companion object {
        /** RFC 6238 附录 B 的测试种子（非机密常量） */
        const val SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        /**
         * 基准时刻（毫秒）：`1_700_000_000_000` ⇒ 秒 = 1_700_000_000，`% 30 = 20`，
         * 即处于周期第 20 秒，**距下一个周期边界还有 10 秒**。
         * 故 `+10_000` 必跨周期，而 `+0` / `+1_000` 同周期。
         */
        const val BASE_MILLIS = 1_700_000_000_000L

        const val PERIOD_BOUNDARY_OFFSET_MILLIS = 10_000L
    }

    private fun totpEntry(): KdbxEntry = KdbxEntry(
        fields = mapOf(KdbxConstants.Fields.OTP to ProtectedString(SECRET))
    )

    private fun hotpEntry(): KdbxEntry = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.OTP to ProtectedString("otpauth://hotp/Label?secret=$SECRET&counter=0")
        )
    )

    private fun readerFor(
        entries: List<KdbxEntry>,
        clock: () -> Long
    ): VaultEntrySecretReader {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            KdbxDatabase(
                header = KdbxHeader.createDefault(useArgon2 = false),
                rootGroup = KdbxGroup(name = "Root", entries = entries)
            )
        )
        return VaultEntrySecretReader(
            session,
            VaultEntryMapper(StringsProvider { _, _ -> "" }),
            clock
        )
    }

    @Test
    fun `同一周期内重复取码命中缓存且不重算`() = runTest {
        val entry = totpEntry()
        var clock = BASE_MILLIS
        val reader = readerFor(listOf(entry), { clock })
        val id = entry.id.toHexString()

        val first = reader.calculateEntryTotp(id)
        assertEquals(6, first?.code?.length)

        // 同周期内的第二次（模拟下一拍）必须命中缓存：同一实例即证明未重算
        assertSame(first, reader.calculateEntryTotp(id))

        // 周期内推进 1 秒仍是同周期
        clock = BASE_MILLIS + 1_000
        assertSame(first, reader.calculateEntryTotp(id))
    }

    @Test
    fun `跨周期必须重算验证码`() = runTest {
        val entry = totpEntry()
        var clock = BASE_MILLIS
        val reader = readerFor(listOf(entry), { clock })
        val id = entry.id.toHexString()

        val first = reader.calculateEntryTotp(id)

        clock = BASE_MILLIS + PERIOD_BOUNDARY_OFFSET_MILLIS
        val next = reader.calculateEntryTotp(id)

        assertNotSame("跨周期必须重算（否则会持续下发上一周期之码）", first, next)
        assertEquals(6, next?.code?.length)
        // 周期号推进一格，验证码本身亦应不同（RFC 6238 种子在相邻周期必出不同码）
        assertTrue(first?.code != next?.code)
    }

    @Test
    fun `作废缓存后立即重算`() = runTest {
        val entry = totpEntry()
        var clock = BASE_MILLIS
        val reader = readerFor(listOf(entry), { clock })
        val id = entry.id.toHexString()

        val first = reader.calculateEntryTotp(id)
        assertSame(first, reader.calculateEntryTotp(id))

        // 模拟「改种子 / 落库 / 锁库 / 同步合并」触发的作废
        reader.invalidateTotpCache()

        assertNotSame("作废后必须重算，否则会下发过期验证码", first, reader.calculateEntryTotp(id))
    }

    @Test
    fun `批量取码与单条取码共用同一缓存且无 OTP 条目缺席`() = runTest {
        val withTotp = totpEntry()
        val alsoWithTotp = totpEntry()
        val withoutOtp = KdbxEntry(fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("无 OTP")))
        var clock = BASE_MILLIS
        val reader = readerFor(listOf(withTotp, alsoWithTotp, withoutOtp), { clock })

        val firstId = withTotp.id.toHexString()
        val secondId = alsoWithTotp.id.toHexString()

        // 先经单条通道填充缓存
        val single = reader.calculateEntryTotp(firstId)

        // 批量通道：命中项复用同一快照实例；无 OTP 条目如实缺席
        val batch = reader.calculateEntryTotps(listOf(firstId, secondId, withoutOtp.id.toHexString()))
        assertEquals(setOf(firstId, secondId), batch.keys)
        assertSame(single, batch[firstId])
    }

    @Test
    fun `HOTP 一律不入缓存`() = runTest {
        val entry = hotpEntry()
        var clock = BASE_MILLIS
        val reader = readerFor(listOf(entry), { clock })
        val id = entry.id.toHexString()

        val first = reader.calculateEntryTotp(id)
        val second = reader.calculateEntryTotp(id)

        assertNotSame("HOTP 之码由持久化计数器决定，缓存会交付已被推进掉的码", first, second)
    }
}
