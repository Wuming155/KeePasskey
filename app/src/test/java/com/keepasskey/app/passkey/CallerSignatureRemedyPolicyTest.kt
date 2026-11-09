package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「登记此应用签名」补救的资格判定（`ENG-20261028-06`）。
 *
 * 关键安全点：**已绑定而签名不符时绝不提供登记入口**——那等于让顶替者自证身份。
 */
class CallerSignatureRemedyPolicyTest {

    private val pkg = "com.example.app"
    private val digestA = "AA".repeat(32)
    private val digests = CallerCertDigests.ofSingle(digestA)

    private fun eligible(
        reason: CredentialRejectionReason = CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND,
        callerPackage: String? = pkg,
        certDigests: CallerCertDigests = digests,
        hasAnyBinding: Boolean = false
    ): Boolean = CallerSignatureRemedyPolicy.eligible(reason, callerPackage, certDigests, hasAnyBinding)

    @Test
    fun `未登记签名且摘要可读时提供登记入口`() {
        assertTrue(eligible())
    }

    @Test
    fun `已登记而签名不符时绝不提供登记入口`() {
        assertFalse(
            "已存在绑定记录 ⇒ 就地登记等于让顶替者自证身份",
            eligible(hasAnyBinding = true)
        )
        assertFalse(
            "签名不符的拒绝原因本身也不得带登记动作",
            eligible(reason = CredentialRejectionReason.CALLER_SIGNATURE_MISMATCH)
        )
    }

    @Test
    fun `摘要不可读或包名为空时不给假入口`() {
        assertFalse(
            "写「仅包名」降级键正是本项要消灭的形态",
            eligible(certDigests = CallerCertDigests.EMPTY)
        )
        assertFalse(eligible(callerPackage = null))
        assertFalse(eligible(callerPackage = "   "))
    }

    @Test
    fun `其他拒绝原因不得复用本入口`() {
        listOf(
            CredentialRejectionReason.REQUEST_INVALID,
            CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED,
            CredentialRejectionReason.DAL_UNVERIFIED,
            CredentialRejectionReason.INTERNAL_ERROR
        ).forEach { reason ->
            assertFalse("$reason 的正解不是登记调用方签名", eligible(reason = reason))
        }
    }

    @Test
    fun `与签发裁决的可补授权判据一致`() {
        val verdict = PasskeyRpAuthorization.evaluate(
            requestRpId = "",
            credentialRpId = "example.com",
            browserFlow = false,
            callerBinding = CredentialManagerPackageBindingGate.Decision.UNBOUND
        )
        assertTrue(
            "裁决说「可在窗口内补授权」时，资格判定必须同时成立（两处口径不得漂移）",
            verdict.authorizeCallerInWindow && eligible()
        )

        val rejected = PasskeyRpAuthorization.evaluate(
            requestRpId = "",
            credentialRpId = "example.com",
            browserFlow = false,
            callerBinding = CredentialManagerPackageBindingGate.Decision.REJECTED
        )
        assertFalse(
            "裁决说「不得补授权」时，资格判定必须同步拒绝",
            rejected.authorizeCallerInWindow || eligible(hasAnyBinding = true)
        )
    }
}
