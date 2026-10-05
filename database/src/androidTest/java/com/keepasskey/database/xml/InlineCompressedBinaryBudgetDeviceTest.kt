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
 * 令解压产物 `D`、堆界 `M`。单节点成功路径的峰值**下界** ≈ `2D`（输出缓冲 + `toByteArray()` 副本）。
 * - `2D > M` ⇒ **必须**失败于 `OutOfMemoryError`（本用例断言此预测；该方向判据可靠）。
 * - `2D ≤ M` ⇒ **不能**推出成功（ISSUE-P2-490）：真实峰值还要叠加 base64 字符串 / char 数组 /
 *   压缩体 / gunzip 缓冲等中间态，恒**大于** `2D`。故此区间属临界带，"成功 / OOM" 皆可能。
 *   本用例的处理方式是**按构造规避**临界带：在安全带内（`D = M/4`，即 `2D = M/2`）另取一次
 *   断言成功——此处余量足矣，成功才是可判定的期望；若把满额规模放在临界带断言成功，
 *   则在 `2D == M` 的设备（如真机 M332BF：maxHeap = 256 MiB 与 2D = 256 MiB 恰好相等）必然假红。
 *
 * 另设 1 MiB 对照：证明该代码路径本身可用，排除"因为代码坏了才失败"的误读。
 *
 * 跨设备一致性（本条判据的立规缘由）：同一用例曾在 192 MiB 的 AVD 上走 OOM 分支通过、
 * 在 256 MiB 真机上走成功分支假红——**结论随设备堆界翻转**。改后两个方向均由规模与堆界
 * 的显式关系决定，与"这台机器恰好"无关。
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

        // ISSUE-P2-490 AC②：把「本机堆界 + 走哪条分支」打成可核对读数——跨堆界实跑的证据
        // 必须能从用例输出直接读出是哪一档（192 MiB / 256 MiB），不得靠外部推断。
        println(
            "[ISSUE-P2-200 落点① 堆界读数] maxHeap=$maxHeap (${maxHeap / MIB} MiB)" +
                ", 单节点峰值下界 2D=$peakLowerBound (${peakLowerBound / MIB} MiB)" +
                " ⇒ ${if (peakLowerBound > maxHeap) "OOM 分支（2D > M）" else "安全带分支（2D ≤ M）"}"
        )

        if (peakLowerBound > maxHeap) {
            // 方向一（本分支判据是**可靠**的）：峰值下界已超堆界 ⇒ 必然 OOM。
            // 这正是 ISSUE-P2-200 落点① 的「单节点即打崩堆」结论。
            assertTrue(
                "maxHeap=$maxHeap 低于单节点解压峰值下界（≈2×$MEMBER_LIMIT_BYTES=$peakLowerBound）" +
                    "⇒ 必须失败于 OutOfMemoryError（ISSUE-P2-200 落点① 的「单节点即打崩堆」）；实际=$outcome",
                outcome is InflateOutcome.OutOfMemory
            )
            return
        }

        // ISSUE-P2-490：`peakLowerBound` 只是**下界**（≈2D），真实峰值还要叠加
        // base64 字符串 / char 数组 / 压缩体 / gunzip 缓冲等中间态，故实际占用**严格大于 2D**。
        // 原实现在此分支断言「成功」，于是 `2D ≤ M` 一旦成立（尤其 `2D == M` 的临界值，
        // 如真机 M332BF 的 maxHeap=256MiB 与 2D=256MiB 恰好相等）就与真实行为矛盾：
        // 同一用例在 192 MiB 的 AVD 上走 OOM 分支通过、在 256 MiB 真机上走成功分支必红。
        // 现改为在**安全带**内取规模断言成功——令 2D = maxHeap/2，为中间态留足一倍余量，
        // 使「成功」成为可判定的期望；临界带（2D ≤ M < 2D+中间态）由构造方式**规避**而非断言。
        val safeBytes = maxHeap / SAFE_BAND_DIVISOR
        println(
            "[ISSUE-P2-200 落点① 安全带读数] 解压产物 D=$safeBytes (${safeBytes / MIB} MiB)" +
                ", 峰值下界 2D=${2L * safeBytes} (${2L * safeBytes / MIB} MiB) = maxHeap/2"
        )
        assertTrue(
            "测试前提：安全带规模须不超过生产允许上界 $MEMBER_LIMIT_BYTES（maxHeap=$maxHeap）",
            safeBytes in 1..MEMBER_LIMIT_BYTES
        )
        val safeOutcome = inflateInlineCompressed(safeBytes)
        assertTrue(
            "maxHeap=$maxHeap 下 2×$safeBytes=${
                2L * safeBytes
            } 仅为堆界一半（留足中间态余量），单节点内联压缩附件应当成功；实际=$safeOutcome",
            safeOutcome is InflateOutcome.Success
        )
        assertEquals(
            "安全带规模下解压产物字节数必须与压缩前一致",
            safeBytes,
            (safeOutcome as InflateOutcome.Success).bytes
        )
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
        /**
         * ISSUE-P2-490：安全带的分母——取 `maxHeap / 4` 作为解压产物规模，使峰值下界
         * `2D = maxHeap/2`，为「base64 中间态 + gunzip 缓冲 + `toByteArray()` 副本」
         * 留出约一倍堆界余量，使该带的「成功」成为可判定期望。
         */
        const val SAFE_BAND_DIVISOR = 4L

        /** 读数换算用（仅用于打印堆界档位，不参与判据） */
        const val MIB = 1024L * 1024

        /** 对照规模：必须成功 */
        const val CONTROL_BYTES = 1L * 1024 * 1024

        /** 与 `BinaryNode.MAX_INFLATED_ATTACHMENT_BYTES`（= 整包上限 128 MiB）等值的**允许**上界 */
        const val MEMBER_LIMIT_BYTES = 128L * 1024 * 1024

        const val CHUNK_BYTES = 64 * 1024

        /** 官方写侧规范字面量（`KdbxXmlEntrySerializer` 同源口径） */
        const val COMPRESSED_TRUE = "True"
    }
}
