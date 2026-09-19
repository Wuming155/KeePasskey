package com.keepasskey.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `TracerPid` 探测的**设备侧**回归（**ISSUE-P3-83**）。
 *
 * ## 为什么宿主单测不够
 *
 * 宿主 JVM 用合成文本验证的是**解析逻辑**；而「`/proc/self/status` 在 Android 上确实以
 * `TracerPid:\t<N>` 的形式存在、且进程自身可读」是一条**平台事实**——AGENTS.md §5 明确
 * 「涉及平台 API 的逻辑不可只靠宿主单测」。本用例读**真实内核文件**并断言：
 * 字段存在、可解析、且与直接读取的字节内容一致。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：真实 `/proc/self/status` 可读、字段格式可解析、未被 trace 时值为 0、
 *   有界读取（≤ 8 KiB）足以覆盖该文件。
 * - **不覆盖**：真实 ptrace 附加下的非零取值——让本进程被真实 tracer 附加需要 root 侧
 *   `strace`/gdbserver 等外部工具配合，且会改变被测进程状态。该分支由宿主用例以合成内容覆盖。
 */
@RunWith(AndroidJUnit4::class)
class TracedProcessProbeDeviceTest {

    private val probe = ProcStatusTracedProcessProbe()

    @Test
    fun `真实 proc 文件的 TracerPid 字段可被解析`() {
        val raw = File("/proc/self/status").readText()

        assertTrue(
            "真实 /proc/self/status 必须含 TracerPid 字段（平台事实）",
            raw.lineSequence().any { it.startsWith("TracerPid:") }
        )

        val pid = ProcTracerPid.parse(raw)

        assertNotNull("真实内核格式必须可解析（解析失败即探测在本平台失效）", pid)
        assertTrue("TracerPid 不得为负", pid!! >= 0)
    }

    @Test
    fun `生产实现与直接读取结果一致`() {
        val direct = ProcTracerPid.parse(File("/proc/self/status").readText())
        val viaProbe = probe.tracerPid()

        assertEquals("生产实现的有界读取不得丢字段", direct, viaProbe)
    }

    @Test
    fun `未附加 tracer 的测试进程报告 0 且不误判为被 trace`() {
        val pid = probe.tracerPid()

        // ISSUE-P2-192 余量第 8 项：环境前提（测试进程未被真实 trace）从硬断言改为 Assume——
        // adb shell am instrument 起的测试进程默认无 tracer；开发者从 IDE 附加调试器时
        // 前提不成立即跳过并记入 skipped 数，不再被误读为「生产有 bug」（探测对非零
        // TracerPid 的正确判定由宿主合成内容用例覆盖）。
        org.junit.Assume.assumeTrue(
            "测试进程不应被 trace（TracerPid=$pid；若从 IDE 附加调试请以 am instrument 复跑）",
            pid == 0
        )
        assertTrue("TracerPid=0 不得被判为被 trace", !RuntimeIntegrityPolicy.isTraced(pid))
    }

    @Test
    fun `有界读取上限足以覆盖 status 文件`() {
        val size = File("/proc/self/status").readText().toByteArray(Charsets.UTF_8).size

        assertTrue(
            "status 实际大小 $size 字节，必须远小于有界读取上限，避免截断丢掉 TracerPid",
            size < 8 * 1024
        )
    }
}
