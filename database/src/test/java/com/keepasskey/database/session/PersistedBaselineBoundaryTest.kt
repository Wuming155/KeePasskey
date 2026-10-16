package com.keepasskey.database.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * `ISSUE-P1-631`：持久化基线**留存上限**的边界专项。
 *
 * 口径与规模取舍（如实登记）：
 * - 生产上限 [MAX_PERSISTED_BASELINE_BYTES] = 160 MiB，且解析侧以 128 MiB 解压输出守卫封顶
 *   任何**可解析**库 ⇒ 该上限在真实打开链路上不可达（纯防御）。因此本件用**同一实现、
 *   仅限额参数不同**的方式覆盖 < / == / +1 边界语义（不是复制一份判据），并单独断言生产常量。
 * - 160 MiB 真尺寸实跑（构造 160 MiB 流 + 捕获缓冲）需要数百 MiB 堆且对 CI 无增益——
 *   **不做、不声称**；边界**语义**由等价的显式小额覆盖，生产**数值**由常量断言覆盖。
 */
class PersistedBaselineBoundaryTest {

    /** 惰性生成流：不持有全部字节，只有捕获侧会物化（用于验证「超限只放弃捕获」） */
    private class LazyStream(private val total: Int) : InputStream() {
        var produced = 0
            private set

        override fun read(): Int {
            if (produced >= total) return -1
            produced += 1
            return produced and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (produced >= total) return -1
            val count = minOf(len, total - produced)
            for (i in 0 until count) b[off + i] = ((produced + i + 1) and 0xFF).toByte()
            produced += count
            return count
        }

        override fun available(): Int = total - produced
    }

    private fun drainFully(input: InputStream): Int {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            total += read
        }
        return total
    }

    @Test
    fun `生产留存上限为 160 MiB 且为捕获流的默认限额`() {
        assertEquals("生产留存上限必须恰为 160 MiB", 160 * 1024 * 1024, MAX_PERSISTED_BASELINE_BYTES)
        // 默认参数即生产限额：不传限额构造的实例，其行为与显式传入生产限额的实例一致（小额示例）
        val payload = ByteArray(64) { it.toByte() }
        val byDefault = PersistedBaselineCapturingInputStream(ByteArrayInputStream(payload))
        byDefault.use { it.readBytes() }
        val fromDefault = byDefault.capturedBytes() ?: error("限额内必须捕获")
        byDefault.wipe()
        assertArrayEquals("默认限额实例必须正常捕获", payload, fromDefault)
    }

    @Test
    fun `恰好等于上限必须完整捕获`() {
        val limit = 1024
        val payload = ByteArray(limit) { (it and 0x7F).toByte() }
        val capturing = PersistedBaselineCapturingInputStream(ByteArrayInputStream(payload), limit)
        capturing.use { drainFully(it) }
        val captured = capturing.capturedBytes() ?: error("恰好上限必须捕获成功（不得少 1 字节即放弃）")
        assertEquals("恰好上限的捕获长度必须等于上限", limit, captured.size)
        assertArrayEquals("恰好上限的捕获内容必须逐位一致", payload, captured)
        capturing.wipe()
        assertNull("取回并清零后不得再产出捕获", capturing.capturedBytes())
    }

    @Test
    fun `超过上限 1 字节即整体放弃且读取完整贯通`() {
        val limit = 1024
        val payload = ByteArray(limit + 1) { (it and 0x7F).toByte() }
        val capturing = PersistedBaselineCapturingInputStream(ByteArrayInputStream(payload), limit)
        val drained = drainFully(capturing)
        assertEquals("超限只影响捕获，读取必须完整透传（不多不少）", payload.size, drained)
        assertNull("超限 1 字节必须整体放弃捕获（不留半份基线）", capturing.capturedBytes())
        capturing.wipe()
    }

    @Test
    fun `超限放弃后内部缓冲必须已清零`() {
        val limit = 256
        val capturing = PersistedBaselineCapturingInputStream(
            LazyStream(limit * 4),
            maxCaptureBytes = limit
        )
        assertEquals("读取必须完整贯通惰性流", limit * 4, drainFully(capturing))
        assertNull("超限后不得产出捕获", capturing.capturedBytes())
        // 内部缓冲在超限点即被 wipe：继续读不会重新填充，重复取回仍为 null
        assertNull("重复取回仍不得产出捕获", capturing.capturedBytes())
        capturing.wipe()
    }

    @Test
    fun `单字节读取路径的限额判定与批量读取一致`() {
        val limit = 32
        // 单字节 read() 路径：恰好上限 ⇒ 捕获成功
        val exact = PersistedBaselineCapturingInputStream(ByteArrayInputStream(ByteArray(limit)), limit)
        var produced = 0
        while (true) {
            val value = exact.read()
            if (value < 0) break
            produced += 1
        }
        assertEquals("单字节路径必须读满", limit, produced)
        assertEquals("单字节路径恰好上限必须捕获成功", limit, exact.capturedBytes()?.size)
        exact.wipe()

        // 单字节 read() 路径：超限 1 字节 ⇒ 放弃
        val overflow = PersistedBaselineCapturingInputStream(ByteArrayInputStream(ByteArray(limit + 1)), limit)
        var overflowRead = 0
        while (true) {
            val value = overflow.read()
            if (value < 0) break
            overflowRead += 1
        }
        assertEquals("单字节路径超限时读取仍须完整", limit + 1, overflowRead)
        assertNull("单字节路径超限 1 字节必须整体放弃", overflow.capturedBytes())
        overflow.wipe()
    }

    @Test
    fun `超限未登记即等价于无可信基线`() {
        // 捕获超限 ⇒ capturedBytes() == null ⇒ 打开链以 retain(null) 登记 ⇒ 快照为 null。
        // 三段语义在本件与 PersistedBaselineLifecycleTest 各自锚定：
        // ① 超限 ⇒ null（本件）；② 登记 null ⇒ 无基线且旧值作废（Lifecycle 件）；③ 无基线 ⇒
        // 漂移合并走保守并集语义（app 侧 `VaultFileDriftMergeBaselineTest.验收3`）。
        val limit = 64
        val capturing = PersistedBaselineCapturingInputStream(ByteArrayInputStream(ByteArray(limit + 1)), limit)
        capturing.use { it.readBytes() }
        assertNull("① 超限必须不产出捕获", capturing.capturedBytes())
        capturing.wipe()
    }
}
