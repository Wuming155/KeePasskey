package com.keepasskey.app.passkey

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CM 通道**候选层**入选判定单元测试（**ISSUE-P2-83** AC②）。
 *
 * 与 [CredentialManagerPackageBindingGateTest]（门控三值语义）互补：本文件断言门控结果
 * **确实作用到候选入选**——即端到端的「同包名不同签名 → 不命中」。
 */
class CredentialCandidateMatcherTest {

    private val pkg = "com.example.app"
    private val domain = "example.com"

    private fun passwordEntry(url: String): KdbxEntry = KdbxEntry(
        id = KdbxUuid.fromHexString("0".repeat(32)),
        fields = mapOf(
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString("secret", isProtected = true)
        )
    )

    private fun passwordlessEntry(url: String): KdbxEntry = KdbxEntry(
        id = KdbxUuid.fromHexString("1".padStart(32, '0')),
        fields = mapOf(KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false))
    )

    private fun passkeyEntry(url: String, rpId: String): KdbxEntry = KdbxEntry(
        id = KdbxUuid.fromHexString("2".padStart(32, '0')),
        fields = mapOf(KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)),
        customFields = listOf(
            KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString(rpId, isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("priv", isProtected = true))
        )
    )

    // ── 密码候选（AC② 第一条：同包名不同签名 → 不命中） ──────────────

    @Test
    fun `包名维度未放行时 android 绑定密码条目不得入选`() {
        val entry = passwordEntry("android://$pkg")

        assertFalse(
            "门控判定为不放行（=已绑定但签名不匹配）时，候选层必须不产出该条目",
            CredentialCandidateMatcher.matchesPassword(
                entry, targetDomain = "", callingPackage = pkg, packageDimensionAllowed = false
            )
        )
        assertTrue(
            "反之放行时必须入选（确认前置条件是门控而非别的因素）",
            CredentialCandidateMatcher.matchesPassword(
                entry, targetDomain = "", callingPackage = pkg, packageDimensionAllowed = true
            )
        )
    }

    @Test
    fun `域维度不受包名维度门控影响`() {
        val entry = passwordEntry("https://$domain")

        assertTrue(
            "浏览器委派路径的域匹配与包名维度门控无关",
            CredentialCandidateMatcher.matchesPassword(
                entry, targetDomain = domain, callingPackage = pkg, packageDimensionAllowed = false
            )
        )
    }

    @Test
    fun `无密码条目一律不入选`() {
        assertFalse(
            CredentialCandidateMatcher.matchesPassword(
                passwordlessEntry("android://$pkg"),
                targetDomain = "",
                callingPackage = pkg,
                packageDimensionAllowed = true
            )
        )
        assertFalse(
            CredentialCandidateMatcher.matchesPassword(
                passwordlessEntry("https://$domain"),
                targetDomain = domain,
                callingPackage = pkg,
                packageDimensionAllowed = true
            )
        )
    }

    @Test
    fun `包名不匹配的密码条目不入选`() {
        assertFalse(
            CredentialCandidateMatcher.matchesPassword(
                passwordEntry("android://com.example.other"),
                targetDomain = "",
                callingPackage = pkg,
                packageDimensionAllowed = true
            )
        )
    }

    @Test
    fun `Web 绑定条目不得经同形包名入选`() {
        // P2-40 既有约束：https:// 绑定只走域维度
        assertFalse(
            CredentialCandidateMatcher.matchesPassword(
                passwordEntry("https://$pkg"),
                targetDomain = "",
                callingPackage = pkg,
                packageDimensionAllowed = true
            )
        )
    }

    // ── 通行密钥候选 ────────────────────────────────────────────────

    @Test
    fun `包名维度未放行时 android 绑定通行密钥条目不得入选`() {
        val entry = passkeyEntry("android://$pkg", rpId = pkg)

        assertFalse(
            "普通应用路径同样受签名绑定门控约束（AC②）",
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = false, targetRpId = "", callingPackage = pkg, packageDimensionAllowed = false
            )
        )
        assertTrue(
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = false, targetRpId = "", callingPackage = pkg, packageDimensionAllowed = true
            )
        )
    }

    @Test
    fun `浏览器委派路径按 RP-ID 域匹配且与包名门控无关`() {
        val entry = passkeyEntry("https://$domain", rpId = domain)

        assertTrue(
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = true, targetRpId = domain, callingPackage = "com.android.chrome",
                packageDimensionAllowed = false
            )
        )
        assertFalse(
            "RP-ID 不匹配时不得入选",
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = true, targetRpId = "evil.example", callingPackage = "com.android.chrome",
                packageDimensionAllowed = false
            )
        )
    }

    // ── allowCredentials 收敛（本次整改：请求列出的凭据之外一律不得下发候选） ──

    @Test
    fun `allowCredentials 非空时白名单外的凭据不得入选`() {
        val entry = passkeyEntry("https://$domain", rpId = domain)

        assertFalse(
            "请求明确列出 allowCredentials 而本凭据不在其中时，不得作为候选下发",
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = true, targetRpId = domain, callingPackage = "com.android.chrome",
                packageDimensionAllowed = false, allowedCredentialIds = setOf("other-cred")
            )
        )
        assertTrue(
            "凭据 id 在白名单内时必须入选",
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = true, targetRpId = domain, callingPackage = "com.android.chrome",
                packageDimensionAllowed = false, allowedCredentialIds = setOf("cred")
            )
        )
        assertTrue(
            "空集表示请求未限定（无用户名 / discoverable 流程），不得收敛",
            CredentialCandidateMatcher.matchesPasskey(
                entry, browserFlow = true, targetRpId = domain, callingPackage = "com.android.chrome",
                packageDimensionAllowed = false, allowedCredentialIds = emptySet()
            )
        )
    }

    @Test
    fun `非 passkey 条目不得进入通行密钥候选`() {
        assertFalse(
            CredentialCandidateMatcher.matchesPasskey(
                passwordEntry("android://$pkg"),
                browserFlow = false,
                targetRpId = "",
                callingPackage = pkg,
                packageDimensionAllowed = true
            )
        )
    }
}
