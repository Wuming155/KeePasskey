package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI 产物可见性守卫（**ISSUE-P3-123 ④**）。
 *
 * ## 缺陷形态
 *
 * 公开仓库的 `actions/upload-artifact` 产物**等同公开文件**。整改前 `build.yml` 把 R8 的
 * `mapping.txt` / `seeds.txt` / `usage.txt` / `configuration.txt` 一并归档，且该步骤带
 * `if: always()`、无任何可见性限制——对本应用（密码管理器）而言，等于把**类 / 方法 / 字段
 * 命名全貌**公开，任何人可据此把崩溃栈与反编译结果还原成可读符号。
 *
 * ## 判据为什么写成源码扫描
 *
 * 这是「某个 CI 步骤是否会把某类文件交出去」的**结构性**约束：运行期用例覆盖不到，
 * 而一旦有人在 `path:` 里加回一行就没有任何测试会变红。
 */
class CiArtifactVisibilityGuardTest {

    private val buildWorkflow = readSource(BUILD_WORKFLOW)

    @Test
    fun `构建工作流不得把 R8 去混淆映射作为产物上传`() {
        // 逐个 upload-artifact 步骤检查：从 `uses: actions/upload-artifact` 起，
        // 到下一条 `- name:` / `- uses:` 边界为止，该窗口内不得出现 mapping 路径。
        val windows = uploadArtifactWindows(buildWorkflow)

        assertTrue("未找到任何 upload-artifact 步骤（守卫失效，需更新锚点）", windows.isNotEmpty())
        windows.forEachIndexed { index, window ->
            assertFalse(
                "第 ${index + 1} 个 upload-artifact 步骤把去混淆映射列入产物路径——" +
                    "公开仓库的上传等同公开映射：\n$window",
                window.contains("outputs/mapping")
            )
        }
    }

    @Test
    fun `映射摘要必须写入 step summary 以保留审计能力`() {
        assertTrue(
            "不得只删上传而不留可审计摘要——那会把「保留面审计」一并丢掉",
            buildWorkflow.contains("GITHUB_STEP_SUMMARY")
        )
        assertTrue(
            "摘要步骤必须真的统计四个 R8 产物",
            listOf("mapping.txt", "seeds.txt", "usage.txt", "configuration.txt")
                .all { buildWorkflow.contains(it) }
        )
    }

    @Test
    fun `非发布说明必须写明映射不随产物归档`() {
        assertTrue(
            "NOT-FOR-RELEASE.txt 必须向拿到 CI 产物的人说明「映射不在其中、去哪儿取」",
            buildWorkflow.contains("**不随本产物归档**")
        )
    }

    @Test
    fun `全部工作流均不得上传去混淆映射`() {
        val workflows = File(repositoryRoot, WORKFLOW_DIR).listFiles { f -> f.name.endsWith(".yml") }
            .orEmpty()
        assertTrue("未找到任何工作流文件", workflows.isNotEmpty())

        workflows.forEach { file ->
            val source = file.readText()
            uploadArtifactWindows(source).forEach { window ->
                assertFalse(
                    "${file.name} 的 upload-artifact 步骤包含去混淆映射",
                    window.contains("outputs/mapping")
                )
            }
        }
    }

    /**
     * 截取每个 `upload-artifact` 步骤的产物配置窗口：自 `uses: actions/upload-artifact` 起，
     * 至下一条同级 `- name:` / `- uses:` 之前。
     */
    private fun uploadArtifactWindows(source: String): List<String> {
        val marker = "uses: actions/upload-artifact"
        val windows = mutableListOf<String>()
        var index = source.indexOf(marker)
        while (index >= 0) {
            val after = source.indexOf("\n      - ", index)
            windows += source.substring(index, if (after >= 0) after else source.length)
            index = source.indexOf(marker, index + marker.length)
        }
        return windows
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val BUILD_WORKFLOW = ".github/workflows/build.yml"
        const val WORKFLOW_DIR = ".github/workflows"

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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
