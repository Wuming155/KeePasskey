package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.kdf.KdfParameters.Argon2.Argon2Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-59（审计 RUST-05）回归：原生 Argon2 路径的参数上界镜像与受检窄化。
 *
 * 背景：
 * ① 原生内核只做**下界**闸门（`memoryKib ≥ 8 × parallelism`）与 JNI 有符号闸门，**无逐项上界**；
 *    上界原本只存在于 `KdbxKdfParameterCodec.validateArgon2Bounds`（deserialize 阶段），
 *    原生派生入口自身不复核 → 参数一旦绕过（内部构造 / 未来新入口）即无第二道防线。
 * ② `(memoryInBytes / 1024).toInt()` / `iterations.toInt()` 在超 `Int` 范围时**静默窄化**
 *    （回绕为任意值，含负数），使「被静默改写的参数」参与派生或被 JNI 闸门拒绝，根因不可辨。
 *
 * 解码后的上界值须与 `KdbxKdfParameterCodec` **同值**——本用例以硬编码期望值双向锁定，
 * 任一侧漂移即失败（模块依赖单向，crypto 不可反向引用 database 常量，故用同值声明 + 测试锁）。
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

    // ===== AC①：上界镜像（与 KdbxKdfParameterCodec.validateArgon2Bounds 同值）=====

    @Test
    fun `上界镜像_内存边界与 codec 同值`() {
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
    fun `上界镜像_迭代与并行度边界与 codec 同值`() {
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
    fun `上界镜像_不误拒合法用户配置`() {
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
