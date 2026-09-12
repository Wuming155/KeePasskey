package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.RealVaultRepository
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
        modifiedAt: Instant = baseTime
    ): KdbxEntry = KdbxEntry(
        id = KdbxUuid.fromHexString(hexId),
        fields = mapOf(KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)),
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

        val ranked = AutofillCandidateRanker.rank(listOf(parent, exact), "", "login.github.com")

        assertEquals(2, ranked.size)
        assertEquals(exact.id.toHexString(), ranked.first().entry.id.toHexString())
        assertTrue(AutofillCandidateRanker.MatchReason.EXACT_DOMAIN in ranked.first().reasons)
        assertTrue(AutofillCandidateRanker.MatchReason.PARENT_DOMAIN in ranked[1].reasons)
    }

    @Test
    fun `相似恶意域名不得匹配`() {
        val evil = entry(hexIdOf(1), "https://evilgithub.com")
        assertTrue(AutofillCandidateRanker.rank(listOf(evil), "", "github.com").isEmpty())
    }

    @Test
    fun `包名精确匹配且不得前缀匹配`() {
        val ok = entry(hexIdOf(1), "android://com.example.app")
        val ranked = AutofillCandidateRanker.rank(listOf(ok), "com.example.app", null)

        assertEquals(1, ranked.size)
        assertTrue(AutofillCandidateRanker.MatchReason.EXACT_PACKAGE in ranked.first().reasons)

        val prefix = entry(hexIdOf(2), "android://com.example.app.evil")
        assertTrue(AutofillCandidateRanker.rank(listOf(prefix), "com.example.app", null).isEmpty())
    }

    @Test
    fun `域名形态包名不得按包名维度命中`() {
        // P2-40 回归锁：https://<host> 条目不得因调用包名与 host 同形而入选
        // （整改前 isPackageMatch 剥离任意 scheme → ("https://github.com", "github.com") == true）
        val webBound = entry(hexIdOf(1), "https://github.com")
        assertTrue(
            "Web 绑定条目不得被同形包名命中",
            AutofillCandidateRanker.rank(listOf(webBound), "github.com", null).isEmpty()
        )

        // 裸包名条目同样不再按包名命中（只认显式 android:// 绑定）
        val bare = entry(hexIdOf(2), "com.example.app")
        assertTrue(
            "裸包名条目不得按包名命中",
            AutofillCandidateRanker.rank(listOf(bare), "com.example.app", null).isEmpty()
        )

        // 同一批内：android:// 绑定条目仍正常入选，且仅它入选
        val bound = entry(hexIdOf(3), "android://com.example.app")
        val ranked = AutofillCandidateRanker.rank(listOf(webBound, bare, bound), "com.example.app", null)
        assertEquals(1, ranked.size)
        assertEquals(bound.id.toHexString(), ranked.first().entry.id.toHexString())
    }

    @Test
    fun `webDomain 为空时不做域名匹配`() {
        val e = entry(hexIdOf(1), "https://github.com")
        assertTrue(AutofillCandidateRanker.rank(listOf(e), "com.other", null).isEmpty())
    }

    @Test
    fun `上限截断`() {
        val entries = (1..5).map { entry(hexIdOf(it), "https://github.com") }
        val ranked = AutofillCandidateRanker.rank(entries, "", "github.com", limit = 2)
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
            lastFilledEntryId = b.id.toHexString()
        )

        assertEquals(b.id.toHexString(), ranked.first().entry.id.toHexString())
    }

    @Test
    fun `收藏条目同分时靠前`() {
        val plain = entry(hexIdOf(1), "https://github.com")
        val favorite = entry(hexIdOf(2), "https://github.com", favorite = true)

        val ranked = AutofillCandidateRanker.rank(listOf(plain, favorite), "", "github.com")

        assertEquals(favorite.id.toHexString(), ranked.first().entry.id.toHexString())
    }
}
