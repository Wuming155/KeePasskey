package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxConstants
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
