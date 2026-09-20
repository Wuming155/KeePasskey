package com.keepasskey.app.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 供应链**扫描面**收口守卫（`ISSUE-P2-219`）。
 *
 * 缺陷背景：`dependencyCheckAggregate` 会对项目**全部可解析配置**做扫描，而 AGP 会为自带的
 * 测试 / lint / 截图校验工具链创建一批配置（`unified-test-platform-*` / `androidLintTool` /
 * `_internal-screenshot-*`）。这些配置的产物**只在构建机 JVM 上运行、永不进入 APK**，其构件
 * 也不是本仓声明的依赖——把它们纳入扫描，等于让 AGP 内部件的 CVE 长期把闸门压红。
 * 2026-09-20 实测（本地同参数扫描）：`漏洞实例 1316 条 / 达阈（CVE × 构件）948 条`，
 * **948 条全部落在这些工具链配置族上**，生产 runtime / compile 面为 0 条（详见
 * `docs/resolved/batches/225-CI门禁四项缺陷整改批次.md` §2）。
 *
 * 处置是**扫描面收口**而非逐条豁免（理由与 AC④ 的「登记口径后从闸门移除」通道见同一批次文档）；
 * 本用例把该口径变成机器可查（两条不变式）——避免它退化为散文承诺（`ISSUE-P3-205` 的教训）：
 *
 * 1. **AGP 工具链族必须留在 skip 名单内**：否则 AGP 内部件会重新回到闸门，闸门再次失去信息量；
 * 2. **生产配置族不得混入 skip 名单**：否则会掩盖**本仓真实依赖**的漏洞——这正是
 *    「不得以放宽阈值或删除断言了事」的机检出口（工具链族之外的任何新增跳过都会先在这里红灯）。
 */
class SupplyChainScanSurfaceTest {

    @Test
    fun `AGP 工具链配置族必须显式排除在扫描面之外`() {
        val skipped = skippedConfigurations()

        val missing = REQUIRED_SKIPPED.filterNot { it in skipped }
        assertTrue(
            "[$INIT_SCRIPT] 以下 AGP 工具链配置未排除在扫描面外（其 CVE 将重新把闸门压红）：$missing；" +
                "当前 skip 名单=$skipped",
            missing.isEmpty()
        )
    }

    @Test
    fun `扫描面不得排除生产配置族`() {
        val skipped = skippedConfigurations()

        val offending = skipped.filter { name ->
            (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) &&
                !name.contains(ANDROID_TEST_MARKER) && !name.contains(SCREENSHOT_TEST_MARKER)
        }
        assertTrue(
            "[$INIT_SCRIPT] 以下被排除的配置疑似**生产面**（会掩盖本仓真实依赖的漏洞，须先登记口径）：" +
                "$offending。生产面配置（如 `releaseRuntimeClasspath` / `debugCompileClasspath`）" +
                "一律不得跳过——放宽扫描面的唯一合法通道是登记为 AGP 工具链族并附带依据",
            offending.isEmpty()
        )
    }

    /** 解析 init 脚本 `skipConfigurations = mutableListOf( ... )` 中的字符串字面量 */
    private fun skippedConfigurations(): List<String> {
        val source = readSource(INIT_SCRIPT)
        val block = SKIP_BLOCK.find(source)
        assertTrue(
            "[$INIT_SCRIPT] 未匹配到 `skipConfigurations = mutableListOf(…)` 块（结构是否已变更？）",
            block != null
        )
        val names = STRING_LITERAL.findAll(requireNotNull(block).value).map { it.groupValues[1] }.toList()
        assertTrue("[$INIT_SCRIPT] skip 名单解析为空（结构变更？）", names.isNotEmpty())
        return names
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val INIT_SCRIPT = ".github/dependency-check.init.gradle.kts"
        const val ANDROID_TEST_MARKER = "androidTest"
        const val SCREENSHOT_TEST_MARKER = "screenshotTest"

        /** `unified-test-platform-*` 与 `_internal-screenshot-*` 族的代表项 + lint 工具本体 */
        val REQUIRED_SKIPPED = listOf(
            "unified-test-platform-core",
            "unified-test-platform-launcher",
            "unified-test-platform-android-device-provider-ddmlib",
            "unified-test-platform-android-driver-instrumentation",
            "unified-test-platform-android-test-plugin",
            "unified-test-platform-android-test-plugin-host-additional-test-output",
            "unified-test-platform-android-test-plugin-host-apk-installer",
            "unified-test-platform-android-test-plugin-host-coverage",
            "unified-test-platform-android-test-plugin-host-device-info",
            "unified-test-platform-android-test-plugin-host-emulator-control",
            "unified-test-platform-android-test-plugin-host-logcat",
            "unified-test-platform-android-test-plugin-result-listener-gradle",
            "unified-test-platform-gradle-work-action",
            "androidLintTool",
            "_internal-screenshot-test-task-layoutlib",
            "_internal-screenshot-test-task-layoutlib-res",
            "_internal-screenshot-validation-junit-engine"
        )

        /** `skipConfigurations = mutableListOf(` 起、至与其配对的 `)` 止（非贪婪到首个行首缩进的右括号） */
        val SKIP_BLOCK = Regex("""skipConfigurations\s*=\s*mutableListOf\(([\s\S]*?)\n\s*\)""")

        /** 块内的字符串字面量（跳过 `//` 注释行——注释里出现的配置名不参与判定） */
        val STRING_LITERAL = Regex("""^\s*"([^"]+)"\s*,?\s*$""", RegexOption.MULTILINE)

        const val ROOT_SEARCH_DEPTH = 4

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
