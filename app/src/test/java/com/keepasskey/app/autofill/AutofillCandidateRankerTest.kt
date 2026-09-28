package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [AutofillCandidateRanker] 单元测试（ISSUE-P3-39）。
 *
 * 重点覆盖两类断言：
 * 1. **排序**：精确域名 > 父域、收藏同分靠前、上次填充置顶、上限截断；
 * 2. **安全不放松**：相似恶意域名 / 包名前缀一律不得匹配（匹配条件与既有严格实现一致）。
 */
class AutofillCandidateRankerTest {

    private val baseTime: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(
        hexId: String,
        url: String,
        favorite: Boolean = false,
        modifiedAt: Instant = baseTime,
        title: String = ""
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid.fromHexString(hexId),
        fields = mapOf(
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false)
        ),
        times = KdbxTimes(lastModificationTime = modifiedAt),
        customData = if (favorite) {
            mapOf(RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY to "true")
        } else {
            emptyMap()
        }
    )

    private fun hexIdOf(n: Int): String = n.toString(16).padStart(32, '0')

    @Test
    fun `精确域名优先于父域`() {
        val parent = entry(hexIdOf(1), "https://github.com")
        val exact = entry(hexIdOf(2), "https://login.github.com")

        val ranked = AutofillCandidateRanker.rank(listOf(parent, exact), "", "login.github.com", packageDimensionAuthorized = false)

        assertEquals(2, ranked.size)
        assertEquals(exact.id.toHexString(), ranked.first().entry.id.toHexString())
        assertTrue(AutofillCandidateRanker.MatchReason.EXACT_DOMAIN in ranked.first().reasons)
        assertTrue(AutofillCandidateRanker.MatchReason.PARENT_DOMAIN in ranked[1].reasons)
    }

    @Test
    fun `相似恶意域名不得匹配`() {
        val evil = entry(hexIdOf(1), "https://evilgithub.com")
        assertTrue(AutofillCandidateRanker.rank(listOf(evil), "", "github.com", packageDimensionAuthorized = false).isEmpty())
    }

    @Test
    fun `包名精确匹配且不得前缀匹配`() {
        val ok = entry(hexIdOf(1), "android://com.example.app")
        val ranked = AutofillCandidateRanker.rank(listOf(ok), "com.example.app", null, packageDimensionAuthorized = true)

        assertEquals(1, ranked.size)
        assertTrue(AutofillCandidateRanker.MatchReason.EXACT_PACKAGE in ranked.first().reasons)

        val prefix = entry(hexIdOf(2), "android://com.example.app.evil")
        assertTrue(AutofillCandidateRanker.rank(listOf(prefix), "com.example.app", null, packageDimensionAuthorized = true).isEmpty())
    }

    @Test
    fun `域名形态包名不得按包名维度命中`() {
        // P2-40 回归锁：https://<host> 条目不得因调用包名与 host 同形而入选
        // （整改前 isPackageMatch 剥离任意 scheme → ("https://github.com", "github.com") == true）
        val webBound = entry(hexIdOf(1), "https://github.com")
        assertTrue(
            "Web 绑定条目不得被同形包名命中",
            AutofillCandidateRanker.rank(listOf(webBound), "github.com", null, packageDimensionAuthorized = true).isEmpty()
        )

        // 裸包名条目同样不再按包名命中（只认显式 android:// 绑定）
        val bare = entry(hexIdOf(2), "com.example.app")
        assertTrue(
            "裸包名条目不得按包名命中",
            AutofillCandidateRanker.rank(listOf(bare), "com.example.app", null, packageDimensionAuthorized = true).isEmpty()
        )

        // 同一批内：android:// 绑定条目仍正常入选，且仅它入选
        val bound = entry(hexIdOf(3), "android://com.example.app")
        val ranked = AutofillCandidateRanker.rank(listOf(webBound, bare, bound), "com.example.app", null, packageDimensionAuthorized = true)
        assertEquals(1, ranked.size)
        assertEquals(bound.id.toHexString(), ranked.first().entry.id.toHexString())
    }

    @Test
    fun `webDomain 为空时不做域名匹配`() {
        val e = entry(hexIdOf(1), "https://github.com")
        assertTrue(AutofillCandidateRanker.rank(listOf(e), "com.other", null, packageDimensionAuthorized = false).isEmpty())
    }

    @Test
    fun `上限截断`() {
        val entries = (1..5).map { entry(hexIdOf(it), "https://github.com") }
        val ranked = AutofillCandidateRanker.rank(entries, "", "github.com", packageDimensionAuthorized = false, limit = 2)
        assertEquals(2, ranked.size)
    }

    @Test
    fun `上次填充条目置顶`() {
        val a = entry(hexIdOf(1), "https://github.com")
        val b = entry(hexIdOf(2), "https://github.com")

        val ranked = AutofillCandidateRanker.rank(
            listOf(a, b),
            "",
            "github.com",
            packageDimensionAuthorized = false,
            lastFilledEntryId = b.id.toHexString()
        )

        assertEquals(b.id.toHexString(), ranked.first().entry.id.toHexString())
    }

    @Test
    fun `收藏条目同分时靠前`() {
        val plain = entry(hexIdOf(1), "https://github.com")
        val favorite = entry(hexIdOf(2), "https://github.com", favorite = true)

        val ranked = AutofillCandidateRanker.rank(listOf(plain, favorite), "", "github.com", packageDimensionAuthorized = false)

        assertEquals(favorite.id.toHexString(), ranked.first().entry.id.toHexString())
    }

    // ===== ISSUE-P2-46：`android://` 维度必须经「包名 + 签名」首次绑定 =====

    @Test
    fun `包名维度未授权时 android 绑定条目不命中`() {
        val bound = entry(hexIdOf(1), "android://com.example.app")

        val ranked = AutofillCandidateRanker.rank(
            listOf(bound),
            "com.example.app",
            null,
            packageDimensionAuthorized = false
        )

        assertTrue(
            "未完成包名 + 签名首次绑定时必须「不命中」（不得降级为弱候选：" +
                "那仍会把凭据交给以同 applicationId 侧载的应用）",
            ranked.isEmpty()
        )
    }

    @Test
    fun `未授权时仅失去包名维度，域名维度仍可入选`() {
        val packageBoundOnly = entry(hexIdOf(1), "android://com.example.app")
        val webBound = entry(hexIdOf(2), "https://github.com")

        val ranked = AutofillCandidateRanker.rank(
            listOf(packageBoundOnly, webBound),
            "com.example.app",
            "github.com",
            packageDimensionAuthorized = false
        )

        assertEquals(1, ranked.size)
        assertEquals(webBound.id.toHexString(), ranked.first().entry.id.toHexString())
        assertFalse(
            "未授权时不得出现 EXACT_PACKAGE 匹配原因",
            AutofillCandidateRanker.MatchReason.EXACT_PACKAGE in ranked.first().reasons
        )
    }

    @Test
    fun `包名维度未授权不改变域名维度的排序与截断`() {
        val parent = entry(hexIdOf(1), "https://github.com")
        val exact = entry(hexIdOf(2), "https://login.github.com")

        val ranked = AutofillCandidateRanker.rank(
            listOf(parent, exact),
            "com.example.app",
            "login.github.com",
            packageDimensionAuthorized = false
        )

        assertEquals(2, ranked.size)
        assertEquals(exact.id.toHexString(), ranked.first().entry.id.toHexString())
    }

    // ===== ISSUE-P3-372 AC⑤：去 www 归一 + 子域后缀（同站加性档） =====

    @Test
    fun `条目带 www 前缀归一后按精确域名入选`() {
        val wwwEntry = entry(hexIdOf(1), "https://www.example.com")

        val ranked = AutofillCandidateRanker.rank(
            listOf(wwwEntry), "", "example.com", packageDimensionAuthorized = false
        )

        assertEquals(1, ranked.size)
        assertTrue(
            "去 www 归一相等应计 EXACT_DOMAIN",
            AutofillCandidateRanker.MatchReason.EXACT_DOMAIN in ranked.first().reasons
        )
        assertEquals(140, ranked.first().score)
    }

    @Test
    fun `条目为目标域子域时按子域后缀档入选`() {
        val sub = entry(hexIdOf(1), "https://accounts.example.com")

        val ranked = AutofillCandidateRanker.rank(
            listOf(sub), "", "example.com", packageDimensionAuthorized = false
        )

        assertEquals(1, ranked.size)
        assertTrue(
            "条目在目标域之下应计 SUBDOMAIN_OF_ORIGIN",
            AutofillCandidateRanker.MatchReason.SUBDOMAIN_OF_ORIGIN in ranked.first().reasons
        )
        // ISSUE-P3-377 AC③：子域档分值与 Monica「子域 115」同值对齐（原 110）
        assertEquals(115, ranked.first().score)
    }

    @Test
    fun `子域后缀档保持严格点号边界`() {
        // 无点号边界的相似域名不得经子域档入选
        val glued = entry(hexIdOf(1), "https://evilgithub.com")
        assertTrue(
            AutofillCandidateRanker.rank(listOf(glued), "", "github.com", packageDimensionAuthorized = false)
                .isEmpty()
        )
        // 后缀堆叠（条目主机名尾部不是 .github.com 而是 .github.com.evil.com）同样不入选
        val stacked = entry(hexIdOf(2), "https://github.com.evil.com")
        assertTrue(
            AutofillCandidateRanker.rank(listOf(stacked), "", "github.com", packageDimensionAuthorized = false)
                .isEmpty()
        )
    }

    // ===== ISSUE-P3-377 AC②：基域档（同 eTLD+1 兄弟子域，Monica `基域 100` 同值） =====

    @Test
    fun `兄弟子域同可注册域按基域档入选`() {
        val sibling = entry(hexIdOf(1), "https://accounts.example.com")

        val ranked = AutofillCandidateRanker.rank(
            listOf(sibling), "", "shop.example.com", packageDimensionAuthorized = false
        )

        assertEquals(1, ranked.size)
        assertTrue(
            "同 eTLD+1 兄弟子域应计 SAME_BASE_DOMAIN",
            AutofillCandidateRanker.MatchReason.SAME_BASE_DOMAIN in ranked.first().reasons
        )
        assertEquals(
            "基域档与 Monica `基域 100` 同值",
            100,
            ranked.first().score
        )
    }

    @Test
    fun `基域档经 PSL 计算——私有段兄弟与异域不得命中`() {
        // 私有段：foo.github.io 与 bar.github.io 的可注册域不同（github.io 是公共后缀）
        val privateSibling = entry(hexIdOf(1), "https://foo.github.io")
        assertTrue(
            "私有段兄弟域不得按基域档命中",
            AutofillCandidateRanker.rank(
                listOf(privateSibling), "", "bar.github.io", packageDimensionAuthorized = false
            ).isEmpty()
        )
        // 异域：基域不同恒不命中（与既有 evilgithub 负例互补——这条专打 eTLD+1 维度）
        val otherBase = entry(hexIdOf(2), "https://accounts.example.org")
        assertTrue(
            AutofillCandidateRanker.rank(
                listOf(otherBase), "", "shop.example.com", packageDimensionAuthorized = false
            ).isEmpty()
        )
    }

    // ===== ISSUE-P3-372 AC④：应用名相似加成（仅排序、不改入选） =====

    @Test
    fun `条目标题与调用方应用名相似时获得加成`() {
        val plain = entry(hexIdOf(1), "https://example.com", title = "Other")
        val similar = entry(hexIdOf(2), "https://example.com", title = "Example App")

        val ranked = AutofillCandidateRanker.rank(
            listOf(plain, similar),
            "com.example.app",
            "example.com",
            packageDimensionAuthorized = false,
            callingAppLabel = "example app"
        )

        assertEquals(2, ranked.size)
        assertTrue(
            "相似标题应计 APP_TITLE_MATCH",
            AutofillCandidateRanker.MatchReason.APP_TITLE_MATCH in ranked.first().reasons
        )
        assertEquals(similar.id.toHexString(), ranked.first().entry.id.toHexString())
        assertEquals(140 + 95, ranked.first().score)
    }

    @Test
    fun `应用名加成不得让未通过严格匹配的条目入选`() {
        // 标题与应用名高度相似，但 url 与目标域无关 ⇒ 仍必须落选
        val unrelated = entry(hexIdOf(1), "https://other.example.org", title = "Example App")

        val ranked = AutofillCandidateRanker.rank(
            listOf(unrelated),
            "com.example.app",
            "example.com",
            packageDimensionAuthorized = false,
            callingAppLabel = "Example App"
        )

        assertTrue(
            "纯标题相似绝不构成入选条件（打分只排序、不放宽匹配）",
            ranked.isEmpty()
        )
    }

    @Test
    fun `过短应用名与空白标签不加成`() {
        assertFalse(AutofillCandidateRanker.titleMatchesAppLabel("AB", "a"))
        assertFalse(AutofillCandidateRanker.titleMatchesAppLabel("AB", ""))
        assertFalse(AutofillCandidateRanker.titleMatchesAppLabel("", "ab"))
        assertFalse(AutofillCandidateRanker.titleMatchesAppLabel("GitHub", "gitlab"))
        assertTrue(AutofillCandidateRanker.titleMatchesAppLabel("  My  App ", "myapp"))
    }

    // ===== ISSUE-P3-373 AC①：Wi-Fi 设置上下文加成（只改排序不改准入） =====

    @Test
    fun `Wi-Fi 设置上下文对带信号的已入选条目给加成`() {
        val wifi = entry(hexIdOf(1), "https://router.example.com", title = "家中 WiFi")
        val plain = entry(hexIdOf(2), "https://router.example.com", title = "Router Admin")

        val ranked = AutofillCandidateRanker.rank(
            listOf(plain, wifi),
            "com.android.settings",
            "router.example.com",
            packageDimensionAuthorized = false,
            wifiContext = true
        )

        assertEquals(2, ranked.size)
        assertTrue(
            "带信号条目应计 WIFI_CONTEXT_MATCH",
            AutofillCandidateRanker.MatchReason.WIFI_CONTEXT_MATCH in ranked.first().reasons
        )
        assertEquals(wifi.id.toHexString(), ranked.first().entry.id.toHexString())
        assertEquals(140 + 70, ranked.first().score)
    }

    @Test
    fun `非 Wi-Fi 上下文或无信号时零加成`() {
        val wifi = entry(hexIdOf(1), "https://router.example.com", title = "家中 WiFi")
        val plain = entry(hexIdOf(2), "https://router.example.com", title = "Router Admin")

        // 清单外包名：即便条目带信号也不加成
        val offContext = AutofillCandidateRanker.rank(
            listOf(wifi), "com.example.browser", "router.example.com",
            packageDimensionAuthorized = false, wifiContext = false
        )
        assertEquals(140, offContext.first().score)
        assertFalse(
            AutofillCandidateRanker.MatchReason.WIFI_CONTEXT_MATCH in offContext.first().reasons
        )

        // 上下文命中但条目无信号：同样不加成
        val noSignal = AutofillCandidateRanker.rank(
            listOf(plain), "com.android.settings", "router.example.com",
            packageDimensionAuthorized = false, wifiContext = true
        )
        assertEquals(140, noSignal.first().score)
        assertFalse(
            AutofillCandidateRanker.MatchReason.WIFI_CONTEXT_MATCH in noSignal.first().reasons
        )
    }

    @Test
    fun `Wi-Fi 加成不得让未通过严格匹配的条目入选`() {
        // 标题带强信号但 url 与目标域无关 ⇒ 仍必须落选（加成只排序不改准入）
        val unrelated = entry(hexIdOf(1), "https://other.example.org", title = "家中 WiFi")

        val ranked = AutofillCandidateRanker.rank(
            listOf(unrelated),
            "com.android.settings",
            "router.example.com",
            packageDimensionAuthorized = false,
            wifiContext = true
        )

        assertTrue("纯信号词不构成入选条件", ranked.isEmpty())
    }
}
