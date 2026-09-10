package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-10 子项 2（ZT-21）签名计数器边界回归测试。
 *
 * 旧缺陷：`fromCustomFields` 用 `toIntOrNull() ?: 0` 直接采信不可信 KDBX 文本，
 * 恶意库可写入 `Int.MAX_VALUE`；断言侧 `signCount + 1` 随即回绕为 `Int.MIN_VALUE`（负计数器），
 * 向 RP 交出语义错乱的重放防护状态（CWE-190）。
 *
 * 现契约：解析/钳制/递增三处收口到 [PasskeyData.parseSignCount] / [PasskeyData.clampSignCount] /
 * [PasskeyData.nextSignCount]，任何输入产出的计数器恒落在
 * `SIGN_COUNT_UNKNOWN..MAX_SIGN_COUNT` 闭区间内。
 */
class PasskeyDataSignCountTest {

    // ------------------------------------------------------------------
    // 解析边界
    // ------------------------------------------------------------------

    @Test
    fun `缺失或非法文本一律归未知哨兵`() {
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount(null))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount(""))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount("   "))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount("abc"))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount("12a"))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount("1.5"))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount("-1"))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.parseSignCount("+7"))
    }

    @Test
    fun `合法计数正常解析`() {
        assertEquals(0, PasskeyData.parseSignCount("0"))
        assertEquals(42, PasskeyData.parseSignCount("42"))
        assertEquals(42, PasskeyData.parseSignCount("  42  "))
        assertEquals(123456, PasskeyData.parseSignCount("123456"))
    }

    @Test
    fun `超出上界的取值一律钳制到 MAX_SIGN_COUNT`() {
        // Int.MAX_VALUE 恰为旧实现被注入的溢出前值
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.parseSignCount(Int.MAX_VALUE.toString()))
        // 10 位但超 Int 表达范围
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.parseSignCount("9999999999"))
        // 超长数字串（远超 Long 范围）不得抛异常，直接钳制
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.parseSignCount("99999999999999999999999999"))
    }

    @Test
    fun `MAX_SIGN_COUNT 预留递增余量且为合法非负上界`() {
        assertTrue(PasskeyData.MAX_SIGN_COUNT < Int.MAX_VALUE)
        assertTrue(PasskeyData.MAX_SIGN_COUNT > 0)
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.clampSignCount(-1))
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.clampSignCount(Int.MAX_VALUE))
    }

    // ------------------------------------------------------------------
    // 递增边界（溢出防护）
    // ------------------------------------------------------------------

    @Test
    fun `递增恒非负且不超过上界`() {
        assertEquals(1, PasskeyData.nextSignCount(PasskeyData.SIGN_COUNT_UNKNOWN))
        assertEquals(2, PasskeyData.nextSignCount(1))
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.nextSignCount(PasskeyData.MAX_SIGN_COUNT))
        // 上界饱和：不得回绕为负
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.nextSignCount(Int.MAX_VALUE))
        // 负值（历史溢出产物）先钳制再递增
        assertEquals(1, PasskeyData.nextSignCount(-1))
        assertEquals(1, PasskeyData.nextSignCount(Int.MIN_VALUE))
    }

    @Test
    fun `恶意 Int_MAX_VALUE 经解析后递增不溢出`() {
        val parsed = PasskeyData.parseSignCount(Int.MAX_VALUE.toString())

        val next = PasskeyData.nextSignCount(parsed)

        assertTrue("递增结果必须非负（旧实现此处为 Int.MIN_VALUE）", next >= 0)
        assertTrue(next <= PasskeyData.MAX_SIGN_COUNT)
    }

    // ------------------------------------------------------------------
    // 条目字段边界（读 / 写）
    // ------------------------------------------------------------------

    private fun passkeyFields(signCountText: String?): List<KdbxCustomField> {
        val fields = mutableListOf(
            KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString("example.com", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred-id", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("private-key", isProtected = true))
        )
        if (signCountText != null) {
            fields.add(KdbxCustomField(PasskeyData.FIELD_SIGN_COUNT, ProtectedString(signCountText, isProtected = false)))
        }
        return fields
    }

    @Test
    fun `恶意库写入 Int_MAX_VALUE 时反序列化结果被钳制`() {
        val data = PasskeyData.fromCustomFields(passkeyFields(Int.MAX_VALUE.toString()))

        assertNotNull(data)
        assertEquals(PasskeyData.MAX_SIGN_COUNT, data!!.signCount)
        assertTrue(PasskeyData.nextSignCount(data.signCount) >= 0)
    }

    @Test
    fun `计数器字段缺失时反序列化为未知哨兵`() {
        val data = PasskeyData.fromCustomFields(passkeyFields(null))

        assertNotNull(data)
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, data!!.signCount)
    }

    @Test
    fun `readSignCount 读取库内现值且缺失归哨兵`() {
        assertEquals(7, PasskeyData.readSignCount(passkeyFields("7")))
        assertEquals(PasskeyData.MAX_SIGN_COUNT, PasskeyData.readSignCount(passkeyFields("99999999999")))
        assertEquals(PasskeyData.SIGN_COUNT_UNKNOWN, PasskeyData.readSignCount(passkeyFields(null)))
    }

    @Test
    fun `序列化时越界计数器被钳制后才落字段`() {
        // 通过 copy 构造越界实例（外部调用方可能的构造路径），落库文本不得为负或溢出前值
        val base = PasskeyData.fromCustomFields(passkeyFields("3"))!!

        val negative = base.copy(signCount = -5).toCustomFields()
        assertEquals(
            PasskeyData.SIGN_COUNT_UNKNOWN.toString(),
            negative.first { it.key == PasskeyData.FIELD_SIGN_COUNT }.value.readString()
        )

        val overflowing = base.copy(signCount = Int.MAX_VALUE).toCustomFields()
        assertEquals(
            PasskeyData.MAX_SIGN_COUNT.toString(),
            overflowing.first { it.key == PasskeyData.FIELD_SIGN_COUNT }.value.readString()
        )
    }
}
