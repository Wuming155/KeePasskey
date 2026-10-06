package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 依赖解析**可重现性**守卫（`ISSUE-P2-231` 重开决策的替代缓解口径之一）。
 *
 * ## 为什么需要它
 *
 * `ISSUE-P2-231` 的处置结论为「**本轮不引入**依赖完整性锁定（既有的
 * `verification-metadata.xml` 与 dependency locking 两条路线均因实施条件不成立而搁置，
 * 详见 [`产品裁决登记.md`](../../../../../docs/architecture/产品裁决登记.md) `PD-14`）」，
 * 并以一组**可执行的替代缓解**补偿。本用例是其中「**构建可重现性**」那一环的机检出口。
 *
 * 上游投毒链里最容易被忽略的一段不是「校验」，而是「**发现**」：
 * 动态版本（`1.+` / `latest.release` / 版本区间）与快照版本会让**上游发布即被自动采纳**，
 * 且该变化**不出现在本仓的任何 diff 里**——代码评审、`dependencyCheck`（已知 CVE 扫描，
 * 与投毒正交）都不会看到它。把版本目录钉成「只允许精确版本」，是当下零成本即可达成的一环。
 *
 * ## 覆盖边界（如实声明，不得读作全面保证）
 *
 * 1. 本用例只扫**版本目录**（`gradle/libs.versions.toml`）——它已是本仓版本声明的权威集中源，
 *    `[libraries]` / `[plugins]` 段内的内联坐标同样在扫描面内（正则覆盖任意 `key = "值"`）；
 * 2. `settings.gradle.kts` 中插件 DSL 的内联 `version "…"`（如 `foojay-resolver`）**不在本面内**，
 *    属已知覆盖限界；
 * 3. 本用例**不**提供任何内容完整性保证——它拦不住「同版本号内容被替换」，那一面正是
 *    `PD-14` 判为「本轮不引入」的 `verification-metadata.xml` 所覆盖的，须待 CI 干净环境可用后重开。
 */
class DependencyResolutionDeterminismTest {

    @Test
    fun `版本目录不得声明动态版本或快照版本`() {
        val source = readRepoFile(VERSION_CATALOG)
        val declarations = TOML_ASSIGNMENT
            .findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        // 防空扫：结构变更（如改为 `.toml` 内联表）会让上面的正则静默零命中，届时必须先修判据
        assertTrue(
            "[$VERSION_CATALOG] 未解析到任何 `key = \"value\"` 版本声明（共 ${declarations.size} 条，"
                + "结构是否已变更？）——本守卫已失去判别对象，不得据此认为检查通过",
            declarations.size >= MIN_DECLARATIONS
        )

        val offending = declarations.mapNotNull { (key, value) ->
            dynamicMarker(value)?.let { "$key = \"$value\"（$it）" }
        }
        assertTrue(
            "[$VERSION_CATALOG] 发现动态 / 快照 / 区间版本声明：$offending。"
                + "这类版本会使上游发布即被自动采纳、且变化不出现在本仓 diff 中，"
                + "与本仓「供应链可发现性」的替代缓解口径相冲突（PD-14）；"
                + "请改用精确版本，确需例外时先走一次产品裁决登记。",
            offending.isEmpty()
        )
    }

    @Test
    fun `动态版本判据本身具备判别力`() {
        // 已知坏样本反校（§157/§158 双口径纪律）：判据必须能抓出各类形态，
        // 否则本守卫会退化为恒真断言——「空扫自绿」是这类机检最常见的失效方式。
        assertTrue("通配尾部未命中", dynamicMarker("1.+") != null)
        assertTrue("裸通配未命中", dynamicMarker("+") != null)
        assertTrue("快照未命中", dynamicMarker("2.0-SNAPSHOT") != null)
        assertTrue("latest.release 未命中", dynamicMarker("latest.release") != null)
        assertTrue("latest.integration 未命中", dynamicMarker("latest.integration") != null)
        assertTrue("版本区间未命中", dynamicMarker("[1.0,2.0)") != null)
        assertTrue("开区间未命中", dynamicMarker("(1.0,)") != null)

        // 正样本不得误伤：alpha / beta 渠道与构建元数据都是**精确**版本
        assertTrue("alpha 渠道被误伤", dynamicMarker("1.5.0-alpha27") == null)
        assertTrue("构建元数据被误伤", dynamicMarker("1.0.0+build5") == null)
        assertTrue("普通精确版本被误伤", dynamicMarker("9.2.1") == null)
    }

    /**
     * material3 钉版约束的**真实执行点**（`ISSUE-P3-505`）。
     *
     * 背景：`gradle/libs.versions.toml` 顶部的管控注释写着「本值仅允许在 1.5.0-alphaN
     * 内部随安全修复上调，禁止跨 minor 跳跃」，但该约束**此前没有任何执行点**——
     * 双声明（`compose.material3` 走 BOM 托管 + `compose.material3.alpha` 显式覆盖）
     * 的钉版效力**寄生于 Gradle「最高版本胜出」的排序出价**：一旦 BOM 的 material3 映射
     * 越过钉版值，实际解析版本会被静默改写，而 `libs.versions.toml` 的钉版行本身纹丝不动。
     *
     * 本用例把该约束钉成三条可判断言（当前状态下均为绿，属**前瞻性守卫**）：
     * ① 钉版值必须停留在 `1.5.0-alphaN` 线内（跨 minor 跳跃即红）；
     * ② 显式覆盖声明必须在位（否则双声明消失、钉版随之失效）；
     * ③ BOM 实际映射的 material3 版本**不得高于**钉版值（fail-closed：定位不到 BOM POM
     *    即失败——`test` 必然已解析过依赖，POM 应当在 Gradle 缓存中）。
     */
    @Test
    fun `material3 钉版不得被 BOM 映射静默改写`() {
        val catalog = readRepoFile(VERSION_CATALOG)
        val pinned = requireTomlValue(catalog, "material3")
        val bomVersion = requireTomlValue(catalog, "composeBom")

        assertTrue(
            "钉版 material3 = \"$pinned\" 已越出 1.5.0-alphaN 线——版本目录约束为"
                + "「仅允许在 1.5.0-alphaN 内部上调，禁止跨 minor 跳跃」；若为有意升级，"
                + "须同步更新该约束注释与 `docs/records/退役依据承接-ISSUE-P3-09.md` 的退出条件判定",
            MATERIAL3_PINNED_LINE.matches(pinned)
        )

        val appBuild = readRepoFile(APP_BUILD_GRADLE)
        assertTrue(
            "[$APP_BUILD_GRADLE] 缺失 `libs.compose.material3.alpha` 显式覆盖声明——"
                + "双声明一旦消失，material3 即退回纯 BOM 托管，`libs.versions.toml` 的钉版行失去效力",
            appBuild.contains("libs.compose.material3.alpha")
        )

        val pom = locateBomPom(bomVersion)
        val managed = parseManagedVersion(pom, "androidx.compose.material3", "material3")
            ?: error(
                "BOM POM 中未找到 androidx.compose.material3:material3 的托管版本（$pom）——"
                    + "BOM 结构是否已变？本守卫据此失去判别对象，须先修判据"
            )
        assertTrue(
            "BOM $bomVersion 映射的 material3 = $managed **高于**钉版 $pinned——"
                + "此时双声明的实际解析由「最高版本胜出」决定，钉版行被静默架空。"
                + "处置：要么下调 BOM / 上调钉版使其重新生效，要么按退出条件回归纯 BOM 托管"
                + "（并同步删除钉版行与 alpha 别名）。",
            compareVersion(managed, pinned) <= 0
        )
    }

    /** 版本比较判据的已知值反校（避免本守卫退化为恒真断言） */
    @Test
    fun `版本比较判据具备判别力`() {
        assertTrue("stable 必须高于同 core 的 alpha", compareVersion("1.4.0", "1.5.0-alpha28") < 0)
        assertTrue("core 版本更高者胜（跨 minor）", compareVersion("1.5.0-alpha01", "1.4.0") > 0)
        assertTrue("同线 alpha 序号越大越新", compareVersion("1.5.0-alpha28", "1.5.0-alpha27") > 0)
        assertEquals("同值比较为 0", 0, compareVersion("1.5.0-alpha28", "1.5.0-alpha28"))
        assertTrue("beta 高于 alpha", compareVersion("1.5.0-beta01", "1.5.0-alpha99") > 0)
        assertTrue("patch 位参与比较", compareVersion("1.4.1", "1.4.0") > 0)
    }

    /** 在 Gradle 缓存中定位指定版本 compose-bom 的 POM（fail-closed：找不到即抛错） */
    private fun locateBomPom(bomVersion: String): File {
        val gradleHome = System.getenv("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".gradle")
        val moduleRoot = File(gradleHome, "caches/modules-2/files-2.1/androidx.compose/compose-bom")
        assertTrue(
            "未找到 Gradle 依赖缓存目录：$moduleRoot（GRADLE_USER_HOME=${gradleHome}）——"
                + "本守卫需要 BOM POM 才能比对映射版本；请在解析过依赖后再跑本用例",
            moduleRoot.isDirectory
        )
        return moduleRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name == "compose-bom-$bomVersion.pom" }
            ?: error(
                "Gradle 缓存中未找到 compose-bom-$bomVersion.pom（已扫 $moduleRoot）——"
                    + "版本目录的 composeBom 与已解析依赖不一致？"
            )
    }

    /** 从 BOM POM 的 `dependencyManagement` 中取指定坐标的托管版本 */
    private fun parseManagedVersion(pom: File, groupId: String, artifactId: String): String? {
        val text = pom.readText()
        val start = text.indexOf("<dependencyManagement>")
        val scope = if (start >= 0) text.substring(start) else text
        return DEPENDENCY_BLOCK.findAll(scope).mapNotNull { block ->
            val body = block.value
            if (!body.contains("<groupId>$groupId</groupId>")) return@mapNotNull null
            if (!body.contains("<artifactId>$artifactId</artifactId>")) return@mapNotNull null
            Regex("<version>([^<]+)</version>").find(body)?.groupValues?.get(1)?.trim()
        }.firstOrNull()
    }

    /** 语义化版本比较：先比 core（major/minor/patch），再比预发布标识（无预发布 > 有预发布） */
    private fun compareVersion(left: String, right: String): Int {
        fun split(value: String): Pair<List<Int>, String?> {
            val core = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(value)
                ?: error("无法解析版本号（须形如 1.5.0-alpha28）：$value")
            val pre = value.substringAfter(core.value, "").trimStart('-').takeIf { it.isNotEmpty() }
            return core.groupValues.drop(1).map { it.toInt() } to pre
        }
        val (leftCore, leftPre) = split(left)
        val (rightCore, rightPre) = split(right)
        for (i in 0..2) {
            if (leftCore[i] != rightCore[i]) return leftCore[i].compareTo(rightCore[i])
        }
        return when {
            leftPre == null && rightPre == null -> 0
            leftPre == null -> 1          // 正式版高于预发布
            rightPre == null -> -1
            else -> comparePreRelease(leftPre, rightPre)
        }
    }

    private fun comparePreRelease(left: String, right: String): Int {
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        for (i in 0 until maxOf(leftParts.size, rightParts.size)) {
            val l = leftParts.getOrNull(i) ?: ""
            val r = rightParts.getOrNull(i) ?: ""
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val cmp = if (ln != null && rn != null) ln.compareTo(rn) else l.compareTo(r)
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** 取版本目录顶层 `key = "value"`；缺失即失败（防静默空扫） */
    private fun requireTomlValue(catalog: String, key: String): String =
        TOML_ASSIGNMENT.findAll(catalog).firstOrNull { it.groupValues[1] == key }?.groupValues[2]
            ?: error("[$VERSION_CATALOG] 未找到 `$key` 版本声明（目录结构是否已变更？）")

    /** 动态版本标记：命中返回原因描述，未命中返回 null */
    private fun dynamicMarker(value: String): String? {
        val v = value.trim()
        return when {
            v.endsWith("+") -> "通配版本"
            v.contains("latest.release", ignoreCase = true) -> "动态版本 latest.release"
            v.contains("latest.integration", ignoreCase = true) -> "动态版本 latest.integration"
            v.endsWith("-SNAPSHOT", ignoreCase = true) -> "快照版本"
            v.startsWith("[") || v.startsWith("(") -> "版本区间"
            else -> null
        }
    }

    /** 仓库内文件全文；app 模块测试工作目录为 `app/`，据此向上回溯定位 */
    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val target = File(candidate, relativePath)
            if (target.isFile) return target.readText()
            dir = candidate.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val VERSION_CATALOG = "gradle/libs.versions.toml"
        const val ROOT_SEARCH_DEPTH = 4

        /** 现值形态下版本目录远多于 20 条；低于此值即说明解析结构已变（防静默空扫） */
        const val MIN_DECLARATIONS = 20

        /** `key = "value"`（key 限 `[A-Za-z0-9_-]`，故 `version.ref = "…"` 一类带点键不入面） */
        val TOML_ASSIGNMENT = Regex("""^\s*([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"\s*(?:#.*)?$""", RegexOption.MULTILINE)

        const val APP_BUILD_GRADLE = "app/build.gradle.kts"

        /** material3 钉版的允许形态：仅 `1.5.0-alphaN`（N ≥ 1，允许 alpha 序号上调） */
        val MATERIAL3_PINNED_LINE = Regex("""1\.5\.0-alpha\d+""")

        /** BOM POM 的 `<dependency>…</dependency>` 块 */
        val DEPENDENCY_BLOCK = Regex("""<dependency>.*?</dependency>""", RegexOption.DOT_MATCHES_ALL)
    }
}
