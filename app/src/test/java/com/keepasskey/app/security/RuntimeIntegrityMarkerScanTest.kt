package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * `ISSUE-P3-178`：`RuntimeIntegrityDetector.containsHookMarker` 的**流式字节匹配**回归。
 *
 * 该函数是 maps 层检测的判据实现（Frida 实测基线 `命中率 3/3` 的判据即 [HOOK_MARKERS]）。
 * 原实现逐行解码成 `String` 再对每行做 6 次 `contains(ignoreCase = true)`；本批改为
 * 「分块字节匹配 + 块间重叠」。**风险最大的改动点正是重叠窗口**——
 * 若重叠量算错，恰好跨越块边界的特征串会被漏掉，而那正是「改名 + 已注入」场景的检测依据。
 *
 * 本类以**小块**驱动该函数（生产分块 64 KiB，测试用 8 ~ 16 字节），逐项覆盖：
 * 跨块边界、大小写混合、散布于多块、无命中、以及「特征串被切成两半」的极端位置。
 */
class RuntimeIntegrityMarkerScanTest {

    /**
     * 测试分块大小：必须 **大于** 最长特征串（`substrate` / `edxposed` / `lsposed` 各 9 字符
     * ⇒ 重叠窗口为 8 字节），否则实现会以 `require` 拒绝（生产分块为 64 KiB，远大于此）。
     */
    private val chunkSize = 16

    @Test
    fun `特征串恰好跨越块边界时必须检出`() {
        // "frida"（5 字节）横跨块边界（索引 16）：前 k 字节在前块、其余在后块
        listOf(0, 1, 2, 3, 4).forEach { prefixLen ->
            val text = "x".repeat(chunkSize - prefixLen) + "frida" + "y".repeat(chunkSize)
            assertTrue(
                "偏移 $prefixLen 处的跨块特征串必须被检出（重叠窗口失效即漏报）",
                contains(text)
            )
        }
    }

    @Test
    fun `大小写混合的特征串必须检出`() {
        listOf("FRIDA", "Frida", "fRiDa", "XPOSED", "LSPosed", "Substrate", "LIBHOOK").forEach { marker ->
            assertTrue(
                "特征串 $marker 必须按 ASCII 不区分大小写命中",
                contains("aaa$marker" + "bbb")
            )
        }
    }

    @Test
    fun `散布于多块且远晚于首块的特征串必须检出`() {
        // 第 1 块干净、第 3 块才出现特征串：验证「循环未提前退出」与跨块状态维护
        val text = "a".repeat(chunkSize) + "b".repeat(chunkSize) +
            "memfd:frida-agent-64.so" + "c".repeat(chunkSize)
        assertTrue(contains(text))
    }

    @Test
    fun `全部块均无特征串时返回 false`() {
        val text = "libc.so\nlibm.so\nlibart.so\nkeepasskey\n".repeat(4)
        assertFalse("不得误报", contains(text))
    }

    @Test
    fun `输入短于一个块或为空时行为正确`() {
        assertFalse(contains(""))
        assertFalse(contains("libc"))
        assertTrue(contains("frida"))
    }

    @Test
    fun `特征串清单必须全为 ASCII`() {
        // 字节级匹配按 ASCII 折叠实现 ⇒ 清单里出现非 ASCII 字符会让匹配语义与
        // 原 `String.contains(ignoreCase = true)` 不再等价。该不变量是本批改动的前提，
        // 故由用例锁定：新增非 ASCII 特征串时此例失败，提示同步改匹配实现。
        RuntimeIntegrityDetector.HOOK_MARKERS.forEach { marker ->
            assertTrue(
                "特征串（$marker）必须全为 ASCII 且非空，否则字节级匹配需改实现",
                marker.isNotEmpty() && marker.all { it.code < 0x80 }
            )
        }
        RuntimeIntegrityDetector.HOOK_TRACE_PATHS.forEach { path ->
            assertTrue("落点路径（$path）必须全为 ASCII", path.all { it.code < 0x80 })
        }
    }

    private fun contains(text: String): Boolean =
        RuntimeIntegrityDetector.containsHookMarker(
            ByteArrayInputStream(text.toByteArray(StandardCharsets.US_ASCII)),
            chunkSize = chunkSize
        )
}
