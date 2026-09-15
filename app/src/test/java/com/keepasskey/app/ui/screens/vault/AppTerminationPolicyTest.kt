package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-17 `showKillAppOption` 单测：入口可见性与终止动作顺序；
 * ISSUE-P3-116 追加：退出前**易失缓存清理**必须在终止进程之前执行，且接线不得被静默删除。
 */
class AppTerminationPolicyTest {

    @Test
    fun `偏好开启且宿主可终止时呈现入口`() {
        assertTrue(AppTerminationPolicy.showsEntry(enabled = true, hostAvailable = true))
    }

    @Test
    fun `偏好关闭时不呈现入口`() {
        assertFalse(AppTerminationPolicy.showsEntry(enabled = false, hostAvailable = true))
    }

    @Test
    fun `宿主不可终止时不呈现入口`() {
        // 无 Activity 上下文时如实不呈现，避免「点了没反应」的假入口
        assertFalse(AppTerminationPolicy.showsEntry(enabled = true, hostAvailable = false))
    }

    @Test
    fun `终止动作为退栈_清理缓存_退出进程且顺序不可颠倒`() {
        val calls = mutableListOf<String>()

        AppTerminationPolicy.terminate(
            detachTask = { calls.add("finishAffinity") },
            purgeCaches = { calls.add("purgeCaches") },
            exitProcess = { code -> calls.add("exit:$code") }
        )

        assertEquals(listOf("finishAffinity", "purgeCaches", "exit:0"), calls)
        assertEquals(0, AppTerminationPolicy.EXIT_CODE_NORMAL)
    }

    /**
     * 清理**必须**早于进程终止：`exitProcess` 之后没有任何代码会执行，
     * 顺序一旦反过来，清理将永远不发生（而调用点不会有任何可见症状）。
     */
    @Test
    fun `清理缓存必须早于终止进程`() {
        val calls = mutableListOf<String>()

        AppTerminationPolicy.terminate(
            detachTask = {},
            purgeCaches = { calls.add("purge") },
            exitProcess = { calls.add("exit") }
        )

        assertTrue("清理步骤被跳过", "purge" in calls)
        assertTrue("清理必须早于 exitProcess", calls.indexOf("purge") < calls.indexOf("exit"))
    }

    // ===== ISSUE-P3-116：接线守卫（JVM 无法构造 Application 实例，故以源码形态锁定） =====

    @Test
    fun `应用外壳必须把易失缓存清理接入终止动作`() {
        val shell = stripComments(readSource(APP_SHELL_SOURCE))

        assertTrue(
            "[$APP_SHELL_SOURCE] 未向 AppTerminationPolicy.terminate 传入 purgeCaches——" +
                "「彻底退出应用」将再次变成「只退栈不清缓存」",
            shell.contains("purgeCaches = {")
        )
        assertTrue(
            "[$APP_SHELL_SOURCE] purgeCaches 必须调用 Application 上的统一清理入口",
            shell.contains("purgeVolatileCachesBeforeExit()")
        )
    }

    @Test
    fun `清理入口必须同时覆盖附件明文与同步密文快照两个面`() {
        val app = stripComments(readSource(APPLICATION_SOURCE))

        val body = functionBody(app, "fun purgeVolatileCachesBeforeExit(")
        assertTrue(
            "[$APPLICATION_SOURCE] 退出前清理必须覆盖附件缓存（cacheDir/attachments 解密明文）",
            body.contains("fileBinaryStore.clear()")
        )
        assertTrue(
            "[$APPLICATION_SOURCE] 退出前清理必须覆盖同步缓存（cacheDir/sync 密文快照）",
            body.contains("syncCacheEvictor.evictAll()")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    /**
     * 按花括号配对提取函数体（含函数体本身）。
     * 调用前须剔除注释，否则注释中的 `{` / `}` 会破坏配对。
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到函数：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("函数缺少函数体：$signature", open >= 0)

        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("函数体未闭合：$signature")
    }

    /** 剔除块注释与行注释——整改说明自身会写出被断言的字面量 */
    private fun stripComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        const val APP_SHELL_SOURCE = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt"
        const val APPLICATION_SOURCE = "app/src/main/java/com/keepasskey/app/MainApplication.kt"

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")

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

        const val ROOT_SEARCH_DEPTH = 4
    }
}
