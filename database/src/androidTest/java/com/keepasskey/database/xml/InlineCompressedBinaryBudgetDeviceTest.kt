package com.keepasskey.database.xml

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.helpers.AttributesImpl
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * **ISSUE-P2-200 落点① 的设备侧实测**：单个内联压缩附件节点的解压内存行为。
 *
 * ## 为什么必须设备侧
 *
 * 该条目的量化结论（「单节点解压瞬态峰值 ≈ 2–3 × 解压产物，低端机 1 个节点即可打崩堆」）
 * 依赖两项**运行时**事实，宿主 JVM 无法代表：
 * 1. 设备 `maxMemory()`（本仓 manifest 无 `largeHeap`，故即为平台给应用的堆界）；
 * 2. ART 在真实低内存压力下的 `OutOfMemoryError` 行为。
 *
 * 本用例**直接驱动生产代码** `[BinaryNode]`（`Compressed="True"` 的内联 `<Value>` 分支），
 * 而非复刻算法——被测的就是 `gunzip` 的逐调用封顶语义与其后的 `toByteArray()` 副本。
 *
 * ## 判别口径（与设备无关的自洽判据，避免"这台机器恰好"的伪结论）
 *
 * 令解压产物 `D`、堆界 `M`。单节点成功路径的峰值下界 ≈ `2D`（输出缓冲 + `toByteArray()` 副本）。
 * - `2D > M` ⇒ **必须**失败于 `OutOfMemoryError`（本用例断言此预测）；
 * - `2D ≤ M` ⇒ 允许成功（本用例断言成功）。
 *
 * 另设 1 MiB 对照：证明该代码路径本身可用，排除"因为代码坏了才失败"的误读。
 *
 * ## 边界（如实声明）
 *
 * 本用例只测**单节点**。多节点**累计**无预算（内联分支 `emit` 不调 `referenceBudget.account`）
 * 属静态可证的代码事实，不在此重复测量。
 */
@RunWith(AndroidJUnit4::class)
class InlineCompressedBinaryBudgetDeviceTest {

    @Test
    fun `小规模内联压缩附件正常解压（路径有效性对照）`() {
        val outcome = inflateInlineCompressed(CONTROL_BYTES)
        assertTrue("1 MiB 内联压缩附件必须正常解压，实际=$outcome", outcome is InflateOutcome.Success)
        assertEquals(
            "解压产物字节数必须与压缩前一致",
            CONTROL_BYTES,
            (outcome as InflateOutcome.Success).bytes
        )
    }

    @Test
    fun `单节点满额内联压缩附件的内存行为与堆界一致`() {
        val maxHeap = Runtime.getRuntime().maxMemory()
        val outcome = inflateInlineCompressed(MEMBER_LIMIT_BYTES)
        val peakLowerBound = 2L * MEMBER_LIMIT_BYTES

        if (peakLowerBound > maxHeap) {
            assertTrue(
                "maxHeap=$maxHeap 低于单节点解压峰值下界（≈2×$MEMBER_LIMIT_BYTES=$peakLowerBound）" +
                    "⇒ 必须失败于 OutOfMemoryError（ISSUE-P2-200 落点① 的「单节点即打崩堆」）；实际=$outcome",
                outcome is InflateOutcome.OutOfMemory
            )
        } else {
            assertTrue(
                "maxHeap=$maxHeap 足以容纳峰值下界 $peakLowerBound，解析应当成功；实际=$outcome",
                outcome is InflateOutcome.Success
            )
        }
    }

    /** 解压结果三态（把「OOM」与「其它失败」分开，避免把损坏误读为内存结论） */
    private sealed interface InflateOutcome {
        data class Success(val bytes: Long) : InflateOutcome
        data object OutOfMemory : InflateOutcome
        data class OtherFailure(val error: Throwable) : InflateOutcome
    }

    /**
     * 驱动生产代码：构造 `Compressed="True"` 的内联 `<Value>`，喂入 Base64 正文后闭合节点。
     * 全程不落盘、不经 KDBX 外壳——被测对象就是 `[BinaryNode]` 的解码 + 解压路径。
     */
    private fun inflateInlineCompressed(uncompressedBytes: Long): InflateOutcome {
        val base64 = Base64.getEncoder().encodeToString(gzipZeros(uncompressedBytes))
        var delivered: KdbxAttachment? = null
        try {
            val node = BinaryNode(
                binariesPool = emptyList(),
                innerStreamCipher = null,
                onDone = { delivered = it }
            )
            val valueAttributes = AttributesImpl().apply {
                addAttribute("", "", KdbxConstants.Xml.COMPRESSED, "CDATA", COMPRESSED_TRUE)
            }
            val valueNode = node.startChild(KdbxConstants.Xml.VALUE, valueAttributes)
            val chars = base64.toCharArray()
            valueNode.text(chars, 0, chars.size)
            valueNode.end()
            node.end()
        } catch (e: OutOfMemoryError) {
            return InflateOutcome.OutOfMemory
        } catch (t: Throwable) {
            return InflateOutcome.OtherFailure(t)
        }
        val attachment = delivered ?: return InflateOutcome.OtherFailure(
            IllegalStateException("节点闭合后未交付任何附件")
        )
        return InflateOutcome.Success(attachment.size)
    }

    /** 流式产出低熵压缩体：避免测试自身先物化一份未压缩数据（否则测的就不是解压峰值了） */
    private fun gzipZeros(totalBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            val chunk = ByteArray(CHUNK_BYTES)
            var written = 0L
            while (written < totalBytes) {
                val n = minOf(chunk.size.toLong(), totalBytes - written).toInt()
                gzip.write(chunk, 0, n)
                written += n
            }
        }
        return out.toByteArray()
    }

    private companion object {
        /** 对照规模：必须成功 */
        const val CONTROL_BYTES = 1L * 1024 * 1024

        /** 与 `BinaryNode.MAX_INFLATED_ATTACHMENT_BYTES`（= 整包上限 128 MiB）等值的**允许**上界 */
        const val MEMBER_LIMIT_BYTES = 128L * 1024 * 1024

        const val CHUNK_BYTES = 64 * 1024

        /** 官方写侧规范字面量（`KdbxXmlEntrySerializer` 同源口径） */
        const val COMPRESSED_TRUE = "True"
    }
}
