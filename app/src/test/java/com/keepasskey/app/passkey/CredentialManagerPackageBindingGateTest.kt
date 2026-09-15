package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判定枚举的短别名（仅本文件可见，避免逐处写全限定名） */
private typealias Decision = CredentialManagerPackageBindingGate.Decision

/**
 * CM 通道 `android://` 包名维度签名绑定门控单元测试（**ISSUE-P2-83**）。
 *
 * 核心是**三值**判定：必须把「已绑定但签名不匹配」（=AC② 的越权面，一律拒绝）与
 * 「从未绑定」（无可参照，退回包名维度既有行为）分开——二者在二元判据下都是「false」，
 * 混同会导致存量条目在 CM 通道无路可走（详见 [CredentialManagerPackageBindingGate] KDoc）。
 */
class CredentialManagerPackageBindingGateTest {

    private val pkg = "com.example.app"
    private val digestA = "AA".repeat(32)
    private val digestB = "BB".repeat(32)

    /** 已绑定 digestA 的调用方视图 */
    private fun decide(
        callingPackage: String = pkg,
        certDigests: CallerCertDigests = CallerCertDigests.ofSingle(digestA),
        trustedDigests: Set<String> = emptySet(),
        boundPackages: Set<String> = emptySet()
    ): CredentialManagerPackageBindingGate.Decision =
        CredentialManagerPackageBindingGate.decide(
            callingPackage = callingPackage,
            certDigests = certDigests,
            isTrusted = { _, digests -> digests.anyMatch { it in trustedDigests } },
            hasAnyBinding = { it in boundPackages }
        )

    // ── AC② 的两条负向判据 ───────────────────────────────────────────

    @Test
    fun `已绑定但本次摘要不匹配时拒绝`() {
        // 同包名不同签名：绑定的是 digestA，本次调用方是 digestB（侧载顶替 / 换签名）
        val decision = decide(
            certDigests = CallerCertDigests.ofSingle(digestB),
            trustedDigests = setOf(digestA),
            boundPackages = setOf(pkg)
        )

        assertEquals(
            "已绑定该包名而摘要不匹配 ⇒ 必须不命中（ISSUE-P2-83 AC②）",
            CredentialManagerPackageBindingGate.Decision.REJECTED,
            decision
        )
        assertFalse(
            CredentialManagerPackageBindingGate.allowsPackageDimension(
                pkg, CallerCertDigests.ofSingle(digestB),
                { _, d -> d.anyMatch { it == digestA } }, { true }
            )
        )
    }

    @Test
    fun `已绑定且本次摘要命中时放行`() {
        val decision = decide(
            trustedDigests = setOf(digestA),
            boundPackages = setOf(pkg)
        )

        assertEquals(CredentialManagerPackageBindingGate.Decision.AUTHORIZED, decision)
    }

    @Test
    fun `多签名者任一命中即放行`() {
        val decision = decide(
            certDigests = CallerCertDigests.of(listOf(digestB, digestA)),
            trustedDigests = setOf(digestA),
            boundPackages = setOf(pkg)
        )

        assertEquals(
            "签名轮换期系统同时下发当前与历史签名者，任一命中即为同一应用",
            CredentialManagerPackageBindingGate.Decision.AUTHORIZED,
            decision
        )
    }

    // ── 从未绑定：与「已绑定不匹配」必须区分 ─────────────────────────

    @Test
    fun `从未绑定的包名退回既有行为而非拒绝`() {
        val decision = decide(boundPackages = emptySet())

        assertEquals(
            "无参照时不得 fail-closed——CM 没有选择器中立面，拒绝会让存量 android:// 条目无路可走",
            CredentialManagerPackageBindingGate.Decision.UNBOUND,
            decision
        )
        assertTrue(
            CredentialManagerPackageBindingGate.allowsPackageDimension(
                pkg, CallerCertDigests.ofSingle(digestA), { _, _ -> false }, { false }
            )
        )
    }

    @Test
    fun `仅绑定其他包名不影响本包名判定`() {
        val decision = decide(boundPackages = setOf("com.example.other"))

        assertEquals(CredentialManagerPackageBindingGate.Decision.UNBOUND, decision)
    }

    // ── fail-closed 与输入边界 ───────────────────────────────────────

    @Test
    fun `包名为空一律拒绝`() {
        assertEquals(CredentialManagerPackageBindingGate.Decision.REJECTED, decide(callingPackage = ""))
        assertEquals(CredentialManagerPackageBindingGate.Decision.REJECTED, decide(callingPackage = "   "))
    }

    @Test
    fun `摘要不可读且该包名已绑定时拒绝`() {
        val decision = decide(
            certDigests = CallerCertDigests.EMPTY,
            boundPackages = setOf(pkg)
        )

        assertEquals(
            "无法校验时若存在绑定 ⇒ fail-closed（不得退化为仅按包名）",
            CredentialManagerPackageBindingGate.Decision.REJECTED,
            decision
        )
    }

    @Test
    fun `摘要不可读且该包名从未绑定时退回既有行为`() {
        val decision = decide(certDigests = CallerCertDigests.EMPTY, boundPackages = emptySet())

        assertEquals(CredentialManagerPackageBindingGate.Decision.UNBOUND, decision)
    }

    @Test
    fun `判定与授权函数不得出现未被覆盖的分支组合`() {
        // 穷举 (摘要可读 × 存储中存在该摘要的授权记录 × 该包名已绑定) 的全部组合，锁定三值语义。
        // 注：摘要**不可读**时存储的授权判据必然为 false（`anyMatch` 在空集合上恒 false），
        // 故表中「不可读 + 授权记录在案」两行即「存储里有过记录、但本次读不到摘要」的真实形态。
        val cases = listOf(
            Case(digestsReadable = true, trustRecorded = true, bound = true, expected = Decision.AUTHORIZED),
            Case(digestsReadable = true, trustRecorded = true, bound = false, expected = Decision.AUTHORIZED),
            Case(digestsReadable = true, trustRecorded = false, bound = true, expected = Decision.REJECTED),
            Case(digestsReadable = true, trustRecorded = false, bound = false, expected = Decision.UNBOUND),
            Case(digestsReadable = false, trustRecorded = true, bound = true, expected = Decision.REJECTED),
            Case(digestsReadable = false, trustRecorded = true, bound = false, expected = Decision.UNBOUND),
            Case(digestsReadable = false, trustRecorded = false, bound = true, expected = Decision.REJECTED),
            Case(digestsReadable = false, trustRecorded = false, bound = false, expected = Decision.UNBOUND)
        )

        cases.forEach { case ->
            val decision = decide(
                certDigests = if (case.digestsReadable) {
                    CallerCertDigests.ofSingle(digestA)
                } else {
                    CallerCertDigests.EMPTY
                },
                trustedDigests = if (case.trustRecorded) setOf(digestA) else emptySet(),
                boundPackages = if (case.bound) setOf(pkg) else emptySet()
            )
            assertEquals(case.toString(), case.expected, decision)
        }
    }

    private data class Case(
        val digestsReadable: Boolean,
        val trustRecorded: Boolean,
        val bound: Boolean,
        val expected: Decision
    )
}
