package com.keepasskey.app.security

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
    }
}
