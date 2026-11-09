package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HealthCheckEngine 密码健康度与安全审计单元测试
 */
class HealthCheckEngineTest {

    @Test
    fun `测试弱密码与重复密码识别`() {
        val weakEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 1 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site 1", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("123456", isProtected = true)
            )
        )

        val reusedEntry1 = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 2 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site 2", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("StrongPass#2026!", isProtected = true)
            )
        )

        val reusedEntry2 = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 3 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site 3", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("StrongPass#2026!", isProtected = true)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(weakEntry, reusedEntry1, reusedEntry2))
        assertTrue(issues.any { it.riskLevel == PasswordRiskLevel.WEAK })
        assertTrue(issues.any { it.riskLevel == PasswordRiskLevel.REUSED })
    }

    @Test
    fun `测试已声明过期时间的过期条目被标记为 EXPIRED`() {
        val expiredEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 4 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Expired Site", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("StrongPass#2026!", isProtected = true)
            ),
            times = KdbxTimes(
                expires = true,
                expiryTime = Instant.now().minusSeconds(86_400)
            )
        )

        val freshEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 5 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Fresh Site", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("AnotherStrong#2026!", isProtected = true)
            ),
            times = KdbxTimes(expires = true, expiryTime = Instant.now().plusSeconds(86_400))
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(expiredEntry, freshEntry))
        assertTrue(issues.any { it.riskLevel == PasswordRiskLevel.EXPIRED && it.title == "Expired Site" })
        assertFalse(issues.any { it.riskLevel == PasswordRiskLevel.EXPIRED && it.title == "Fresh Site" })
    }

    @Test
    fun `常见弱口令按大小写不敏感匹配（经强度引擎，不物化 String）`() {
        val upperWeak = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 6 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Upper Weak", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("PASSWORD", isProtected = true)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(upperWeak))

        assertTrue(
            "大小写不敏感匹配必须识别 'PASSWORD' 为常见弱口令",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Upper Weak" }
        )
    }

    /**
     * ISSUE-P3-36 回归锁：以下口令**不在**接线前的 15 条常见口令表内、长度也 >= 8，
     * 旧实现（`长度 < 8 || 命中 15 条表`）会完全漏判；接入强度引擎后必须被识别。
     *
     * ENG-20261028-07 起同时覆盖强度模型新增的识别面：8 位 `DDMMYYYY` 日期形状
     * （`31122026`）与单元二次重复（`passwordpassword`）——都不得因「长度够、不在表内」漏判。
     */
    @Test
    fun `模式化弱口令被强度引擎识别（旧实现漏判面）`() {
        val cases = listOf("qwertyuiop", "abcabcabc", "20260101", "31122026", "passwordpassword")
        val entries = cases.mapIndexed { index, pw ->
            KdbxEntry(
                id = KdbxUuid(ByteArray(16) { (index + 20).toByte() }),
                fields = mapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("Pattern $index", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString(pw, isProtected = true)
                )
            )
        }

        val issues = HealthCheckEngine.analyzeEntries(entries)

        for ((index, pw) in cases.withIndex()) {
            assertTrue(
                "模式化弱口令 '$pw' 应被识别为 WEAK",
                issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Pattern $index" }
            )
        }
        // 描述文案应如实带上强度评分（不夸大、不省略）
        assertTrue(
            "WEAK 文案应包含强度评分",
            issues.filter { it.riskLevel == PasswordRiskLevel.WEAK }.all { it.description.contains("/4") }
        )
    }

    /** 强口令不得被误判为 WEAK（防止强度引擎接线引入假阳性）。 */
    @Test
    fun `强口令不被误判为弱口令`() {
        val strong = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 40 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Strong Site", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("tR7#kL9@mQ2!xZ4&vB6*", isProtected = true)
            )
        )

        // ENG-20261028-07：整串是「强单元 × 2」时按单元 + 一次重复计（周期标志置位），
        // 仍属最高档——二次重复惩罚不得把强口令误判为弱。
        val doubledStrong = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 41 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Doubled Strong", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("aB3\$kLmQ7#xZ".repeat(2), isProtected = true)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(strong, doubledStrong))

        assertFalse(
            "长随机口令与「强单元重复」都不应被判为 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK }
        )
    }

    // ===== ISSUE-P3-394：口令为 {REF} 引用时按展开后的真实口令评强度 =====
    //
    // 判据设计：WEAK 描述自带「长度: N」——被引用口令与引用原文长度必然不同，
    // 借此锁定「展开确实发生」而非「恰好同为弱口令」。测试数据全部为虚构假凭据。

    private fun entryOf(title: String, password: String?): KdbxEntry = KdbxEntry(
        id = KdbxUuid.random(),
        fields = buildMap {
            put(KdbxConstants.Fields.TITLE, ProtectedString(title, isProtected = false))
            if (password != null) {
                put(KdbxConstants.Fields.PASSWORD, ProtectedString(password, isProtected = true))
            }
        }
    )

    @Test(timeout = 10_000)
    fun `单级引用按展开后的真实口令评强度`() {
        val target = entryOf("RefTarget", "123456")
        val referrer = entryOf("Referrer", "{REF:P@T:RefTarget}")

        val issues = HealthCheckEngine.analyzeEntries(listOf(target, referrer))

        val weakReferrer = issues.filter {
            it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Referrer"
        }
        assertTrue(
            "引用条目必须按被引用口令（弱）判级",
            weakReferrer.isNotEmpty()
        )
        assertTrue(
            "WEAK 描述的长度必须是展开后的口令长度（6）而非引用原文——据此锁定展开确实发生",
            weakReferrer.all { it.description.contains("长度: 6") }
        )
    }

    @Test(timeout = 10_000)
    fun `多级引用链按链终端口令评强度`() {
        val c = entryOf("C-Chain", "123456")
        val b = entryOf("B-Chain", "{REF:P@T:C-Chain}")
        val a = entryOf("A-Chain", "{REF:P@T:B-Chain}")

        val issues = HealthCheckEngine.analyzeEntries(listOf(a, b, c))

        val weakA = issues.filter {
            it.riskLevel == PasswordRiskLevel.WEAK && it.title == "A-Chain"
        }
        assertTrue(
            "两级引用链必须展开至链终端真实口令（弱）再评强度",
            weakA.isNotEmpty()
        )
        assertTrue(
            "WEAK 描述的长度必须是链终端口令长度（6）",
            weakA.all { it.description.contains("长度: 6") }
        )
    }

    @Test(timeout = 10_000)
    fun `环引用展开终止不悬挂且扫描可完成`() {
        val a = entryOf("RingA", "{REF:P@T:RingB}")
        val b = entryOf("RingB", "{REF:P@T:RingA}")

        // 能走到断言即「终止不悬挂」（timeout 兜底）：环链无终结值，展开通道在深度上限后
        // 保守回退引用原文，扫描不得抛错或死循环
        val issues = HealthCheckEngine.analyzeEntries(listOf(a, b))

        assertNotNull("环引用不得使扫描失败", issues)
    }

    @Test(timeout = 10_000)
    fun `引用强口令的条目不因引用原文被误判弱口令`() {
        val target = entryOf("StrongTarget", "tR7#kL9@mQ2!xZ4&vB6*")
        val referrer = entryOf("StrongReferrer", "{REF:P@T:StrongTarget}")

        val issues = HealthCheckEngine.analyzeEntries(listOf(target, referrer))

        assertFalse(
            "展开后的强口令不得判 WEAK（含展开产物为空的回归防护）",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "StrongReferrer" }
        )
    }

    // ===== ENG-20261028-29：重用检测与强度评估统一按「生效口令」（引用展开后） =====
    //
    // 判据：两趟共用同一展开口径——「直接值」与「指向同一口令的引用」必须归为同一重用；
    // 生效口令不同者（含未命中引用的回退原文与真实口令）不得误判。数据全部为虚构假凭据。

    @Test(timeout = 10_000)
    fun `直接值与指向同一口令的引用归为重用`() {
        val target = entryOf("ReuseTarget", "StrongPass#2026!")
        val direct = entryOf("ReuseDirect", "StrongPass#2026!")
        val referrer = entryOf("ReuseReferrer", "{REF:P@T:ReuseTarget}")

        val issues = HealthCheckEngine.analyzeEntries(listOf(target, direct, referrer))

        val reused = issues.filter { it.riskLevel == PasswordRiskLevel.REUSED }
        assertEquals(
            "直接值、被引用条目与引用条目必须归为同一口令",
            setOf("ReuseTarget", "ReuseDirect", "ReuseReferrer"),
            reused.map { it.title }.toSet()
        )
        assertTrue(
            "重用计数按生效口令聚合，三条均应报告 3 个条目",
            reused.all { it.description.contains("3 个不同条目") }
        )
    }

    @Test(timeout = 10_000)
    fun `多级引用链的终端口令与直接值归为重用`() {
        val c = entryOf("ChainC", "StrongPass#2026!")
        val b = entryOf("ChainB", "{REF:P@T:ChainC}")
        val a = entryOf("ChainA", "{REF:P@T:ChainB}")
        val direct = entryOf("ChainDirect", "StrongPass#2026!")

        val issues = HealthCheckEngine.analyzeEntries(listOf(a, b, c, direct))

        assertEquals(
            "链终端口令与直接值相同，四条必须同组",
            setOf("ChainA", "ChainB", "ChainC", "ChainDirect"),
            issues.filter { it.riskLevel == PasswordRiskLevel.REUSED }.map { it.title }.toSet()
        )
    }

    @Test(timeout = 10_000)
    fun `未命中引用的回退原文不与真实口令误判重用`() {
        val broken = entryOf("BrokenRef", "{REF:P@T:Missing}")
        val real = entryOf("RealSecret", "StrongPass#2026!")

        val issues = HealthCheckEngine.analyzeEntries(listOf(broken, real))

        assertFalse(
            "未命中引用回退的原文与真实口令不同，不得判为重用",
            issues.any { it.riskLevel == PasswordRiskLevel.REUSED }
        )
    }

    @Test(timeout = 10_000)
    fun `两条相同的未命中引用按生效口令同组`() {
        // 未命中引用按引擎语义保留原文，两条相同原文即同一个生效口令——这是两趟同口径的直接
        // 推论：重用统计不得因「原文是引用」而整条跳过（否则等于对引用条目静默失去查重能力）。
        val first = entryOf("BrokenA", "{REF:P@T:Missing}")
        val second = entryOf("BrokenB", "{REF:P@T:Missing}")

        val issues = HealthCheckEngine.analyzeEntries(listOf(first, second))

        val reused = issues.filter { it.riskLevel == PasswordRiskLevel.REUSED }
        assertEquals(setOf("BrokenA", "BrokenB"), reused.map { it.title }.toSet())
        assertTrue(reused.all { it.description.contains("2 个不同条目") })
    }

    @Test(timeout = 10_000)
    fun `展开为空的引用按空口令豁免不判弱也不计重用`() {
        val emptyTarget = entryOf("EmptyTarget", "")
        val refToEmpty1 = entryOf("RefToEmptyA", "{REF:P@T:EmptyTarget}")
        val refToEmpty2 = entryOf("RefToEmptyB", "{REF:P@T:EmptyTarget}")

        val issues = HealthCheckEngine.analyzeEntries(listOf(emptyTarget, refToEmpty1, refToEmpty2))

        assertFalse(
            "生效口令为空＝本条目不需要密码，不判 WEAK 也不计重用",
            issues.any {
                it.title in setOf("EmptyTarget", "RefToEmptyA", "RefToEmptyB") &&
                    it.riskLevel in setOf(PasswordRiskLevel.WEAK, PasswordRiskLevel.REUSED)
            }
        )
    }

    // ---- ISSUE-P3-408：空密码一律不判弱密码；弱口令仍判 WEAK ----

    private fun passkeyEntryOf(
        title: String,
        password: String? = null,
        customKeys: List<String> = listOf(
            PasskeyData.KPEX_FIELD_RELYING_PARTY,
            PasskeyData.KPEX_FIELD_CREDENTIAL_ID,
            PasskeyData.KPEX_FIELD_PRIVATE_KEY
        ),
        times: KdbxTimes = KdbxTimes()
    ): KdbxEntry {
        val fields = mutableMapOf<String, ProtectedString>(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)
        )
        if (password != null) {
            fields[KdbxConstants.Fields.PASSWORD] = ProtectedString(password, isProtected = true)
        }
        return KdbxEntry(
            id = KdbxUuid.random(),
            fields = fields,
            customFields = customKeys.map { key ->
                KdbxCustomField(
                    key = key,
                    value = ProtectedString("test-only-$key", isProtected = true)
                )
            },
            times = times
        )
    }

    @Test(timeout = 10_000)
    fun `空密码一律不判弱密码——含普通条目与通行密钥`() {
        val plainEmpty = entryOf("Legacy Empty", "")
        val passkeyEmpty = passkeyEntryOf("GitHub Passkey", password = null)
        val nullPassword = entryOf("Null Password", null)

        val issues = HealthCheckEngine.analyzeEntries(listOf(plainEmpty, passkeyEmpty, nullPassword))

        assertFalse(
            "空密码语义是「不需要密码」，不得报 WEAK",
            issues.any {
                it.riskLevel == PasswordRiskLevel.WEAK &&
                    it.title in setOf("Legacy Empty", "GitHub Passkey", "Null Password")
            }
        )
    }

    @Test(timeout = 10_000)
    fun `已填写的弱口令仍判弱密码`() {
        val weak = entryOf("Legacy Weak", "123456")
        // ENG-20261028-07：7 个 emoji 是 7 个**码点**（UTF-16 下为 14 码元）→ TOO_SHORT → WEAK；
        // 文案里的「长度」必须按码点给出 7，与强度模型的长度口径一致。
        val emojiWeak = entryOf("Emoji Weak", "🔐🔑🧩🛡🔒🔓🚀")

        val issues = HealthCheckEngine.analyzeEntries(listOf(weak, emojiWeak))

        assertTrue(
            "非空弱口令仍必须报 WEAK（强度审计只针对已填写口令）",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Legacy Weak" }
        )
        assertTrue(
            "7 个 emoji 不足 8 个码点，必须报 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Emoji Weak" }
        )
        assertTrue(
            "WEAK 文案的长度必须是码点数（7），不是 UTF-16 码元数（14）",
            issues.filter { it.title == "Emoji Weak" && it.riskLevel == PasswordRiskLevel.WEAK }
                .all { it.description.contains("长度: 7") }
        )
    }

    @Test(timeout = 10_000)
    fun `通行密钥若设置了弱密码仍参与强度评估`() {
        val weakPasskey = passkeyEntryOf("Passkey With Weak Password", password = "123456")

        val issues = HealthCheckEngine.analyzeEntries(listOf(weakPasskey))

        assertTrue(
            "passkey 条目一旦设置弱口令，强度判定不得被身份豁免",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Passkey With Weak Password" }
        )
    }

    @Test(timeout = 10_000)
    fun `通行密钥空密码仍可报告过期条目`() {
        val expiredPasskey = passkeyEntryOf(
            title = "Expired Passkey",
            password = null,
            times = KdbxTimes(
                expires = true,
                expiryTime = Instant.now().minusSeconds(86_400)
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(expiredPasskey))

        assertTrue(
            "空密码跳过弱判定后，EXPIRED 时效检查仍须生效",
            issues.any { it.riskLevel == PasswordRiskLevel.EXPIRED && it.title == "Expired Passkey" }
        )
        assertFalse(
            "过期 passkey 不得因空密码报 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Expired Passkey" }
        )
    }
}
