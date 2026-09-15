package com.keepasskey.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 原生内核**构建失败必须 fail-closed** 的守卫（ISSUE-P3-125③）。
 *
 * 缺陷背景：`crypto/build.gradle.kts` 的宿主构建任务曾设 `isIgnoreExitValue = true`，
 * 使得「cargo 缺失」与「cargo 构建失败」在结果上不可区分——两者都表现为
 * 「宿主库未产出 ⇒ JNI 用例经 `Assume` 跳过 ⇒ 构建保持绿色」。
 * 于是 **Rust 内核编译不过时全绿**，属「假绿」通道（用例跳过数会上升，但没有任何断言会失败）。
 *
 * 现策略（本用例锁定）：
 * - **缺失**（离线 / 无工具链）→ 保留 `onlyIf` 跳过 → 有意的降级路径；
 * - **失败**（工具链在但编译失败）→ 任务直接失败 → 构建失败（fail-closed）。
 *
 * 断言前剔除注释——整改说明自身会写出被断言的字面量。
 */
class NativeBuildFailClosedTest {

    @Test
    fun `宿主 cargo 构建不得吞掉非零退出码`() {
        val script = stripComments(readBuildScript())

        assertFalse(
            "crypto/build.gradle.kts 不得再对 cargo 构建任务设置 isIgnoreExitValue（会吞掉编译失败）：" +
                "「工具链缺失」应走 onlyIf 跳过，而「构建失败」必须 fail-closed",
            SWALLOW_EXIT_CODE.containsMatchIn(script)
        )
        assertTrue(
            "必须保留 onlyIf 的「cargo 缺失即跳过」降级路径（离线环境仍可跑其余单测）",
            MISSING_TOOLCHAIN_SKIP.containsMatchIn(script)
        )
    }

    @Test
    fun `宿主 cargo 构建任务仍真实执行 cargo build`() {
        val script = stripComments(readBuildScript())

        assertTrue(
            "构建命令必须仍为真实 cargo build（不得被替换为 no-op / echo 占位）",
            script.contains("""commandLine("cargo", "build", "--release")""")
        )
    }

    private fun readBuildScript(): String {
        val file = File(repositoryRoot, BUILD_SCRIPT)
        assertTrue("构建脚本不存在（是否被重命名/移动）：$BUILD_SCRIPT", file.isFile)
        return file.readText()
    }

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量 */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        const val BUILD_SCRIPT = "crypto/build.gradle.kts"

        /** 禁止形态：吞掉非零退出码（含被注释掉的情形已被 stripComments 排除） */
        val SWALLOW_EXIT_CODE = Regex("""isIgnoreExitValue\s*=\s*true""")

        /** 必需形态：「cargo 缺失」探活 + onlyIf 跳过 */
        val MISSING_TOOLCHAIN_SKIP =
            Regex("""onlyIf\s*\{[\s\S]*?cargo[\s\S]*?--version[\s\S]*?\}""")

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
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
