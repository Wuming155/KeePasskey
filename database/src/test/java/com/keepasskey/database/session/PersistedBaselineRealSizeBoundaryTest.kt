package com.keepasskey.database.session

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P1-631`：持久化基线留存上限的**真实尺寸边界实跑**（方案第 8 项）。
 *
 * 为什么不能只靠「小额度等价测试 + 常量断言」：等价测试证明的是**控制流**（`+1` 走哪条分支），
 * 不能证明 160 MiB 量级下同一条流不发生 int 溢出、不在放弃后仍做等量克隆、不因 off-by-one
 * 把「恰好等于上限」判成超限。本件用**流式**输入（按需产出字节，测试自身不预分配 160 MiB 数组）
 * 真实跑完三个边界；三个边界**分列三个用例**，令每例的临时缓冲在方法返回后即可回收，
 * 避免同帧内同时驻留三份 160 MiB 量级缓冲把用例变成「测堆大小」。
 */
class PersistedBaselineRealSizeBoundaryTest {

    private companion object {
        /** 生产上限（与被测实现同源常量，不得写字面量） */
        const val LIMIT: Long = 160L * 1024 * 1024

        /** 读取块：真实解析器按块消费的形态（此处 64 KiB） */
        const val CHUNK = 64 * 1024
    }

    /** 按需产出 [size] 个字节的流式输入（不在测试侧预分配 160 MiB 数组） */
    private class GeneratedInputStream(private val size: Long) : InputStream() {
        private var produced = 0L

        override fun read(): Int {
            if (produced >= size) return -1
            produced++
            return 0x41
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (produced >= size) return -1
            val count = minOf(len.toLong(), size - produced).toInt()
            for (i in 0 until count) b[off + i] = 0x41
            produced += count
            return count
        }

        override fun available(): Int = (size - produced).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** 整流消费 [stream]，返回读出的总字节数（Long，避免 int 计数掩盖溢出） */
    private fun drain(stream: InputStream): Long {
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read < 0) break
            total += read
        }
        return total
    }

    private fun usedHeapBytes(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    @Test
    fun `上限减一 必须完整捕获且长度精确`() {
        val stream = PersistedBaselineCapturingInputStream(
            GeneratedInputStream(LIMIT - 1),
            MAX_PERSISTED_BASELINE_BYTES
        )
        assertEquals("上限-1 必须完整贯通（Long 计数，无 int 溢出）", LIMIT - 1, drain(stream))
        assertEquals(
            "上限-1 必须完整捕获（长度精确）",
            (LIMIT - 1).toInt(),
            stream.capturedLengthForTesting()
        )
        stream.wipe()
        assertEquals("清零后内部缓冲长度必须归零", 0, stream.capturedLengthForTesting())
    }

    @Test
    fun `恰好等于上限 必须被接受`() {
        val stream = PersistedBaselineCapturingInputStream(
            GeneratedInputStream(LIMIT),
            MAX_PERSISTED_BASELINE_BYTES
        )
        assertEquals("恰好上限必须完整贯通", LIMIT, drain(stream))
        assertEquals(
            "恰好上限必须被接受（判超即 off-by-one 缺陷）",
            LIMIT.toInt(),
            stream.capturedLengthForTesting()
        )
        stream.wipe()
    }

    @Test
    fun `超过上限一字节 贯通但整体放弃且不做等量克隆`() {
        val stream = PersistedBaselineCapturingInputStream(
            GeneratedInputStream(LIMIT + 1),
            MAX_PERSISTED_BASELINE_BYTES
        )
        assertEquals("超限后输入仍必须完整贯通给下游（解析不受影响）", LIMIT + 1, drain(stream))
        assertEquals("超限即整体放弃捕获", -1, stream.capturedLengthForTesting())

        // 定点度量「取回」这一步的堆增量：放弃后 capturedBytes 必须**直接返回 null**，
        // 不得调用 toByteArray() 克隆第二份 160 MiB 量级缓冲。
        val before = usedHeapBytes()
        val captured = stream.capturedBytes()
        val delta = usedHeapBytes() - before
        println("[P1-631] 超限后 capturedBytes() 调用的堆增量 = $delta 字节（阈值 32 MiB）")
        assertNull("放弃后取回必须为 null", captured)
        assertTrue(
            "放弃后取回不得克隆等量缓冲（实测增量=$delta 字节，应远小于 160 MiB）",
            delta < 32L * 1024 * 1024
        )
        stream.wipe()
    }

    @Test
    fun `超限捕获不得把旧基线留成可信当前版本`() {
        val core = SessionCore()
        core.retainPersistedBaseline(ByteArray(64) { 0x7A }, "/vaults/big.kdbx")
        val oldBuffer = core.persistedBaselineInternalBytesForTesting() ?: error("旧基线必须存在")
        assertNotNull("前提：旧基线已登记", core.persistedBaselineClone())
        val revisionBefore = core.persistedBaselineRevision()

        // 本次打开超限 ⇒ 未捕获 ⇒ 登记 null（会话侧口径：不登记 = 作废旧基线）
        val over = PersistedBaselineCapturingInputStream(
            GeneratedInputStream(LIMIT + 1),
            MAX_PERSISTED_BASELINE_BYTES
        )
        assertEquals(LIMIT + 1, drain(over))
        assertNull(over.capturedBytes())
        core.retainPersistedBaseline(over.capturedBytes(), "/vaults/big.kdbx")
        over.wipe()

        assertNull(
            "超限未捕获时不得保留旧基线——旧值对应的是**旧版本**，冒充「可信当前版本」会让合并底版错误",
            core.persistedBaselineClone()
        )
        assertTrue("旧基线的内部缓冲必须已就地清零", oldBuffer.all { it == 0.toByte() })
        assertTrue("修订必须前移（令在途校验失效）", core.persistedBaselineRevision() > revisionBefore)
    }

    @Test
    fun `无可信基线时快照为 null 即后续漂移走保守路径`() {
        val core = SessionCore()
        assertNull("未登记基线时快照必须为 null（下游按空底版并集语义合并）", core.persistedBaselineClone())
        assertNull("未登记时无路径标识", core.persistedBaselinePathIdentifier())
    }
}
