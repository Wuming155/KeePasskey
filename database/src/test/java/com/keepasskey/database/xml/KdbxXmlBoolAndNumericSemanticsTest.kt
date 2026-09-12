package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Base64

/**
 * KDBX 4 XML 布尔与数值字段的官方语义回归（审计项 D11 / D12 / D15 / D16）。
 *
 * **官方裁决依据**（`KeePassLib/Serialization/KdbxFile.Read.Streamed.cs`，逐行核对）：
 * - `ReadBool(xr, bDefault)`（:834-841）：精确比较 `"True"` / `"False"`，非法值回落**字段各自的默认值**；
 * - `ReadNullableBool(xr, obDefault)`（:844-853）：大小写不敏感，仅用于 `EnableAutoType`（:392）/
 *   `EnableSearching`（:394），默认 null（继承）；
 * - `ReadIconId(xr, icDefault)`（:949-956）：非法或越界（不在 `0..68` = `PwIcon.Count` 内）回落默认图标；
 * - `ReadULong(xr, 0)`（:904-916）：`UsageCount` 为 TUInt64。
 *
 * 映射表（`非法值` 一列即本测试逐条固化的行为）：
 * | XML 元素 | 官方类型 | 缺省值 | `"True"` | `"False"` | 非法值（`1` / `0` / `true` / 空串 / `" True "` …） |
 * |---|---|---|---|---|---|
 * | `Times/Expires` | `ReadBool(xr,false)`（:501） | false | true | false | **false** |
 * | `Group/IsExpanded` | `ReadBool(xr,true)`（:388） | true | true | false | **true** |
 * | `Entry/QualityCheck` | `ReadBool(xr,true)`（:459） | true | true | false | **true** |
 * | `Group/EnableAutoType` | `ReadNullableBool(xr,null)`（:392） | null（继承） | true | false | **null** |
 * | `Group/EnableSearching` | `ReadNullableBool(xr,null)`（:394） | null（继承） | true | false | **null** |
 * | `IconID`（组 / 条目） | `ReadIconId`（:382 / :441） | 48（组）/ 0（条目） | — | — | **默认图标**（越界 `69`、负数、溢出、非数字） |
 * | `Times/UsageCount` | `ReadULong(xr,0)`（:503） | 0 | — | — | **0**（负数 / 非数字 / 超出 ulong 上界） |
 *
 * 时间缺省（D11/D12）：`<Times>` 整体缺失与子元素缺失都取 0001-01-01T00:00:00Z
 * （官方未初始化时间 = .NET `DateTime.MinValue`），**绝不取 now()**，以免在
 * 「lastModificationTime 越新越胜出」的三方合并中被误判为「刚刚修改」。
 */
class KdbxXmlBoolAndNumericSemanticsTest {

    private val groupUuidBase64: String = Base64.getEncoder().encodeToString(ByteArray(16) { 0x11 })
    private val entryUuidBase64: String = Base64.getEncoder().encodeToString(ByteArray(16) { 0x22 })

    // ---------------------------------------------------------------- 纯解析函数映射表

    @Test
    fun testParseBoolExactLiteralsAndFieldDefaults() {
        // 合法字面量：精确匹配（大小写敏感、不 trim）
        assertEquals(true, KdbxXmlScalarParsers.parseBool("True", false))
        assertEquals(false, KdbxXmlScalarParsers.parseBool("False", true))

        // 非法值一律回落调用方给出的字段默认值（不是统一 false）
        val illegal = listOf("1", "0", "true", "false", "TRUE", "FALSE", " True ", "False ", "", " ", "null", "yes", "-1")
        for (raw in illegal) {
            assertEquals("parseBool(\"$raw\", default=true)", true, KdbxXmlScalarParsers.parseBool(raw, true))
            assertEquals("parseBool(\"$raw\", default=false)", false, KdbxXmlScalarParsers.parseBool(raw, false))
        }

        // 元素缺失（null）同样回落默认值
        assertEquals(true, KdbxXmlScalarParsers.parseBool(null, true))
        assertEquals(false, KdbxXmlScalarParsers.parseBool(null, false))
    }

    @Test
    fun testParseNullableBoolCaseInsensitiveAndNullInheritance() {
        // 官方 ReadNullableBool 用 StrUtil.CaseIgnoreCmp：大小写不敏感
        assertEquals(true, KdbxXmlScalarParsers.parseNullableBool("True"))
        assertEquals(true, KdbxXmlScalarParsers.parseNullableBool("true"))
        assertEquals(true, KdbxXmlScalarParsers.parseNullableBool("TRUE"))
        assertEquals(false, KdbxXmlScalarParsers.parseNullableBool("False"))
        assertEquals(false, KdbxXmlScalarParsers.parseNullableBool("false"))

        // "Null"/"null"/非法值（含 "1"）→ null（继承父组）
        val inherit = listOf(null, "", " ", "null", "Null", "NULL", "1", "0", "yes", " True ")
        for (raw in inherit) {
            assertNull("parseNullableBool(\"$raw\")", KdbxXmlScalarParsers.parseNullableBool(raw))
        }
    }

    @Test
    fun testParseIconIdRangeClamp() {
        assertEquals(48, KdbxXmlScalarParsers.parseIconIdOrNull("48"))
        assertEquals(0, KdbxXmlScalarParsers.parseIconIdOrNull("0"))
        assertEquals(68, KdbxXmlScalarParsers.parseIconIdOrNull("68"))
        assertEquals(27, KdbxXmlScalarParsers.parseIconIdOrNull(" +27 "))

        val invalid = listOf(
            "69", "70", "-1", "abc", "", " ", "1.0", "0x30", "null",
            "2147483647", "2147483648", "99999999999999999999", null
        )
        for (raw in invalid) {
            assertNull("parseIconIdOrNull(\"$raw\")", KdbxXmlScalarParsers.parseIconIdOrNull(raw))
        }
    }

    @Test
    fun testParseUsageCountUnsignedSemantics() {
        assertEquals(0L, KdbxXmlScalarParsers.parseUsageCountOrZero(null))
        assertEquals(0L, KdbxXmlScalarParsers.parseUsageCountOrZero(""))
        assertEquals(0L, KdbxXmlScalarParsers.parseUsageCountOrZero("   "))
        assertEquals(0L, KdbxXmlScalarParsers.parseUsageCountOrZero("0"))
        assertEquals(42L, KdbxXmlScalarParsers.parseUsageCountOrZero("42"))
        assertEquals(42L, KdbxXmlScalarParsers.parseUsageCountOrZero(" 42 "))
        assertEquals(7L, KdbxXmlScalarParsers.parseUsageCountOrZero("+7"))
        assertEquals(42L, KdbxXmlScalarParsers.parseUsageCountOrZero("00000000000000000000042"))

        // 负号 / 非数字 / 超出 TUInt64 上界 → 0（绝不抛未类型化异常）
        val invalid = listOf("-1", "-0", "abc", "1.5", "1e3", "0x10", "１２",
            "18446744073709551616", "99999999999999999999999999")
        for (raw in invalid) {
            assertEquals("parseUsageCountOrZero(\"$raw\")", 0L, KdbxXmlScalarParsers.parseUsageCountOrZero(raw))
        }

        // 合法 TUInt64 但超出 Long 表示范围 → 饱和（保持非递减排序语义，绝不回绕成负数）
        assertEquals(Long.MAX_VALUE, KdbxXmlScalarParsers.parseUsageCountOrZero("9223372036854775807"))
        assertEquals(Long.MAX_VALUE, KdbxXmlScalarParsers.parseUsageCountOrZero("9223372036854775808"))
        assertEquals(Long.MAX_VALUE, KdbxXmlScalarParsers.parseUsageCountOrZero("18446744073709551615"))
    }

    // ---------------------------------------------------------------- 端到端 XML 语义

    @Test
    fun testExpiresOfficialReadBoolMappingEndToEnd() {
        val cases = listOf(
            "True" to true,
            "False" to false,
            "1" to false,        // D15 明确要求：<Expires>1</Expires> → false（官方语义）
            "0" to false,
            "true" to false,
            "TRUE" to false,
            " True " to false,   // 官方裸字符串精确比较，不 trim
            "" to false,
            "null" to false,
            "yes" to false
        )
        for ((raw, expected) in cases) {
            val group = groupWithTimes("<Expires>$raw</Expires>")
            assertEquals("Times/Expires=$raw", expected, group.times.expires)
        }

        // 自闭合 <Expires/> 等价空文本 → 默认 false
        assertFalse(groupWithTimes("<Expires/>").times.expires)
        // 元素缺失 → 默认 false
        assertFalse(groupWithTimes("<UsageCount>1</UsageCount>").times.expires)
    }

    @Test
    fun testIsExpandedOfficialReadBoolMappingEndToEnd() {
        val cases = listOf(
            "True" to true,
            "False" to false,
            "1" to true,      // 非法值回落字段默认 true（不是 false）
            "0" to true,
            "true" to true,
            "" to true,
            "no" to true
        )
        for ((raw, expected) in cases) {
            val group = groupWithScalar("<IsExpanded>$raw</IsExpanded>")
            assertEquals("Group/IsExpanded=$raw", expected, group.isExpanded)
        }
        // 元素缺失 → 默认 true
        assertTrue(groupWithScalar("<Name>X</Name>").isExpanded)
    }

    @Test
    fun testQualityCheckOfficialReadBoolMappingEndToEnd() {
        val cases = listOf(
            "True" to true,
            "False" to false,
            "1" to true,
            "0" to true,
            "false" to true,
            "" to true
        )
        for ((raw, expected) in cases) {
            val entry = entryWithScalar("<QualityCheck>$raw</QualityCheck>")
            assertEquals("Entry/QualityCheck=$raw", expected, entry.qualityCheck)
        }
        // 元素缺失 → 默认 true
        assertTrue(entryWithScalar("<IconID>0</IconID>").qualityCheck)
    }

    @Test
    fun testNullableGroupBoolsOfficialMappingEndToEnd() {
        val autoTypeCases = listOf(
            "True" to true,
            "true" to true,
            "TRUE" to true,
            "False" to false,
            "false" to false,
            "1" to null,      // D15 明确要求：<EnableAutoType>1</EnableAutoType> → 继承 null，而非 false
            "null" to null,
            "Null" to null,
            "" to null,
            "yes" to null
        )
        for ((raw, expected) in autoTypeCases) {
            val group = groupWithScalar("<EnableAutoType>$raw</EnableAutoType>")
            assertEquals("Group/EnableAutoType=$raw", expected, group.enableAutoType)
        }

        val searchingCases = listOf(
            "True" to true,
            "FALSE" to false,
            "0" to null,
            "null" to null
        )
        for ((raw, expected) in searchingCases) {
            val group = groupWithScalar("<EnableSearching>$raw</EnableSearching>")
            assertEquals("Group/EnableSearching=$raw", expected, group.enableSearching)
        }

        // 两个元素都缺失 → 均为 null（继承），绝不退化为 false
        val bare = groupWithScalar("<Name>X</Name>")
        assertNull(bare.enableAutoType)
        assertNull(bare.enableSearching)
    }

    @Test
    fun testIconIdRangeClampEndToEnd() {
        val groupCases = listOf(
            "48" to 48,
            "0" to 0,
            "68" to 68,
            "69" to 48,          // 越界（PwIcon.Count 虚拟值）→ 组默认 48
            "-1" to 48,
            "abc" to 48,
            "" to 48,
            "2147483648" to 48,  // int 溢出
            "99999999999999999999" to 48
        )
        for ((raw, expected) in groupCases) {
            val group = groupWithScalar("<IconID>$raw</IconID>")
            assertEquals("Group/IconID=$raw", expected, group.iconId)
        }
        assertEquals("Group/IconID 缺失", 48, groupWithScalar("<Name>X</Name>").iconId)

        val entryCases = listOf(
            "0" to 0,
            "27" to 27,
            "68" to 68,
            "69" to 0,           // 越界 → 条目默认 0（PwIcon.Key）
            "-5" to 0,
            "abc" to 0,
            "" to 0,
            "2147483648" to 0
        )
        for ((raw, expected) in entryCases) {
            val entry = entryWithScalar("<IconID>$raw</IconID>")
            assertEquals("Entry/IconID=$raw", expected, entry.iconId)
        }
        assertEquals("Entry/IconID 缺失", 0, entryWithScalar("<QualityCheck>True</QualityCheck>").iconId)
    }

    @Test
    fun testUsageCountUnsignedEndToEnd() {
        val cases = listOf(
            "0" to 0L,
            "42" to 42L,
            " 7 " to 7L,
            "-1" to 0L,
            "abc" to 0L,
            "" to 0L,
            "1.5" to 0L,
            "18446744073709551616" to 0L,      // TUInt64 上界 + 1 → 非法 → 0
            "99999999999999999999999999" to 0L
        )
        for ((raw, expected) in cases) {
            val group = groupWithTimes("<UsageCount>$raw</UsageCount>")
            assertEquals("Times/UsageCount=$raw", expected, group.times.usageCount)
        }
        // 元素缺失 → 0
        assertEquals(0L, groupWithTimes("<Expires>False</Expires>").times.usageCount)
    }

    // ---------------------------------------------------------------- D11 / D12 时间缺省

    @Test
    fun testMissingTimesElementDefaultsToAncientForGroupAndEntry() {
        // <Times> 整体缺失：官方未初始化时间 = .NET DateTime.MinValue = 0001-01-01T00:00:00Z
        val group = groupWithScalar("<Name>NoTimes</Name>")
        assertAncientTimes(group.times)

        val entry = entryWithScalar("<IconID>0</IconID>")
        assertAncientTimes(entry.times)
    }

    @Test
    fun testTimesSubElementsMissingDefaultToAncient() {
        // <Times> 存在但子元素缺失（官方 ReadTime 只赋值出现的子元素）→ 其余保持远古缺省
        val group = groupWithTimes("<Expires>False</Expires>")
        assertAncientTimes(group.times)

        val entry = entryWithScalar(
            "<Times><Expires>True</Expires></Times>"
        )
        assertAncientTimes(entry.times, expectedExpires = true)
    }

    /**
     * D11/D12 的核心不变式：缺省时间恒早于 Unix 纪元，故在「lastModificationTime 越新越胜出」
     * 的三方合并中**不可能**虚假胜出对端（这正是旧实现取 now() 的失效模式）。
     */
    @Test
    fun testAncientDefaultNeverWinsNewerWinsMerge() {
        val ancient = KdbxXmlTimeHelper.ANCIENT_INSTANT
        val counterpart = Instant.parse("1970-01-01T00:00:00Z")

        assertTrue("远古缺省必须早于 Unix 纪元", ancient.isBefore(Instant.EPOCH))
        // 「越新越胜出」判定的实际比较式：缺省值恒不满足 isAfter，绝不会被选为较新一侧
        assertFalse(ancient.isAfter(counterpart))
        assertTrue(ancient.isBefore(counterpart))
    }

    // ---------------------------------------------------------------- 组合防御：全非法字段不得抛异常

    @Test
    fun testAllInvalidScalarsFallBackToDefaultsWithoutException() {
        val xml = rootGroupXml(
            "<IconID>-999</IconID>",
            "<IsExpanded>1</IsExpanded>",
            "<EnableAutoType>1</EnableAutoType>",
            "<EnableSearching>maybe</EnableSearching>",
            "<Times><Expires>1</Expires><UsageCount>-5</UsageCount></Times>",
            entryXml(
                "<IconID>huge</IconID>",
                "<QualityCheck>1</QualityCheck>",
                "<Times><Expires>1</Expires><UsageCount>99999999999999999999999999</UsageCount></Times>"
            )
        )

        val group = parse(xml).rootGroup
        assertEquals(48, group.iconId)
        assertTrue(group.isExpanded)
        assertNull(group.enableAutoType)
        assertNull(group.enableSearching)
        assertFalse(group.times.expires)
        assertEquals(0L, group.times.usageCount)

        val entry = group.entries[0]
        assertEquals(0, entry.iconId)
        assertTrue(entry.qualityCheck)
        assertFalse(entry.times.expires)
        assertEquals(0L, entry.times.usageCount)
    }

    // ---------------------------------------------------------------- 辅助

    private fun assertAncientTimes(times: KdbxTimes, expectedExpires: Boolean = false) {
        val ancient = KdbxXmlTimeHelper.ANCIENT_INSTANT
        assertEquals("creationTime", ancient, times.creationTime)
        assertEquals("lastModificationTime", ancient, times.lastModificationTime)
        assertEquals("lastAccessTime", ancient, times.lastAccessTime)
        assertEquals("expiryTime", ancient, times.expiryTime)
        assertEquals("locationChanged", ancient, times.locationChanged)
        assertEquals("expires", expectedExpires, times.expires)
        // 缺省绝不取 now()：必须远早于 1970
        assertTrue("缺省时间必须远早于 Unix 纪元", times.lastModificationTime.isBefore(Instant.EPOCH))
    }

    private fun parse(xml: String): KdbxXmlParser.ParseResult =
        KdbxXmlParser(null).parse(ByteArrayInputStream(xml.toByteArray()))

    private fun rootGroupXml(vararg children: String): String = """
        <KeePassFile>
            <Root>
                <Group>
                    <UUID>$groupUuidBase64</UUID>
                    <Name>G</Name>
                    ${children.joinToString("\n")}
                </Group>
            </Root>
        </KeePassFile>
    """.trimIndent()

    private fun entryXml(vararg children: String): String = """
        <Entry>
            <UUID>$entryUuidBase64</UUID>
            ${children.joinToString("\n")}
        </Entry>
    """.trimIndent()

    /** 构造仅含某个 Group 级标量元素的文档 */
    private fun groupWithScalar(scalarXml: String): KdbxGroup = parse(rootGroupXml(scalarXml)).rootGroup

    /** 构造含 `<Times>` 子树的 Group 文档 */
    private fun groupWithTimes(timesChildren: String): KdbxGroup =
        parse(rootGroupXml("<Times>$timesChildren</Times>")).rootGroup

    /** 构造仅含某个 Entry 级标量元素的文档，返回根组内的首个条目 */
    private fun entryWithScalar(scalarXml: String) =
        parse(rootGroupXml(entryXml(scalarXml))).rootGroup.entries[0]
}
