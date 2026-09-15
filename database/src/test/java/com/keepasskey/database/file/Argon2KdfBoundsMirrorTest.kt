package com.keepasskey.database.file

import com.keepasskey.crypto.kdf.Argon2KdfEngine
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-59（审计 RUST-05）AC①/③ 的**跨模块上界锁**：原生 Argon2 派生入口的**逐项上界**
 * 必须与 KDBX 参数解码侧（[KdbxKdfParameterCodec.validateArgon2Bounds]）同值。
 *
 * 为什么需要本用例：`crypto` 模块不可反向依赖 `database`（模块依赖单向），
 * 故 [Argon2KdfEngine.isWithinKdfBounds] 只能以「同值声明」镜像上界；若无跨模块锁，
 * 任一侧调整上界都会导致「解码放行但派生入口拒绝」（或反之）的静默语义分裂。
 *
 * **口径边界（本用例显式区分，不作等价断言）**：解码侧裁决 = 逐项上界 **且** `I×M` 联合预算
 * **且** 动态堆门槛（`memoryInBytes ≤ maxMemory()/2`）；派生入口镜像的只是**逐项上界**
 * （AC① 枚举的三项）。故：
 * - 逐项**越界**值：两侧必须一致拒绝（不依赖堆，可严格断言）；
 * - 逐项**界内**值：派生入口恒放行；解码侧是否放行取决于堆门槛（本用例按实际堆容量
 *   分支断言，不做环境依赖的硬编码预期）。
 */
class Argon2KdfBoundsMirrorTest {

    private val gib4 = 4L * 1024 * 1024 * 1024
    private val maxIterations = 1L shl 24

    private fun roundTrip(memoryInBytes: Long, iterations: Long, parallelism: Int): KdfParameters =
        KdbxKdfParameterCodec.deserialize(
            KdbxKdfParameterCodec.serialize(
                KdfParameters.Argon2(
                    type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
                    salt = ByteArray(32) { 0x42 },
                    parallelism = parallelism,
                    memoryInBytes = memoryInBytes,
                    iterations = iterations,
                    version = KdfParameters.Argon2.ARGON2_VERSION_13
                )
            ).toByteArray()
        )

    /** 解码侧是否放行（true = 通过 `validateArgon2Bounds` 全部三项裁决） */
    private fun codecAccepts(memoryInBytes: Long, iterations: Long, parallelism: Int): Boolean =
        try {
            roundTrip(memoryInBytes, iterations, parallelism)
            true
        } catch (_: KdbxCorruptFileException) {
            false
        }

    /** 逐项越界 ⇒ 两侧一致拒绝（堆无关，可严格断言） */
    private fun assertBothReject(memoryInBytes: Long, iterations: Long, parallelism: Int) {
        assertFalse(
            "解码侧应拒绝（m=$memoryInBytes, i=$iterations, p=$parallelism）",
            codecAccepts(memoryInBytes, iterations, parallelism)
        )
        assertFalse(
            "派生入口应拒绝（m=$memoryInBytes, i=$iterations, p=$parallelism）",
            Argon2KdfEngine.isWithinKdfBounds(memoryInBytes, iterations, parallelism)
        )
    }

    @Test
    fun `内存上界_越界值双侧一致拒绝`() {
        // 下界为官方语义值 8192 字节（非 1 MiB）
        assertBothReject(8191L, 2, 2)
        assertBothReject(gib4 + 1, 2, 2)
    }

    @Test
    fun `迭代上界_越界值双侧一致拒绝`() {
        assertBothReject(8192L, maxIterations + 1, 2)
        assertBothReject(8192L, 0, 2)
    }

    @Test
    fun `并行度上界_越界值双侧一致拒绝`() {
        assertBothReject(8192L, 2, 65)
        assertBothReject(8192L, 2, 0)
    }

    @Test
    fun `界内且堆可容纳_两侧一致放行`() {
        // 8192 B 远小于任何 JVM 堆门槛，故两侧均放行；迭代取 2^24 亦满足 I×M ≤ 2^40
        val memory = 8192L
        assertTrue("解码侧应放行", codecAccepts(memory, maxIterations, 2))
        assertTrue("派生入口应界内", Argon2KdfEngine.isWithinKdfBounds(memory, maxIterations, 2))
        assertTrue("并行度上界应界内", Argon2KdfEngine.isWithinKdfBounds(memory, 2, 64))
        assertTrue("下界应界内", Argon2KdfEngine.isWithinKdfBounds(8192L, 1, 1))
    }

    @Test
    fun `逐项界内但超堆门槛_派生入口仍放行_解码侧按堆裁决`() {
        // 4 GiB：逐项上界内（AC① 镜像放行），但解码侧动态堆门槛几乎必然拒绝——
        // 该差异是**有意**的：解码侧裁决的是「本进程能否安全派生」，
        // 派生入口镜像的是「参数是否落在规范上界内」；原生内核的内存由 Rust 侧
        // try_reserve 失败即返回 None（fail-closed），不依赖 JVM 堆。
        assertTrue("4 GiB 应落在逐项上界内", Argon2KdfEngine.isWithinKdfBounds(gib4, 2, 2))
        assertEquals(
            "解码侧裁决取决于堆容量（heap/2 ≥ 4 GiB 时才放行）",
            Runtime.getRuntime().maxMemory() / 2 >= gib4,
            codecAccepts(gib4, 2, 2)
        )
    }

    @Test
    fun `合法用户配置_派生入口恒放行且不误拒`() {
        // AC③：上界须 ≥ 一切合法用户配置——官方默认 / 本仓 KdfBenchmark 自荐上限
        val configs = listOf(
            Triple(64L * 1024 * 1024, 2L, 2),   // 官方默认 Argon2id
            Triple(512L * 1024 * 1024, 20L, 4), // 本仓 KdfBenchmark 自荐上限
            Triple(1L shl 30, 1L, 4),           // 1 GiB / 单轮
        )
        for ((m, i, p) in configs) {
            assertTrue(
                "$m / $i / $p 不得被派生入口误拒",
                Argon2KdfEngine.isWithinKdfBounds(m, i, p)
            )
            assertEquals(
                "$m / $i / $p 解码侧裁决应仅取决于堆门槛（heap/2 ≥ $m）",
                Runtime.getRuntime().maxMemory() / 2 >= m,
                codecAccepts(m, i, p)
            )
        }
    }

    @Test
    fun `越界内存参数_解码侧抛异常`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTrip(gib4 + 1, 2, 2) }
    }
}
