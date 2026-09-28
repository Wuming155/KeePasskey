package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [StructuredFieldPolicy] 单元测试（ISSUE-P3-375 AC⑥：识别 / 匹配 / 取值三段正反例）。
 */
class StructuredFieldPolicyTest {

    private val baseTime: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(
        hexId: String,
        fields: Map<String, String> = emptyMap(),
        modifiedAt: Instant = baseTime
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid.fromHexString(hexId),
        customFields = fields.map { (k, v) ->
            KdbxCustomField(k, ProtectedString(v, isProtected = false))
        },
        times = KdbxTimes(lastModificationTime = modifiedAt)
    )

    private fun hexIdOf(n: Int): String = n.toString(16).padStart(32, '0')

    private fun node(
        id: String,
        hints: List<String> = emptyList(),
        autocomplete: String? = null,
        label: String? = null,
        visible: Boolean = true,
        important: Boolean = true
    ): ScanNode = ScanNode(
        id = id,
        autofillHints = hints,
        autocomplete = autocomplete,
        label = label,
        isVisible = visible,
        importantForAutofill = important
    )

    // ===== AC① 识别：hint / autocomplete 可填充，label 只识别不填充 =====

    @Test
    fun `hint 源识别卡与地址角色`() {
        assertEquals(
            StructuredFieldRole.CREDIT_CARD_NUMBER,
            StructuredFieldPolicy.roleFromHint(listOf("creditCardNumber"))
        )
        assertEquals(
            StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
            StructuredFieldPolicy.roleFromHint(listOf("CreditCardSecurityCode"))
        )
        assertEquals(
            StructuredFieldRole.POSTAL_CODE,
            StructuredFieldPolicy.roleFromHint(listOf("postalCode"))
        )
        assertNull("无关 hint 不得误判", StructuredFieldPolicy.roleFromHint(listOf("username")))
        assertNull(StructuredFieldPolicy.roleFromHint(emptyList()))
    }

    @Test
    fun `autocomplete 源多值切分后取首个命中`() {
        assertEquals(
            StructuredFieldRole.CREDIT_CARD_NUMBER,
            StructuredFieldPolicy.roleFromAutocomplete("cc-number")
        )
        assertEquals(
            StructuredFieldRole.CREDIT_CARD_NUMBER,
            StructuredFieldPolicy.roleFromAutocomplete("cc-number cc-csc")
        )
        assertEquals(
            StructuredFieldRole.POSTAL_ADDRESS_LOCALITY,
            StructuredFieldPolicy.roleFromAutocomplete(" address-level2 ")
        )
        assertNull(StructuredFieldPolicy.roleFromAutocomplete(null))
        assertNull(StructuredFieldPolicy.roleFromAutocomplete(""))
        assertNull(StructuredFieldPolicy.roleFromAutocomplete("username web-search"))
    }

    @Test
    fun `label 源只识别不进填充目标`() {
        assertEquals(
            StructuredFieldRole.CREDIT_CARD_NUMBER,
            StructuredFieldPolicy.labelRoleOf("信用卡卡号")
        )
        assertEquals(
            StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
            StructuredFieldPolicy.labelRoleOf("CVV 安全码")
        )
        assertEquals(
            StructuredFieldRole.POSTAL_CODE,
            StructuredFieldPolicy.labelRoleOf("邮政编码")
        )
        assertNull(StructuredFieldPolicy.labelRoleOf("用户名"))
        assertNull(StructuredFieldPolicy.labelRoleOf(null))

        // 关系断言：label-only 节点**不**进 detect 结果（置信不足不填）
        val labelOnly = node("7", label = "卡号")
        assertTrue(StructuredFieldPolicy.detectFillableTargets(listOf(labelOnly)).isEmpty())
    }

    @Test
    fun `识别尊重可见性与 importantForAutofill`() {
        val targets = StructuredFieldPolicy.detectFillableTargets(
            listOf(
                node("1", hints = listOf("creditCardNumber")),
                node("2", autocomplete = "cc-csc"),
                node("3", hints = listOf("creditCardNumber"), visible = false),
                node("4", autocomplete = "postal-code", important = false)
            ),
            respectImportantForAutofill = true
        )
        // 1（hint）与 2（autocomplete）命中；3 不可见、4 页面禁填 ⇒ 双双排除
        assertEquals(
            setOf(
                StructuredFieldRole.CREDIT_CARD_NUMBER,
                StructuredFieldRole.CREDIT_CARD_SECURITY_CODE
            ),
            targets.keys
        )
        assertEquals("1", targets[StructuredFieldRole.CREDIT_CARD_NUMBER])
        assertEquals("2", targets[StructuredFieldRole.CREDIT_CARD_SECURITY_CODE])
        assertFalse(StructuredFieldRole.POSTAL_CODE in targets)

        // 覆盖模式（respect=false）：仅 importantForAutofill 挡的那条被放行
        val overridden = StructuredFieldPolicy.detectFillableTargets(
            listOf(node("4", autocomplete = "postal-code", important = false)),
            respectImportantForAutofill = false
        )
        assertEquals("4", overridden[StructuredFieldRole.POSTAL_CODE])
    }

    @Test
    fun `同角色多节点取首个且确定性`() {
        val targets = StructuredFieldPolicy.detectFillableTargets(
            listOf(
                node("5", hints = listOf("creditCardNumber")),
                node("6", hints = listOf("creditCardNumber"))
            )
        )
        assertEquals("5", targets[StructuredFieldRole.CREDIT_CARD_NUMBER])
    }

    // ===== AC②③ 匹配与取值：缺字段不入选、空白值缺席 =====

    private val numberAndCvv = setOf(
        StructuredFieldRole.CREDIT_CARD_NUMBER,
        StructuredFieldRole.CREDIT_CARD_SECURITY_CODE
    )

    @Test
    fun `候选供给要求全部所需字段齐备`() {
        val full = entry(hexIdOf(1), mapOf("creditCardNumber" to "4111111111111111", "creditCardSecurityCode" to "123"))
        val missingCvv = entry(hexIdOf(2), mapOf("creditCardNumber" to "4222222222222222"))
        val blankCvv = entry(
            hexIdOf(3),
            mapOf("creditCardNumber" to "4333333333333333", "creditCardSecurityCode" to "  ")
        )

        val selected = StructuredFieldPolicy.selectCandidates(listOf(full, missingCvv, blankCvv), numberAndCvv)

        assertEquals("缺字段 / 空白字段的卡不得入选", 1, selected.size)
        assertEquals(hexIdOf(1), selected.first().id.toHexString())
        assertTrue(
            "空需求集恒空",
            StructuredFieldPolicy.selectCandidates(listOf(full), emptySet()).isEmpty()
        )
    }

    @Test
    fun `候选按最后修改时间降序并受上限约束`() {
        val older = entry(hexIdOf(1), mapOf("creditCardNumber" to "4111"), modifiedAt = baseTime)
        val newer = entry(hexIdOf(2), mapOf("creditCardNumber" to "4222"), modifiedAt = baseTime.plusSeconds(60))
        val entries = (3..8).map { entry(hexIdOf(it), mapOf("creditCardNumber" to "x$it"), modifiedAt = baseTime) }

        val ranked = StructuredFieldPolicy.selectCandidates(
            listOf(older) + entries + listOf(newer),
            setOf(StructuredFieldRole.CREDIT_CARD_NUMBER)
        )

        assertEquals(StructuredFieldPolicy.SELECT_LIMIT, ranked.size)
        assertEquals("最新修改在最前", hexIdOf(2), ranked.first().id.toHexString())
    }

    @Test
    fun `取值只返回非空字段且缺失缺席`() {
        val card = entry(
            hexIdOf(1),
            mapOf(
                "creditCardNumber" to "4111111111111111",
                "creditCardSecurityCode" to "   ",
                "postalCode" to "100000"
            )
        )
        val values = StructuredFieldPolicy.valuesFor(
            card,
            setOf(
                StructuredFieldRole.CREDIT_CARD_NUMBER,
                StructuredFieldRole.CREDIT_CARD_SECURITY_CODE,
                StructuredFieldRole.POSTAL_CODE,
                StructuredFieldRole.POSTAL_ADDRESS_REGION
            )
        )

        assertEquals("4111111111111111", values[StructuredFieldRole.CREDIT_CARD_NUMBER])
        assertEquals("100000", values[StructuredFieldRole.POSTAL_CODE])
        assertFalse(
            "空白安全码缺席",
            values.containsKey(StructuredFieldRole.CREDIT_CARD_SECURITY_CODE)
        )
        assertFalse("缺失字段缺席", values.containsKey(StructuredFieldRole.POSTAL_ADDRESS_REGION))
    }

    @Test
    fun `标注与齐备判定`() {
        val card = entry(hexIdOf(1), mapOf("creditCardNumber" to "4111"))
        val plain = entry(hexIdOf(2), mapOf("note" to "just a note"))

        assertTrue(StructuredFieldPolicy.hasAnyStructuredData(card))
        assertFalse("无关自定义字段不构成结构化标注", StructuredFieldPolicy.hasAnyStructuredData(plain))
        assertTrue(StructuredFieldPolicy.hasAllFields(card, setOf(StructuredFieldRole.CREDIT_CARD_NUMBER)))
        assertFalse(StructuredFieldPolicy.hasAllFields(card, numberAndCvv))
        assertFalse("空需求集恒 false", StructuredFieldPolicy.hasAllFields(card, emptySet()))
    }

    @Test
    fun `字段名映射对全部角色可用且稳定`() {
        StructuredFieldRole.entries.forEach { role ->
            val name = StructuredFieldPolicy.fieldNameFor(role)
            assertTrue("字段名不得空白：$role", name.isNotBlank())
            assertEquals("同一角色字段名稳定", name, StructuredFieldPolicy.fieldNameFor(role))
        }
        assertEquals("creditCardNumber", StructuredFieldPolicy.fieldNameFor(StructuredFieldRole.CREDIT_CARD_NUMBER))
    }
}
