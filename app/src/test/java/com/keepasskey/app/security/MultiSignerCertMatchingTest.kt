package com.keepasskey.app.security

import com.keepasskey.app.autofill.AutofillCallerTrustStore
import com.keepasskey.app.autofill.AutofillWebDomainPolicy
import com.keepasskey.app.autofill.WebDomainAttribution
import com.keepasskey.app.passkey.DalStatementMatcher
import com.keepasskey.app.passkey.DigitalAssetLinksVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-93（审计 F-19）回归：**签名轮换期的多签名者匹配**。
 *
 * 缺陷形态：各处只取 `signingInfo.apkContentsSigners?.firstOrNull()` 的**单个**摘要，
 * 而签名轮换期同一应用同时持有当前签名者（`apkContentsSigners`）与历史签名者
 * （`signingCertificateHistory`）→ 结果随系统返回顺序变化 → 白名单 / DAL / 信任记录
 * 可能把合法调用方误判为未授权（方向 fail-closed，属可用性缺陷）。
 *
 * 本用例锁：① 摘要集合的归一化语义；② 三处放行判定「**任一**摘要命中即通过」；
 * ③ 两处生产者必须遍历 `apkContentsSigners` + `signingCertificateHistory`（静态守卫，
 * 防退回 `firstOrNull()`）；④ 既有降级分支（摘要全不可读时仅按包名）保持不变。
 */
class MultiSignerCertMatchingTest {

    private val currentDigest = "AA".repeat(32)
    private val rotatedDigest = "BB".repeat(32)

    // ---------------- ① 摘要集合 ----------------

    @Test
    fun `摘要集合归一化为大写去重且保序`() {
        val digests = CallerCertDigests.of(listOf("  aa11  ", "Aa11", null, "", "bb22", "  "))

        assertEquals(listOf("AA11", "BB22"), digests.values)
        assertEquals("AA11", digests.primary)
        assertFalse(digests.isEmpty)
    }

    @Test
    fun `空集合无主摘要且不匹配任何谓词`() {
        val empty = CallerCertDigests.of(listOf(null, "   ", ""))
        assertTrue(empty.isEmpty)
        assertNull(empty.primary)
        assertFalse(empty.anyMatch { true })
        assertTrue(CallerCertDigests.EMPTY.isEmpty)
    }

    // ---------------- ② 三处放行判定 ----------------

    @Test
    fun `受信浏览器白名单按任一摘要命中即通过`() {
        val chrome = "com.android.chrome"
        val known = BrowserSigningFingerprints.TRUSTED.getValue(chrome).first()

        // 签名轮换：当前签名者不在白名单，但历史签名者在 → 仍应通过
        val rotated = CallerCertDigests.of(listOf(currentDigest, known))
        assertTrue(
            "轮换期历史签名者命中白名单即应通过（此前只看首个摘要会误判未授权）",
            BrowserSigningFingerprints.isTrusted(chrome, rotated)
        )
        // 两者都不在白名单 → 拒绝
        assertFalse(
            BrowserSigningFingerprints.isTrusted(
                chrome,
                CallerCertDigests.of(listOf(currentDigest, rotatedDigest))
            )
        )
        // 未取证包名 / 空集合 → 拒绝（fail-closed）
        assertFalse(BrowserSigningFingerprints.isTrusted("com.example.evil", rotated))
        assertFalse(BrowserSigningFingerprints.isTrusted(chrome, CallerCertDigests.EMPTY))
        // 单摘要入口语义等价
        assertTrue(BrowserSigningFingerprints.isTrusted(chrome, known.lowercase()))
    }

    @Test
    fun `webDomain 归属裁决按任一摘要命中浏览器分支`() {
        val chrome = "com.android.chrome"
        val known = BrowserSigningFingerprints.TRUSTED.getValue(chrome).first()
        val digests = CallerCertDigests.of(listOf(currentDigest, known))

        assertEquals(
            WebDomainAttribution.BROWSER_DELEGATED,
            AutofillWebDomainPolicy.attribute(chrome, "https://github.com", digests, dalVerified = false)
        )
        // 无摘要可读且 DAL 未验证 → 拒绝
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute(chrome, "https://github.com", CallerCertDigests.EMPTY, false)
        )
        // 非法域即使摘要命中亦拒绝（域形态校验先行）
        assertEquals(
            WebDomainAttribution.REJECTED,
            AutofillWebDomainPolicy.attribute(chrome, "single", digests, dalVerified = true)
        )
    }

    @Test
    fun `DAL 声明匹配按任一摘要命中即通过`() {
        val pkg = "com.example.app"
        val json = dalJson(pkg, listOf(currentDigest))

        assertTrue(
            "调用方任一摘要命中声明指纹即应通过",
            DalStatementMatcher.match(json, pkg, CallerCertDigests.of(listOf(rotatedDigest, currentDigest))) ==
                DigitalAssetLinksVerifier.DalResult.VERIFIED
        )
        assertTrue(
            "单摘要入口语义等价（含冒号 / 小写形式归一化）",
            DalStatementMatcher.match(json, pkg, currentDigest.lowercase().chunked(2).joinToString(":")) ==
                DigitalAssetLinksVerifier.DalResult.VERIFIED
        )
        assertFalse(
            "无任何摘要命中 → NOT_VERIFIED",
            DalStatementMatcher.match(json, pkg, CallerCertDigests.of(listOf(rotatedDigest))) ==
                DigitalAssetLinksVerifier.DalResult.VERIFIED
        )
        assertFalse(
            "空集合 → NOT_VERIFIED（fail-closed）",
            DalStatementMatcher.match(json, pkg, CallerCertDigests.EMPTY) ==
                DigitalAssetLinksVerifier.DalResult.VERIFIED
        )
    }

    @Test
    fun `信任记录按任一摘要命中即视为已授权`() {
        val store = AutofillCallerTrustStore(null)
        val pkg = "com.example.app"

        // 用户在「当前签名者」下显式授权（写入主摘要）
        assertTrue(store.trust(pkg, currentDigest))
        assertTrue(store.isTrusted(pkg, CallerCertDigests.of(listOf(currentDigest))))

        // 签名轮换：调用方此时同时持有当前（新）与历史（旧）签名者 → 仍视为已授权
        assertTrue(
            "轮换期历史签名者命中既有信任记录即应视为已授权（否则反复要求确认）",
            store.isTrusted(pkg, CallerCertDigests.of(listOf(rotatedDigest, currentDigest)))
        )
        // 完全无关的签名者（重打包 / 冒名）→ 未授权
        assertFalse(store.isTrusted(pkg, CallerCertDigests.of(listOf(rotatedDigest))))
        assertFalse(store.isTrusted(pkg, CallerCertDigests.of(listOf("CC".repeat(32)))))
    }

    @Test
    fun `摘要全不可读时保留仅按包名的降级信任记录`() {
        val store = AutofillCallerTrustStore(null)
        val pkg = "com.example.app"

        // ISSUE-P1-24 既有取舍：证书不可读时按「仅包名」记录
        assertTrue(store.trust(pkg, null))
        assertTrue(
            "空集合应回退到降级键（与整改前的 isTrusted(pkg, null) 判定面一致）",
            store.isTrusted(pkg, CallerCertDigests.EMPTY)
        )
        assertFalse(
            "摘要可读时不得回退到降级键（否则同包名换签名会继承信任）",
            store.isTrusted(pkg, CallerCertDigests.of(listOf(currentDigest)))
        )
    }

    // ---------------- ③ 生产者取证口径（静态守卫） ----------------

    @Test
    fun `两处生产者必须遍历当前与历史签名者而非只取首个`() {
        for (path in listOf(AUTOFILL_RESOLVER_PATH, CALLING_ORIGIN_RESOLVER_PATH)) {
            // 剔除注释后再断言：整改说明本身会引用缺陷写法，否则注释会造成假失败
            val code = stripComments(readSource(path))
            assertTrue(
                "$path 必须遍历 signingCertificateHistory（签名轮换期历史签名者）",
                code.contains("signingCertificateHistory")
            )
            assertTrue("$path 必须遍历 apkContentsSigners", code.contains("apkContentsSigners"))
            assertFalse(
                "$path 不得再对签名者数组取 firstOrNull() 后单独使用（ISSUE-P3-93 的缺陷形态）",
                Regex("""apkContentsSigners\??\.firstOrNull\(\)""").containsMatchIn(code)
            )
        }
        // apk-key-hash origin 结构上只能返回单个字符串，故必须同时提供多签名者 origin 集合访问器，
        // 使需要按 origin 匹配的路径可遍历全部签名者
        assertTrue(
            "CallingOriginResolver 必须提供多签名者 origin 集合访问器（apkKeyHashOrigins）",
            stripComments(readSource(CALLING_ORIGIN_RESOLVER_PATH)).contains("fun apkKeyHashOrigins(")
        )
    }

    /** 剔除块注释与行注释（静态断言须只看真实声明，不看整改说明） */
    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    // ---------------- 辅助 ----------------

    private fun dalJson(pkg: String, fingerprints: List<String>): String =
        """[{"relation":["${DalStatementMatcher.RELATION_GET_LOGIN_CREDS}"],"target":{""" +
            """"namespace":"android_app","package_name":"$pkg","sha256_cert_fingerprints":""" +
            fingerprints.joinToString(",", "[", "]") { "\"$it\"" } + "}}]"

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val AUTOFILL_RESOLVER_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillOriginResolver.kt"
        const val CALLING_ORIGIN_RESOLVER_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/CallingOriginResolver.kt"
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
