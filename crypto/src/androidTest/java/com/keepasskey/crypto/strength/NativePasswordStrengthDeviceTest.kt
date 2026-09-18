package com.keepasskey.crypto.strength

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * 口令强度原生内核 · **设备侧真机验证**（JNI 定长布局契约）。
 *
 * 为什么必须真机：`AGENTS.md` §3 规则 2 把本内核定为**全库热路径**，并单独规定了
 * 「JNI 定长布局契约：强度评估返回定长 3 元 `IntArray [score, log10×100, flags]`，
 * `FLAG_*` 位值两侧逐位对齐」。宿主侧 `PasswordStrengthTest` 的跨语言契约用例带 `Assume`
 * （桌面 `cargoHostBuild` 产物缺失即跳过），且**只断言「期望位被置起」**——
 * 从不检查「返回了未声明的位」，也不检查 FFI 是否改写调用方缓冲。设备侧的
 * `.so` 由 `:crypto:cargoNdkBuild` 交叉编译，与宿主产物是两个二进制。
 *
 * 本用例补上宿主看不见的四件事：
 * 1. **布局不变量**：任何输入都恰好返回 3 个 int，且 `flags` 只落在 Kotlin 侧已声明的
 *    位并集内（Rust 侧新增/改动位值而 Kotlin 未同步时，宿主按位与断言仍绿，此处直接报红）；
 * 2. **调用方缓冲不被 FFI 改写**：口令字节由调用方负责清零，若原生侧把内部副本回写进
 *    调用方数组，健康检查复用同一缓冲的后续逻辑会被静默破坏；
 * 3. **null 实参闸门**：Rust 侧 `is_null()` 闸门缺失在真机上是 SIGSEGV（进程被杀），
 *    Kotlin 类型系统挡不住反射传 null；
 * 4. **热路径量级**：ART + NDK 产物下单次评估的实测耗时（回归到「每调用重建词表」这类
 *    量级问题时立即触顶）。
 *
 * 敏感数据：输入全为**公开合成口令**（非真实凭据），仍用 `ByteArray` 承载并保留清零习惯。
 */
@RunWith(AndroidJUnit4::class)
class NativePasswordStrengthDeviceTest {

    /** 覆盖九类标志与边界输入的合成口令集（公开常量，非真实凭据）。 */
    private val corpus = listOf(
        "",
        "a",
        "aB3\$",
        "password",
        "aaaaBBBB",
        "abcdWXYZ",
        "qwertyui",
        "user1990name",
        "abcdefghij",
        "aaaaaaaaaa",
        "abcabcabc",
        "aB3\$kLmQ7#xZ9!",
        "tR7#kL9@mQ2!xZ4&vB6*",
        "口令密码测试"
    )

    @Test
    fun 定长三元布局与已声明位并集在真机成立() {
        val declaredMask = passwordStrengthFlagUnion()
        for (pw in corpus) {
            val bytes = pw.toByteArray(Charsets.UTF_8)
            val layout = NativePasswordStrength.estimate(bytes)
                ?: throw AssertionError("[$pw] 真机上原生评估返回 null（内核异常或符号缺失）")
            assertEquals(
                "[$pw] 跨 FFI 布局必须是定长 3 元 IntArray",
                LAYOUT_SIZE, layout.size
            )
            assertTrue(
                "[$pw] 分档越界：${layout[0]}",
                layout[0] in PasswordStrengthEvaluator.SCORE_MIN..PasswordStrengthEvaluator.SCORE_MAX
            )
            assertTrue("[$pw] log10×100 应为非负，实际 ${layout[1]}", layout[1] >= 0)
            assertEquals(
                "[$pw] 返回了 Kotlin 侧未声明的标志位 0x${(layout[2] and declaredMask.inv()).toString(16)}" +
                    "（两侧位值契约已漂移）",
                0,
                layout[2] and declaredMask.inv()
            )
        }

        // 探活自身的契约：`password` 必须判为分档下界且命中常见口令表
        val common = NativePasswordStrength.evaluate("password".toByteArray(Charsets.US_ASCII))
        assertEquals("password 分档应为下界", PasswordStrengthEvaluator.SCORE_MIN, common.score)
        assertTrue("password 必须命中常见口令表", common.isCommonPassword)
    }

    @Test
    fun 超长与非UTF8字节输入不破坏布局() {
        // 超出内核分析上限的长口令：走「超额线性惩罚」分支，布局仍须为 3
        val long = ByteArray(LONG_PASSWORD_BYTES) { ((it % 94) + 33).toByte() }
        val longLayout = NativePasswordStrength.estimate(long)
        assertEquals("长口令布局必须为定长 3 元", LAYOUT_SIZE, longLayout?.size)

        // 非法 UTF-8 字节序列不得抛异常、不得返回非法布局
        val invalid = byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte(), 0xC3.toByte())
        val invalidLayout = NativePasswordStrength.estimate(invalid)
        assertEquals("非法 UTF-8 输入布局必须为定长 3 元", LAYOUT_SIZE, invalidLayout?.size)
        assertTrue(
            "非法 UTF-8 输入的分档越界：${invalidLayout?.get(0)}",
            invalidLayout != null &&
                invalidLayout[0] in PasswordStrengthEvaluator.SCORE_MIN..PasswordStrengthEvaluator.SCORE_MAX
        )

        long.fill(0)
        invalid.fill(0)
    }

    @Test
    fun 原生评估不改写调用方口令缓冲() {
        // 契约：本入口不克隆、不留存，清零点由调用方持有（健康检查会复用同一缓冲）。
        // 若 Rust 侧改用 GetByteArrayElements + 模式 0 回写，Zeroizing 归零会顺着回写
        // 把调用方的口令抹掉——宿主看不到（桌面产物同一逻辑，但热路径复用点在设备侧）。
        for (pw in corpus) {
            val bytes = pw.toByteArray(Charsets.UTF_8)
            val snapshot = bytes.copyOf()
            NativePasswordStrength.evaluate(bytes)
            assertArrayEquals("[$pw] 原生评估改写了调用方缓冲", snapshot, bytes)
            bytes.fill(0)
            snapshot.fill(0)
        }
    }

    @Test
    fun null实参在JNI边界归一为null() {
        val estimate = NativePasswordStrength::class.java.getDeclaredMethod(
            "estimate",
            ByteArray::class.java
        )
        val nullPassword: ByteArray? = null
        assertNull(estimate.invoke(NativePasswordStrength, nullPassword) as IntArray?)
    }

    @Test
    fun 真机重复评估稳定且单次耗时落在热路径量级内() {
        val bytes = "tR7#kL9@mQ2!xZ4&vB6*".toByteArray(Charsets.US_ASCII)
        val expected = requireNotNull(NativePasswordStrength.estimate(bytes)) { "基线评估返回 null" }

        // 预热：类加载 / 词表首建不计入统计
        repeat(WARMUP_CALLS) { NativePasswordStrength.estimate(bytes) }
        val durations = LongArray(MEASURED_CALLS)
        for (i in 0 until MEASURED_CALLS) {
            val startedAt = System.nanoTime()
            val actual = NativePasswordStrength.estimate(bytes)
            val elapsed = System.nanoTime() - startedAt
            assertArrayEquals("第 $i 次评估结果与基线不一致（ART JNI 层不稳定）", expected, actual)
            durations[i] = elapsed
        }
        durations.sort()
        val medianMicros = durations[durations.size / 2] / 1_000
        val p95Micros = durations[durations.size * 95 / 100] / 1_000

        println(
            String.format(
                Locale.US,
                "STRENGTH-MEASURE|api=%d abi=%s calls=%d medianMicros=%d p95Micros=%d",
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.joinToString(),
                MEASURED_CALLS,
                medianMicros,
                p95Micros
            )
        )
        assertTrue(
            "单次评估中位数 ${medianMicros}µs 超过热路径上界 ${PER_CALL_BUDGET_MICROS}µs" +
                "（数量级回退：如每次调用重建词表 / 锁争用）",
            medianMicros <= PER_CALL_BUDGET_MICROS
        )
        bytes.fill(0)
        expected.fill(0)
    }

    private companion object {
        private const val LAYOUT_SIZE = 3
        private const val LONG_PASSWORD_BYTES = 10_000
        private const val WARMUP_CALLS = 50
        private const val MEASURED_CALLS = 2_000

        /**
         * 热路径单次上界（**量级闸门，非性能目标**）：健康检查会遍历全库条目，
         * 单次评估回退到毫秒以上即会把遍历推向不可接受的范围。
         */
        private const val PER_CALL_BUDGET_MICROS = 2_000L

        @JvmStatic
        @BeforeClass
        fun requireNativeLibraryAvailable() {
            if (!NativePasswordStrength.available) {
                fail(
                    "NativePasswordStrength.available == false：设备侧强度内核未生效\n" +
                        "  Build.MODEL=${Build.MODEL}, SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.joinToString()}\n" +
                        "  请核对 :crypto:cargoNdkBuild 产物与 estimate 导出符号"
                )
            }
        }
    }
}

/** 已声明标志位的并集（逐名 OR，不复写位掩码字面量，避免与 [PasswordStrengthFlags] 漂移）。 */
private fun passwordStrengthFlagUnion(): Int =
    PasswordStrengthFlags.COMMON_PASSWORD or
        PasswordStrengthFlags.TOO_SHORT or
        PasswordStrengthFlags.REPEATED_RUN or
        PasswordStrengthFlags.SEQUENCE or
        PasswordStrengthFlags.KEYBOARD_WALK or
        PasswordStrengthFlags.DATE_LIKE or
        PasswordStrengthFlags.SINGLE_CHAR_CLASS or
        PasswordStrengthFlags.LOW_UNIQUE_RATIO or
        PasswordStrengthFlags.PERIODIC_REPEAT
