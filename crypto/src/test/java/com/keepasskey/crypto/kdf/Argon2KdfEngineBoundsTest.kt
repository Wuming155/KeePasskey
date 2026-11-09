package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.kdf.KdfParameters.Argon2.Argon2Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-59（审计 RUST-05）回归：Argon2 派生入口的参数接受域与受检窄化。
 *
 * 背景：
 * ① 原生内核只做**下界**闸门（`memoryKib ≥ 8 × parallelism`）与 JNI 有符号闸门，**无逐项上界**；
 *    上界原本只存在于 `KdbxKdfParameterCodec.validateArgon2Bounds`（deserialize 阶段），
 *    原生派生入口自身不复核 → 参数一旦绕过（内部构造 / 未来新入口）即无第二道防线。
 * ② `(memoryInBytes / 1024).toInt()` / `iterations.toInt()` 在超 `Int` 范围时**静默窄化**
 *    （回绕为任意值，含负数），使「被静默改写的参数」参与派生或被 JNI 闸门拒绝，根因不可辨。
 *
 * ENG-20261028-08：越界的裁决点是**公共接受域本身**，不再是「走原生还是走 BC」的路由条件——
 * [Argon2KdfEngine.transform] 对界外参数直接抛 [CryptoException.KdfException]，原生不可用 /
 * 版本不支持 / AD 超限这三类**实现可用性**回退保持原样（AD 回退见 `Argon2AdLimitFallbackTest`）。
 * ENG-20261028-16：基础边界定义在 [Argon2KdfLimits]，database `KdbxKdfAcceptance` 引用同一处，
 * 两侧的逐点对拍仍由 `Argon2KdfBoundsAlignmentTest` 守着，但不再需要同值副本。
 */
class Argon2KdfEngineBoundsTest {

    private val engine = Argon2KdfEngine(Argon2Type.ARGON2ID)

    private fun params(
        memoryInBytes: Long,
        iterations: Long = 2L,
        parallelism: Int = 2
    ) = KdfParameters.Argon2(
        type = Argon2Type.ARGON2ID,
        salt = ByteArray(32) { 0x42 },
        parallelism = parallelism,
        memoryInBytes = memoryInBytes,
        iterations = iterations,
        version = KdfParameters.Argon2.ARGON2_VERSION_13
    )

    // ===== AC①：基础边界（唯一定义见 Argon2KdfLimits，database 接受域引用同一处）=====

    @Test
    fun `基础边界_内存上下界`() {
        val gib4 = 4L * 1024 * 1024 * 1024
        // ISSUE-P2-290：交叉约束后 p=1 的下界恰为 8192（p=2 需 ≥ 16384，见专项用例）
        assertTrue("8192B 且 p=1 恰达下界，应在界内", Argon2KdfEngine.isWithinKdfBounds(8192L, 2, 1))
        assertFalse("8191B 低于下界", Argon2KdfEngine.isWithinKdfBounds(8191L, 2, 2))
        assertTrue("4 GiB 恰为封顶，应在界内", Argon2KdfEngine.isWithinKdfBounds(gib4, 2, 2))
        assertFalse("4 GiB + 1B 越界", Argon2KdfEngine.isWithinKdfBounds(gib4 + 1, 2, 2))
    }

    // ===== ISSUE-P2-290 AC②：交叉约束（memory ≥ 8 × parallelism × 1024），与原生内核下界逐项对齐 =====

    @Test
    fun `交叉约束_固定样本 m=8192 且 p=64 判越界`() {
        // 条目固定样本：逐项合法、交叉非法（8192 < 8 × 64 × 1024）
        assertFalse(
            "m=8192 且 p=64 必须判越界（禁「上游放行、内核拒」的口径缺口）",
            Argon2KdfEngine.isWithinKdfBounds(8192L, 2, 64)
        )
    }

    @Test
    fun `交叉约束_恰达与不达每通道下界的逐项判定`() {
        assertTrue("p=2 且 m=16384 恰达（8×2×1024）", Argon2KdfEngine.isWithinKdfBounds(16384L, 2, 2))
        assertFalse("p=2 且 m=8192 不达每通道下界", Argon2KdfEngine.isWithinKdfBounds(8192L, 2, 2))
        assertTrue("p=64 且 m=524288 恰达", Argon2KdfEngine.isWithinKdfBounds(524288L, 2, 64))
    }

    @Test
    fun `基础边界_迭代与并行度上下界`() {
        val maxIterations = 1L shl 24
        assertTrue(Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, maxIterations, 2))
        assertFalse(
            "2^24 + 1 次迭代越界",
            Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, maxIterations + 1, 2)
        )
        assertFalse("迭代 0 越界", Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, 0, 2))
        assertTrue(Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, 2, 64))
        assertFalse("并行度 65 越界", Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, 2, 65))
        assertFalse("并行度 0 越界", Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, 2, 0))
    }

    @Test
    fun `基础边界_不误拒合法用户配置`() {
        // AC③：上界须 ≥ 一切合法用户配置——覆盖本仓 KdfBenchmark 自荐上限（≤512 MiB × 20）与
        // 官方默认（64 MiB / t=2 / p=2）及常见更强配置
        assertTrue(
            "官方默认 Argon2id 配置不得被拒",
            Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, 2, 2)
        )
        assertTrue(
            "本仓自荐上限（512 MiB × 20 迭代）不得被拒",
            Argon2KdfEngine.isWithinKdfBounds(512L * 1024 * 1024, 20, 4)
        )
        assertTrue(
            "边界内大内存配置不得被拒",
            Argon2KdfEngine.isWithinKdfBounds(4L * 1024 * 1024 * 1024 - 1, 1, 1)
        )
    }

    // ===== ENG-20261028-08：越界参数在派生前拒绝（与原生可用性无关）=====

    /**
     * 低成本边界样本：全部在**任何派生之前**被拒绝，故不消耗 Argon2 工作量，也不依赖
     * 宿主是否加载到原生库——拒绝发生在本机实现路由之前，原生 / BC 两侧结论一致。
     */
    @Test
    fun `越界参数_派生入口抛出可处理的 KDF 失败`() {
        val overUpperMemory = 4L * 1024 * 1024 * 1024 + 1
        val samples = mapOf(
            "内存低于下界" to params(memoryInBytes = 8191L),
            "内存高于上界" to params(memoryInBytes = overUpperMemory),
            "内存低于每通道下界" to params(memoryInBytes = 8192L),
            "迭代为 0" to params(memoryInBytes = 64L * 1024, iterations = 0L),
            "迭代超上界" to params(memoryInBytes = 64L * 1024, iterations = (1L shl 24) + 1),
            "并行度为 0" to params(memoryInBytes = 64L * 1024, iterations = 2L, parallelism = 0),
            "并行度超上界" to params(memoryInBytes = 64L * 1024, iterations = 2L, parallelism = 65)
        )
        for ((label, sample) in samples) {
            assertFalse(
                "$label 不在公共接受域内",
                Argon2KdfEngine.isWithinKdfBounds(
                    sample.memoryInBytes,
                    sample.iterations,
                    sample.parallelism
                )
            )
            val ex = assertThrows("$label 必须拒绝派生", CryptoException.KdfException::class.java) {
                engine.transform(ByteArray(32), sample)
            }
            assertTrue(
                "$label 的失败须指明边界而非派生错误: ${ex.message}",
                ex.message?.contains("拒绝派生") == true
            )
        }
    }

    @Test
    fun `界内参数_接受域放行`() {
        // 与上一条互为对照：同一判据不得把合法配置判为越界
        assertTrue(
            "官方默认配置须在公共接受域内",
            Argon2KdfEngine.isWithinKdfBounds(64L * 1024 * 1024, 2L, 2)
        )
        assertNull(
            "界内参数不得产生越界原因",
            Argon2KdfLimits.outOfBoundsReason(64L * 1024 * 1024, 2L, 2)
        )
        assertNotNull(
            "越界参数须给出可读原因",
            Argon2KdfLimits.outOfBoundsReason(8191L, 2L, 2)
        )
    }

    // ===== AC②：受检窄化（拒绝静默截断）=====

    @Test
    fun `超 Int 可表达范围的内存参数_抛异常而非静默截断`() {
        // 3 TiB → /1024 = 3_221_225_472 KiB > Int.MAX_VALUE（2_147_483_647）
        val huge = 3L shl 40
        val ex = assertThrows(CryptoException.KdfException::class.java) {
            engine.transform(ByteArray(32), params(memoryInBytes = huge))
        }
        assertTrue(
            "异常信息须指明「超出可表达范围」（而非回绕成负数的下游误报）: ${ex.message}",
            ex.message?.contains("超出可表达范围") == true
        )
        assertTrue("异常信息须含字段名「内存」: ${ex.message}", ex.message?.contains("内存") == true)
    }

    @Test
    fun `超 Int 可表达范围的迭代参数_抛异常而非静默截断`() {
        val ex = assertThrows(CryptoException.KdfException::class.java) {
            engine.transform(
                ByteArray(32),
                params(memoryInBytes = 256L * 1024, iterations = 1L shl 40)
            )
        }
        assertTrue(
            "异常信息须指明「超出可表达范围」: ${ex.message}",
            ex.message?.contains("超出可表达范围") == true
        )
        assertTrue("异常信息须含字段名「迭代」: ${ex.message}", ex.message?.contains("迭代") == true)
    }

    @Test
    fun `受检窄化_边界值恰好等于 Int 上限时不被拒绝`() {
        // 恰好 Int.MAX_VALUE（不越界）：不得抛「超出可表达范围」
        assertEquals(Int.MAX_VALUE, Argon2KdfEngine.requireExpressibleAsInt(Int.MAX_VALUE.toLong(), "内存"))
        assertEquals(
            Int.MAX_VALUE,
            Argon2KdfEngine.requireExpressibleAsInt(Int.MAX_VALUE.toLong() * 1024, "内存", 1024L)
        )
        assertThrows(CryptoException.KdfException::class.java) {
            Argon2KdfEngine.requireExpressibleAsInt(Int.MAX_VALUE.toLong() + 1, "内存")
        }
    }
}
