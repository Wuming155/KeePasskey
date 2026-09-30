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
     */
    @Test
    fun `模式化弱口令被强度引擎识别（旧实现漏判面）`() {
        val cases = listOf("qwertyuiop", "abcabcabc", "20260101")
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

        val issues = HealthCheckEngine.analyzeEntries(listOf(strong))

        assertFalse(
            "长随机口令不应被判为 WEAK",
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

    // ---- ISSUE-P3-407：通行密钥空密码不得误判为弱密码 ----

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
    fun `通行密钥条目空密码不判弱密码`() {
        val passkey = passkeyEntryOf("GitHub Passkey", password = null)

        val issues = HealthCheckEngine.analyzeEntries(listOf(passkey))

        assertFalse(
            "通行密钥无传统密码属正常形态，空密码不得报 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "GitHub Passkey" }
        )
    }

    @Test(timeout = 10_000)
    fun `普通条目空密码仍判弱密码`() {
        val empty = entryOf("Legacy Empty", "")

        val issues = HealthCheckEngine.analyzeEntries(listOf(empty))

        assertTrue(
            "非 passkey 空密码仍必须报 WEAK（不得连带放宽）",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Legacy Empty" }
        )
    }

    @Test(timeout = 10_000)
    fun `通行密钥若设置了密码仍参与强度评估`() {
        val weakPasskey = passkeyEntryOf("Passkey With Weak Password", password = "123456")

        val issues = HealthCheckEngine.analyzeEntries(listOf(weakPasskey))

        assertTrue(
            "passkey 条目一旦设置密码，弱口令判定不得被 passkey 身份豁免",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Passkey With Weak Password" }
        )
    }

    @Test(timeout = 10_000)
    fun `不完整 passkey schema 也不把空密码判弱`() {
        val partial = passkeyEntryOf(
            title = "Partial Passkey",
            password = null,
            customKeys = listOf(
                PasskeyData.KPEX_FIELD_RELYING_PARTY
            )
        )

        val issues = HealthCheckEngine.analyzeEntries(listOf(partial))

        assertFalse(
            "半成品 passkey（仅 RP 键）同样不得被空密码误报 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Partial Passkey" }
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
            "跳过空密码弱判定后，EXPIRED 时效检查仍须生效",
            issues.any { it.riskLevel == PasswordRiskLevel.EXPIRED && it.title == "Expired Passkey" }
        )
        assertFalse(
            "过期 passkey 不得同时因空密码报 WEAK",
            issues.any { it.riskLevel == PasswordRiskLevel.WEAK && it.title == "Expired Passkey" }
        )
    }
}
