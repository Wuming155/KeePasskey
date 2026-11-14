package com.keepasskey.app.autofill

import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [AutofillUnlockRouter] 纯函数判据穷举（PD-05 候选 C，ISSUE-P3-256 AC②）。
 *
 * 真值表逐维覆盖：唯一强匹配 + 已绑定 ⇒ CONFIRM；多候选 / 零候选 / 唯一但未绑定 /
 * 唯一但仅父域弱匹配 / 无目标框 ⇒ PICKER。
 *
 * 配对接线守卫 [AutofillUnlockRouteWiringTest]（断言解锁页真的经本判据路由）。
 */
class AutofillUnlockRouterTest {

    private val baseTime: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(hexId: String, url: String = "https://login.example.com"): KdbxEntry =
        KdbxEntry(
            id = KdbxUuid.fromHexString(hexId),
            fields = mapOf(KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)),
            times = KdbxTimes(lastModificationTime = baseTime)
        )

    private fun hexIdOf(n: Int): String = n.toString(16).padStart(32, '0')

    /** 强匹配候选（exact 语义）：精确域名 */
    private fun strongDomain(e: KdbxEntry): AutofillCandidateRanker.Ranked =
        AutofillCandidateRanker.Ranked(
            entry = e,
            score = 140,
            reasons = setOf(AutofillCandidateRanker.MatchReason.EXACT_DOMAIN)
        )

    /** 强匹配候选（exact 语义）：精确 android:// 包名 */
    private fun strongPackage(e: KdbxEntry): AutofillCandidateRanker.Ranked =
        AutofillCandidateRanker.Ranked(
            entry = e,
            score = 130,
            reasons = setOf(AutofillCandidateRanker.MatchReason.EXACT_PACKAGE)
        )

    /** 弱匹配候选：仅父域（既有打分 120，非 exact） */
    private fun parentOnly(e: KdbxEntry): AutofillCandidateRanker.Ranked =
        AutofillCandidateRanker.Ranked(
            entry = e,
            score = 120,
            reasons = setOf(AutofillCandidateRanker.MatchReason.PARENT_DOMAIN)
        )

    // ── CONFIRM 侧 ──

    private val bindingFingerprint = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"

    /** 已有网址 + `AndroidApp*` 自定义字段绑定的条目（URL 未被改写）。 */
    private fun fieldBoundEntry(): KdbxEntry {
        val (appKey, sigKey) = AndroidAppBindingFields.keysFor(0)
        return entry(hexIdOf(7), url = "https://example.com").copy(
            customFields = listOf(
                KdbxCustomField(appKey, ProtectedString("com.example.app", isProtected = false)),
                KdbxCustomField(
                    sigKey,
                    ProtectedString(
                        AndroidAppBindingFields.toColonHex(bindingFingerprint),
                        isProtected = true
                    )
                )
            )
        )
    }

    /**
     * 锁定态解锁路径的端到端行为契约：解锁页把本次摘要快照显式下传给 rank，
     * 字段绑定才产出唯一 EXACT_PACKAGE 强匹配并路由到确认页（不必手选）。
     * 漏传摘要会退化为零候选 ⇒ PICKER，下面用反例一并锁定。
     */
    @Test
    fun `字段绑定条目在传入签名摘要时经rank路由到CONFIRM`() {
        val ranked = AutofillCandidateRanker.rank(
            entries = listOf(fieldBoundEntry()),
            callingPackage = "com.example.app",
            webDomain = null,
            packageDimensionAuthorized = true,
            callingCertDigests = CallerCertDigests.ofSingle(bindingFingerprint)
        )

        assertEquals(1, ranked.size)
        assertTrue(
            AutofillCandidateRanker.MatchReason.EXACT_PACKAGE in ranked.single().reasons
        )
        val route = AutofillUnlockRouter.route(
            candidates = ranked,
            packageDimensionAuthorized = true,
            hasTargetField = true
        )
        assertTrue("应免选择器直达确认页：$route", route is AutofillUnlockRouter.Route.Confirm)
    }

    @Test
    fun `字段绑定条目漏传签名摘要时零候选并回落PICKER`() {
        val ranked = AutofillCandidateRanker.rank(
            entries = listOf(fieldBoundEntry()),
            callingPackage = "com.example.app",
            webDomain = null,
            packageDimensionAuthorized = true
        )

        assertTrue("漏传摘要不得命中字段绑定", ranked.isEmpty())
        assertTrue(
            AutofillUnlockRouter.route(ranked, packageDimensionAuthorized = true, hasTargetField = true)
                is AutofillUnlockRouter.Route.Picker
        )
    }

    @Test
    fun `唯一精确域名强匹配且已绑定且有目标框 → CONFIRM`() {
        val e = entry(hexIdOf(1))
        val route = AutofillUnlockRouter.route(
            candidates = listOf(strongDomain(e)),
            packageDimensionAuthorized = true,
            hasTargetField = true
        )
        assertTrue("应路由到确认页：$route", route is AutofillUnlockRouter.Route.Confirm)
        assertEquals(e.id.toHexString(), (route as AutofillUnlockRouter.Route.Confirm).entry.id.toHexString())
    }

    @Test
    fun `唯一精确包名强匹配且已绑定且有目标框 → CONFIRM`() {
        val e = entry(hexIdOf(2), url = "android://com.example.app")
        val route = AutofillUnlockRouter.route(
            candidates = listOf(strongPackage(e)),
            packageDimensionAuthorized = true,
            hasTargetField = true
        )
        assertTrue("应路由到确认页：$route", route is AutofillUnlockRouter.Route.Confirm)
        assertEquals(e.id.toHexString(), (route as AutofillUnlockRouter.Route.Confirm).entry.id.toHexString())
    }

    // ── PICKER 侧（穷举） ──

    @Test
    fun `多候选（两条均强匹配）→ PICKER`() {
        val route = AutofillUnlockRouter.route(
            candidates = listOf(strongDomain(entry(hexIdOf(1))), strongDomain(entry(hexIdOf(2)))),
            packageDimensionAuthorized = true,
            hasTargetField = true
        )
        assertEquals(AutofillUnlockRouter.Route.Picker, route)
    }

    @Test
    fun `零候选 → PICKER`() {
        val route = AutofillUnlockRouter.route(
            candidates = emptyList(),
            packageDimensionAuthorized = true,
            hasTargetField = true
        )
        assertEquals(AutofillUnlockRouter.Route.Picker, route)
    }

    @Test
    fun `唯一强匹配但调用方未绑定 → PICKER（ISSUE-P2-46 fail-closed）`() {
        val route = AutofillUnlockRouter.route(
            candidates = listOf(strongDomain(entry(hexIdOf(1)))),
            packageDimensionAuthorized = false,
            hasTargetField = true
        )
        assertEquals(
            "未绑定调用方必须继续走选择器（首次绑定的补救写入点在选择器路径），反向注入「未绑定也 CONFIRM」时本用例必红",
            AutofillUnlockRouter.Route.Picker,
            route
        )
    }

    @Test
    fun `唯一但仅父域弱匹配 → PICKER`() {
        val route = AutofillUnlockRouter.route(
            candidates = listOf(parentOnly(entry(hexIdOf(1)))),
            packageDimensionAuthorized = true,
            hasTargetField = true
        )
        assertEquals(AutofillUnlockRouter.Route.Picker, route)
    }

    @Test
    fun `唯一强匹配且已绑定但无目标框 → PICKER`() {
        val route = AutofillUnlockRouter.route(
            candidates = listOf(strongDomain(entry(hexIdOf(1)))),
            packageDimensionAuthorized = true,
            hasTargetField = false
        )
        assertEquals(AutofillUnlockRouter.Route.Picker, route)
    }
}
