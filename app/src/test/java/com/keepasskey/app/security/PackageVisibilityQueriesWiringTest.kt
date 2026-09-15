package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-74（审计 E6）回归：**最小包可见性声明**。
 *
 * 缺陷形态：清单未声明 `<queries>`（核实：0 命中），而
 * `AutofillOriginResolver.callingAppCertSha256Hex` 用 `getPackageInfo(..., GET_SIGNING_CERTIFICATES)`
 * 读取**任意调用方**的签名证书 → Android 11+ 包可见性下抛 `NameNotFoundException` → 指纹恒 null
 * → `BrowserSigningFingerprints.isTrusted`（要求非空指纹）与 DAL 校验**双双恒 false** → web 域候选
 * 整体失效（fail-closed，无泄露但功能不可用；minSdk 36 ⇒ **全部支持设备**均受影响）。
 *
 * 本用例锁三件事（静态清单断言，对齐既有 `*WiringTest` 先例）：
 * 1. `<queries>` 存在且含官方推荐的 `https` VIEW intent 声明（使浏览器可见）；
 * 2. 白名单 `BrowserSigningFingerprints.TRUSTED` 的**每个包名**都在清单里显式声明
 *    ——直接以生产常量为准检索，杜绝「白名单加了浏览器、清单忘了同步」的漂移；
 * 3. **不得**声明 `QUERY_ALL_PACKAGES`（最小必要原则；该权限在 Google Play 需审核批准）。
 */
class PackageVisibilityQueriesWiringTest {

    private val manifest: String by lazy {
        val file = File(repositoryRoot, MANIFEST_PATH)
        assertTrue("清单文件不存在：$MANIFEST_PATH", file.isFile)
        file.readText()
    }

    /**
     * 剔除 XML 注释后的清单正文。
     *
     * 必要性：本项的整改说明本身就写在清单注释里（含 `<queries>` / `QUERY_ALL_PACKAGES` 等字样），
     * 若直接对全文做包含判断，注释会把「未声明」误判成「已声明」。
     */
    private val declarations: String by lazy {
        manifest.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    /** `<queries>…</queries>` 段（缺失返回 null） */
    private val queriesBlock: String?
        get() = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL)
            .find(declarations)?.groupValues?.get(1)

    @Test
    fun `清单必须声明 queries 且含 https VIEW intent`() {
        val block = queriesBlock
        assertTrue("清单缺少 <queries> 声明（ISSUE-P2-74：Android 11+ 包可见性会过滤 getPackageInfo）", block != null)
        val body = block!!
        assertTrue(
            "queries 必须声明 android.intent.action.VIEW（按 intent 签名使浏览器可见）",
            body.contains("android.intent.action.VIEW")
        )
        assertTrue(
            "queries 必须限定 BROWSABLE 类别",
            body.contains("android.intent.category.BROWSABLE")
        )
        assertTrue(
            "queries 必须限定 https scheme（本应用只信任 HTTPS 站点）",
            body.contains("android:scheme=\"https\"")
        )
    }

    @Test
    fun `受信浏览器白名单的每个包名都必须在清单中声明`() {
        val body = queriesBlock ?: error("清单缺少 <queries>")
        for (pkg in BrowserSigningFingerprints.TRUSTED.keys) {
            assertTrue(
                "受信浏览器 $pkg 未在 <queries> 中声明——白名单新增浏览器时必须同步清单" +
                    "（否则该浏览器的指纹读取恒失败，其 webDomain 归属永远无法通过）",
                body.contains("<package android:name=\"$pkg\" />")
            )
        }
    }

    @Test
    fun `不得声明 QUERY_ALL_PACKAGES（最小必要原则）`() {
        assertFalse(
            "禁止以 QUERY_ALL_PACKAGES 替代最小声明：该权限在 Google Play 需审核批准，" +
                "且与本应用「最小可见性」口径冲突",
            Regex("""<uses-permission\s+android:name="android\.permission\.QUERY_ALL_PACKAGES\s*""""")
                .containsMatchIn(declarations)
        )
    }

    private companion object {
        const val MANIFEST_PATH = "app/src/main/AndroidManifest.xml"
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
