package com.keepasskey.database.file

import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

/**
 * ISSUE-P3-27 子项 1：解析资源上限的取值关系不变量与边界回归。
 *
 * 背景（条目正文前提经 2026-09-10 复核后修正）：条目原文称「InnerHeader 单字段上限 256 MiB
 * 高于整包 128 MiB」，实测单字段上限为 64 MiB（本就 ≤ 整包上限，前提失准）；真正的不自洽是
 * `InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES` 被硬编码为 256 MiB——而二进制池位于受 128 MiB
 * 整包上限约束的解压载荷**之内**（池字节是整包字节的子集），故该上限永不生效，是一条死守卫。
 *
 * 整改口径：整包上限 [KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES] 为唯一真源，内层两级上限由它
 * 派生（`InnerHeader` 伴生对象），并在其初始化期以 `require` 断言不变量。本测试锁死该取值关系：
 * - 若把池上限改回独立字面量 256 MiB，「内层上限链」用例立即转红；
 * - 若把单字段上限与整包上限脱钩，「唯一真源」用例立即转红。
 */
class KdbxInnerResourceLimitsInvariantTest {

    @Test
    fun `整包上限为唯一真源且内层两级上限由其派生`() {
        val payload = KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES

        // 安全关键常量取值锚定：调大削弱解压炸弹防护、调小误拒含大附件的合法库，
        // 任何改动都必须是有意识的（同时会强制复核本测试与两处 KDoc 的取值论证）
        assertEquals("整包上限真源取值应为 128 MiB", 128L, payload / MIB)
        assertEquals(
            "单字段上限必须等于整包上限的一半（派生值，不得独立取字面量）",
            payload / 2,
            InnerHeader.MAX_INNER_FIELD_BYTES.toLong()
        )
        assertEquals(
            "池累计上限必须由 min(256 MiB 设计值, 整包上限) 收敛到整包上限",
            payload,
            InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES
        )
    }

    @Test
    fun `内层上限链_单字段不超过池累计不超过整包上限`() {
        val payload = KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES
        val field = InnerHeader.MAX_INNER_FIELD_BYTES.toLong()
        val pool = InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES

        assertTrue("单字段上限 $field 必须 ≤ 池累计上限 $pool，否则单个合法字段落不进池预算", field <= pool)
        // 整改前此处为假（池上限 256 MiB > 整包 128 MiB）→ 该守卫永不生效的语义不自洽
        assertTrue("池累计上限 $pool 必须 ≤ 整包上限 $payload，否则该守卫永不生效", pool <= payload)
    }

    @Test
    fun `整包上限是相对内层上限的 binding 守卫`() {
        // 池字节 ⊆ 整包字节 ⇒ 池上限不得低于整包上限，否则池守卫会先于整包守卫触发、
        // 误拒仍落在护栏内的合法多附件库；收敛取等即「池可独占整个整包预算」，是最不误拒的取值。
        assertTrue(
            "池累计上限不得低于整包上限（否则会先于 binding 守卫误拒合法库）",
            InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES >= KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES
        )
        // 单字段必须严格小于整包上限：一个字段至多占预算的一半，其余留给池内其余条目与 XML 正文
        assertTrue(
            "单字段上限必须严格小于整包上限，为其余字段与 XML 正文保留解析余量",
            InnerHeader.MAX_INNER_FIELD_BYTES.toLong() < KdbxFile.MAX_DECOMPRESSED_PAYLOAD_BYTES
        )
    }

    @Test
    fun `长度守卫为闭区间_恰好等于上限合法而超一字节即拒绝`() {
        // InnerHeader 与 LittleEndianUtil 共用同一长度守卫（readBytes 的 maxLength 校验），
        // 此处以缩小的上限核验其闭区间语义，避免为 64 MiB 量级边界制造真实大分配
        // （64 MiB 级边界的「上限 + 1 被拒」已由 InnerHeaderSecurityTest 以生产常量覆盖）。
        val limit = 8
        val exact = ByteArray(limit) { it.toByte() }

        val read = LittleEndianUtil.readBytes(ByteArrayInputStream(exact), limit, limit)
        assertArrayEquals("恰好等于上限的长度必须被接受（闭区间）", exact, read)

        assertThrows(KdbxCorruptFileException::class.java) {
            LittleEndianUtil.readBytes(ByteArrayInputStream(ByteArray(limit + 1)), limit + 1, limit)
        }
        // 上限之内但流中数据不足属「截断」而非「长度非法」，两类失败必须可区分
        // （否则恶意文件造成的截断会被误报为超限，掩盖真实的解析炸弹信号）
        assertThrows(EOFException::class.java) {
            LittleEndianUtil.readBytes(ByteArrayInputStream(ByteArray(limit - 1)), limit, limit + 1)
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
