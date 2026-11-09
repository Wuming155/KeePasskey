package com.keepasskey.app.passkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CM 通道调用方签名绑定在**真机**上的端到端回归（**ISSUE-P2-83** AC③）。
 *
 * ## 覆盖什么
 *
 * 走**真实** [CredentialManagerCallerTrustStore]（真实 `SharedPreferences` 落盘 + 真实键空间扫描）
 * 与**真实**候选层判据 [CredentialCandidateMatcher]，断言 `android://` 维度在
 * **密码填充**与**通行密钥断言**两条链路上的入选/不入选结果。
 *
 * ## 不覆盖什么（如实声明，2026-09-16）
 *
 * **不驱动系统 Credential Manager 的完整 UI 链路**：本机（Redmi 4X / Android 17）
 * `cmd credential` 返回 `No shell command implementation`，无 ADB 侧入口构造
 * `BeginGetCredentialRequest`；而从 `adb` 侧凑 `CallingAppInfo`（含 `SigningInfo`）不属于被测面。
 * 故 AC③ 的「真机实测」落在**绑定存储 + 候选层判据**这一层，系统 UI 链路仍须由侧载客户端
 * 人工复验（已登记为残余）。
 */
@RunWith(AndroidJUnit4::class)
class CredentialManagerBindingDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val store = CredentialManagerCallerTrustStore(context)

    private val pkg = "com.keepasskey.devicetest"

    private val digestA = "AA".repeat(32)
    private val digestB = "BB".repeat(32)

    @Before
    fun setUp() {
        store.untrust(pkg, digestA)
        store.untrust(pkg, digestB)
    }

    @After
    fun tearDown() {
        store.untrust(pkg, digestA)
        store.untrust(pkg, digestB)
    }

    private fun decide(callingPackage: String, digests: CallerCertDigests) =
        CredentialManagerPackageBindingGate.decide(
            callingPackage = callingPackage,
            certDigests = digests,
            isTrusted = store::isTrusted,
            hasAnyBinding = store::hasAnyBindingFor
        )

    private fun passwordEntry(url: String) = KdbxEntry(
        id = KdbxUuid.fromHexString("0".repeat(32)),
        fields = mapOf(
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString("secret", isProtected = true)
        )
    )

    private fun passkeyEntry(url: String, rpId: String) = KdbxEntry(
        id = KdbxUuid.fromHexString("1".padStart(32, '0')),
        fields = mapOf(KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)),
        customFields = listOf(
            KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString(rpId, isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("priv", isProtected = true))
        )
    )

    // ── AC②：真实存储下的两条负向判据 ────────────────────────────────

    @Test
    fun `真实存储下已绑定包名的异签名调用方在两条链路均不命中`() {
        assertTrue("前置：绑定必须真实落盘", store.trust(pkg, digestA))
        assertTrue("前置：绑定存在性必须可查", store.hasAnyBindingFor(pkg))

        assertEquals(
            "同包名不同签名（侧载顶替 / 换签名）⇒ 必须 REJECTED",
            CredentialManagerPackageBindingGate.Decision.REJECTED,
            decide(pkg, CallerCertDigests.ofSingle(digestB))
        )

        val passwordAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            pkg, CallerCertDigests.ofSingle(digestB), store::isTrusted, store::hasAnyBindingFor
        )
        val passkeyAllowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            pkg, CallerCertDigests.ofSingle(digestB), store::isTrusted, store::hasAnyBindingFor
        )

        assertFalse(
            "密码填充链路：包名维度不得入选",
            CredentialCandidateMatcher.matchesPassword(
                passwordEntry("android://$pkg"),
                targetDomain = "",
                callingPackage = pkg,
                packageDimensionAllowed = passwordAllowed
            )
        )
        assertFalse(
            "通行密钥断言链路：包名维度不得入选",
            CredentialCandidateMatcher.matchesPasskey(
                passkeyEntry("android://$pkg", rpId = pkg),
                browserFlow = false,
                targetRpId = "",
                callingPackage = pkg,
                packageDimensionAllowed = passkeyAllowed
            )
        )
    }

    @Test
    fun `真实存储下命中摘要时两条链路均放行`() {
        store.trust(pkg, digestA)

        assertEquals(
            CredentialManagerPackageBindingGate.Decision.AUTHORIZED,
            decide(pkg, CallerCertDigests.ofSingle(digestA))
        )

        val allowed = CredentialManagerPackageBindingGate.allowsPackageDimension(
            pkg, CallerCertDigests.ofSingle(digestA), store::isTrusted, store::hasAnyBindingFor
        )

        assertTrue(
            CredentialCandidateMatcher.matchesPassword(
                passwordEntry("android://$pkg"),
                targetDomain = "",
                callingPackage = pkg,
                packageDimensionAllowed = allowed
            )
        )
        assertTrue(
            CredentialCandidateMatcher.matchesPasskey(
                passkeyEntry("android://$pkg", rpId = pkg),
                browserFlow = false,
                targetRpId = "",
                callingPackage = pkg,
                packageDimensionAllowed = allowed
            )
        )
    }

    @Test
    fun `真实存储下摘要不可读且已绑定时不放行`() {
        store.trust(pkg, digestA)

        assertEquals(
            "无法校验时存在绑定 ⇒ fail-closed（不得退化为仅按包名）",
            CredentialManagerPackageBindingGate.Decision.REJECTED,
            decide(pkg, CallerCertDigests.EMPTY)
        )
    }

    @Test
    fun `真实存储下从未绑定的包名退回既有行为`() {
        assertEquals(
            "无参照时不得 fail-closed——CM 没有选择器中立面",
            CredentialManagerPackageBindingGate.Decision.UNBOUND,
            decide("com.keepasskey.neverbound", CallerCertDigests.ofSingle(digestA))
        )
        assertFalse("未绑定过的包名不得被误报为已有绑定", store.hasAnyBindingFor("com.keepasskey.neverbound"))
    }

    @Test
    fun `真实存储下绑定按包名隔离`() {
        store.trust(pkg, digestA)

        assertTrue(store.hasAnyBindingFor(pkg))
        assertFalse(store.hasAnyBindingFor("com.keepasskey.other"))
        assertFalse(
            "他包名的调用方不得因本包名已绑定而受影响",
            store.isTrusted("com.keepasskey.other", CallerCertDigests.ofSingle(digestA))
        )
    }

    @Test
    fun `撤销后立即不再放行`() {
        store.trust(pkg, digestA)
        assertTrue(store.untrust(pkg, digestA))

        assertEquals(
            "撤销后回到 UNBOUND（该包名不再有任何绑定）",
            CredentialManagerPackageBindingGate.Decision.UNBOUND,
            decide(pkg, CallerCertDigests.ofSingle(digestA))
        )
    }

    @Test
    fun `清理：测试不得在设备上留下绑定残留`() {
        store.trust(pkg, digestA)
        store.untrust(pkg, digestA)

        assertFalse(store.hasAnyBindingFor(pkg))
    }

    // ── ENG-20261028-06：调用方签名裁决走真实绑定存储 ──────────────────

    /** 真实存储下的签发裁决（与候选层分工见 [CredentialCandidateMatcher] KDoc） */
    private fun verdict(callingPackage: String, digests: CallerCertDigests, requestRpId: String) =
        PasskeyRpAuthorization.evaluate(
            requestRpId = requestRpId,
            credentialRpId = "example.com",
            browserFlow = false,
            callerBinding = CredentialManagerPackageBindingGate.decide(
                callingPackage = callingPackage,
                certDigests = digests,
                isTrusted = store::isTrusted,
                hasAnyBinding = store::hasAnyBindingFor
            )
        )

    @Test
    fun `真实存储下未登记签名的原生调用一律不得签发_显式与缺失RP同判`() {
        assertFalse("前置：该包名必须确实未绑定", store.hasAnyBindingFor("com.keepasskey.neverbound"))

        for (requestRp in listOf("", "example.com")) {
            val result = verdict("com.keepasskey.neverbound", CallerCertDigests.ofSingle(digestA), requestRp)
            assertEquals(
                "requestRp='$requestRp'：自报的 rpId 不构成授权参照，必须拒签",
                PasskeyRpVerdict.CALLER_UNBOUND,
                result
            )
            assertTrue("该组合可经窗口内登记签名补救", result.authorizeCallerInWindow)
        }
    }

    @Test
    fun `真实存储下已登记同一签名时可签发`() {
        assertTrue("前置：绑定必须真实落盘", store.trust(pkg, digestA))

        for (requestRp in listOf("", "example.com")) {
            assertEquals(
                "requestRp='$requestRp'：签名绑定命中即可签发",
                PasskeyRpVerdict.ALLOWED,
                verdict(pkg, CallerCertDigests.ofSingle(digestA), requestRp)
            )
        }
    }

    @Test
    fun `真实存储下已登记异签名时拒签且不得就地重绑`() {
        store.trust(pkg, digestA)

        for (requestRp in listOf("", "example.com")) {
            val result = verdict(pkg, CallerCertDigests.ofSingle(digestB), requestRp)
            assertEquals(
                "requestRp='$requestRp'：已登记而签名不符 ⇒ 拒绝",
                PasskeyRpVerdict.CALLER_BINDING_REJECTED,
                result
            )
            assertFalse(
                "已存在绑定记录而签名不符，不得就地重绑（等于让顶替者自证身份）",
                result.authorizeCallerInWindow
            )
        }
    }

    @Test
    fun `真实存储下请求自报其他RP一律拒绝`() {
        store.trust(pkg, digestA)

        assertEquals(
            PasskeyRpVerdict.RP_MISMATCH,
            verdict(pkg, CallerCertDigests.ofSingle(digestA), "other.example")
        )
        assertEquals(
            "未登记包名同样先被 RP 不匹配拦下",
            PasskeyRpVerdict.RP_MISMATCH,
            verdict("com.keepasskey.neverbound", CallerCertDigests.ofSingle(digestA), "other.example")
        )
    }
}
