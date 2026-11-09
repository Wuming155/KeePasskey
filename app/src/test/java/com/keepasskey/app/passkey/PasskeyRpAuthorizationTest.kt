package com.keepasskey.app.passkey

import com.keepasskey.app.passkey.CredentialManagerPackageBindingGate.Decision
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签发授权裁决（`ENG-20261028-06`）：请求 RP 状态 × 调用方签名绑定。
 *
 * 核心口径：原生调用的**包名相同不等于已授权**——签名绑定是唯一参照，与请求是否声明 RP
 * 无关（请求 JSON 由调用方自报，任何应用都能写 `rpId`）。候选检索的取舍（`UNBOUND` 仍出候选）
 * 在末例单独钉住，避免两处口径漂移成「零候选」或「列出候选 = 已授权」。
 */
class PasskeyRpAuthorizationTest {

    private val rp = "example.com"
    private val pkg = "com.example.app"

    private fun verdict(
        requestRpId: String,
        credentialRpId: String = rp,
        browserFlow: Boolean = false,
        callerBinding: Decision = Decision.AUTHORIZED
    ): PasskeyRpVerdict = PasskeyRpAuthorization.evaluate(
        requestRpId = requestRpId,
        credentialRpId = credentialRpId,
        browserFlow = browserFlow,
        callerBinding = callerBinding
    )

    // ── 原生调用：签名绑定是唯一参照，与 RP 状态无关 ────────────────────

    @Test
    fun `原生未绑定签名时不得签发_显式RP与缺失RP同判`() {
        for (requestRp in listOf("", "   ", rp)) {
            assertEquals(
                "requestRp='$requestRp'：自报的 rpId 只能挑凭据，不能授权调用方",
                PasskeyRpVerdict.CALLER_UNBOUND,
                verdict(requestRp, callerBinding = Decision.UNBOUND)
            )
        }
        assertTrue(verdict(rp, callerBinding = Decision.UNBOUND).rejected)
        assertTrue(
            "该拒绝可经窗口内登记签名补救",
            verdict(rp, callerBinding = Decision.UNBOUND).authorizeCallerInWindow
        )
    }

    @Test
    fun `原生已绑定同一签名时可签发`() {
        for (requestRp in listOf("", rp)) {
            assertEquals(
                "requestRp='$requestRp'：签名绑定命中即可签发",
                PasskeyRpVerdict.ALLOWED,
                verdict(requestRp, callerBinding = Decision.AUTHORIZED)
            )
        }
    }

    @Test
    fun `原生已绑定但签名不符时一律拒绝且不得就地重绑`() {
        for (requestRp in listOf("", rp)) {
            val result = verdict(requestRp, callerBinding = Decision.REJECTED)
            assertEquals(
                "requestRp='$requestRp'：已登记而本次签名不符 ⇒ 拒绝",
                PasskeyRpVerdict.CALLER_BINDING_REJECTED,
                result
            )
            assertFalse(
                "已存在绑定记录却签名不符，就地重绑等于让顶替者自证身份",
                result.authorizeCallerInWindow
            )
        }
    }

    @Test
    fun `原生显式RP不相等时一律拒绝`() {
        Decision.entries.forEach { binding ->
            assertEquals(
                "binding=$binding：请求自报的 RP 不匹配即拒，与包名绑定状态无关",
                PasskeyRpVerdict.RP_MISMATCH,
                verdict("other.example", callerBinding = binding)
            )
        }
    }

    @Test
    fun `原生父域子域不得互相当作匹配`() {
        listOf("login.$rp", rp).forEachIndexed { index, requestRp ->
            val credentialRp = if (index == 0) rp else "login.$rp"
            assertEquals(
                "$requestRp vs $credentialRp：精确相等口径，不做父子域放宽",
                PasskeyRpVerdict.RP_MISMATCH,
                verdict(requestRp, credentialRpId = credentialRp, callerBinding = Decision.AUTHORIZED)
            )
        }
    }

    // ── 浏览器委派：RP 由系统背书 origin 锚定 ─────────────────────────

    @Test
    fun `浏览器缺RP交由origin复校而不是按包名绑定裁决`() {
        Decision.entries.forEach { binding ->
            assertEquals(
                "binding=$binding：WebAuthn 缺省 rpId = origin 有效域，签名绑定不参评",
                PasskeyRpVerdict.ALLOWED,
                verdict("", browserFlow = true, callerBinding = binding)
            )
        }
    }

    @Test
    fun `浏览器显式RP必须与凭据RP相等`() {
        assertEquals(
            PasskeyRpVerdict.ALLOWED,
            verdict(rp, browserFlow = true, callerBinding = Decision.UNBOUND)
        )
        assertEquals(
            PasskeyRpVerdict.RP_MISMATCH,
            verdict("login.$rp", browserFlow = true, callerBinding = Decision.AUTHORIZED)
        )
    }

    // ── 全表 ────────────────────────────────────────────────────────

    @Test
    fun `裁决表覆盖全部输入组合且无未定义格`() {
        val cases = buildList {
            for (browser in listOf(false, true)) {
                for (binding in Decision.entries) {
                    add(Case(browser, rp, binding, expected(browser, rp, binding)))
                    add(Case(browser, "other.example", binding, PasskeyRpVerdict.RP_MISMATCH))
                    add(Case(browser, "", binding, expected(browser, "", binding)))
                }
            }
        }

        assertEquals("2 通道 × 3 绑定 × 3 RP 状态", 18, cases.size)
        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                verdict(case.requestRpId, browserFlow = case.browserFlow, callerBinding = case.binding)
            )
        }
    }

    /** 期望值独立写出（不复用被测实现），使表驱动不会因实现改动而自动「对齐」。 */
    private fun expected(browser: Boolean, requestRp: String, binding: Decision): PasskeyRpVerdict = when {
        browser -> if (requestRp == rp || requestRp.isEmpty()) {
            PasskeyRpVerdict.ALLOWED
        } else {
            PasskeyRpVerdict.RP_MISMATCH
        }

        requestRp != rp && requestRp.isNotEmpty() -> PasskeyRpVerdict.RP_MISMATCH
        binding == Decision.AUTHORIZED -> PasskeyRpVerdict.ALLOWED
        binding == Decision.UNBOUND -> PasskeyRpVerdict.CALLER_UNBOUND
        else -> PasskeyRpVerdict.CALLER_BINDING_REJECTED
    }

    // ── 与候选层的分工（列出候选 ≠ 已授权签发） ───────────────────────

    @Test
    fun `未绑定时候选仍列出但签发被拒`() {
        val entry = KdbxEntry(
            id = KdbxUuid.fromHexString("3".padStart(32, '0')),
            fields = mapOf(KdbxConstants.Fields.URL to ProtectedString("android://$pkg", isProtected = false)),
            customFields = listOf(
                KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString(rp, isProtected = false)),
                KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred", isProtected = false)),
                KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("priv", isProtected = true))
            )
        )

        for (requestRp in listOf("", rp)) {
            assertTrue(
                "候选层不为本项收紧（收紧即零候选，用户失去授权入口）；requestRp='$requestRp'",
                CredentialCandidateMatcher.matchesPasskey(
                    entry, browserFlow = false, targetRpId = requestRp,
                    callingPackage = pkg, packageDimensionAllowed = true
                )
            )
            assertTrue(
                "同一组合在签发侧必须拒绝；requestRp='$requestRp'",
                verdict(requestRp, credentialRpId = rp, callerBinding = Decision.UNBOUND).rejected
            )
        }
    }

    private data class Case(
        val browserFlow: Boolean,
        val requestRpId: String,
        val binding: Decision,
        val expected: PasskeyRpVerdict
    )
}
