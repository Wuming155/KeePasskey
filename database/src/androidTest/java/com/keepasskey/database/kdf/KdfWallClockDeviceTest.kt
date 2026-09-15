package com.keepasskey.database.kdf

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.crypto.kdf.Argon2KdfEngine
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.kdf.NativeArgon2
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.KdbxKdfParameterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * KDF 墙钟与内存闸门的**设备侧分路径实测**（ISSUE-P2-80，M-1 / M-2）。
 *
 * ## 为什么必须实测
 *
 * `ISSUE-P2-49` 的墙钟量级与「`M = 堆/2` 的实际可达性」此前**全部是推算**，且推演已四方三错；
 * 复核报告据此要求「**不得以推算替代**」。本用例在真机上给出三组事实：
 *
 * - **M-2（分路径接受性）**：`M = 堆/2` 在**解码闸门**（`KdbxKdfParameterCodec`）与
 *   **两条派生路径**（原生 / BouncyCastle 兜底）上分别是否被接受，以及**实际到达内核的 KiB**；
 *   —— 复核的确切题面是「native 路径只有 codec 一道闸、`transformJvm` 还有 `0.6×maxHeap` 第二道」，
 *   故必须**分路径**测，不能用「两处阈值哪处先触发」的旧题面代替。
 * - **M-1（墙钟）**：默认配置与重载配置的**实测秒数**，并据此给出当前联合预算上界的量级。
 * - **题面更正**：条目原文要求实测「合法 Header（`I = 2²⁴`、`M = 堆/2`）」——
 *   该组合**已不再是合法 Header**：`ISSUE-P2-49` AC① 的 `I×M ≤ 2^40` 联合预算
 *   （`2^24 × 堆/2 ≈ 2^51`）会直接拒绝它。本用例把该事实**作为断言**记录下来。
 *
 * ## 安全与稳定性边界
 *
 * - 输入全部为**公开合成向量**（盐固定、口令固定），非真实凭据；派生结果用后清零、不入日志。
 * - 用例**不**运行 `I×M = 2^40` 的最坏合法配置（据实测速率换算为小时级，会让设备长时间满载）；
 *   该值以**实测速率换算**给出并**明确标注为换算值**（见日志前缀 `KDF-MEASURE|extrapolated=`）。
 *
 * 日志以 `KDF-MEASURE|` 前缀输出，便于从 `adb logcat` 与 AGP 测试 XML 的 `<system-out>` 提取。
 */
@RunWith(AndroidJUnit4::class)
class KdfWallClockDeviceTest {

    private val maxHeapBytes: Long = Runtime.getRuntime().maxMemory()
    private val heapHalfBytes: Long = maxHeapBytes / 2

    private fun report(key: String, value: Any) {
        val line = "KDF-MEASURE|$key=$value"
        Log.i(TAG, line)
        println(line)
    }

    /** 真实派生一次并返回耗时（纳秒）；派生结果与输入口令副本用后清零。 */
    private fun deriveNanos(memoryBytes: Long, iterations: Long, parallelism: Int): Long {
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32) { index -> (index + 1).toByte() },
            parallelism = parallelism,
            memoryInBytes = memoryBytes,
            iterations = iterations,
            version = KdfParameters.Argon2.ARGON2_VERSION_13
        )
        val composite = ByteArray(32) { 0x5A }
        val startedAt = System.nanoTime()
        val out = try {
            Argon2KdfEngine(KdfParameters.Argon2.Argon2Type.ARGON2ID).transform(composite, params)
        } finally {
            composite.fill(0)
        }
        val elapsed = System.nanoTime() - startedAt
        out.fill(0)
        return elapsed
    }

    private fun nanosToSeconds(nanos: Long): String = String.format("%.3f", nanos / 1_000_000_000.0)

    @Test
    fun `M-2 分路径实测堆一半内存的接受性与到达内核的值`() {
        report("api", Build.VERSION.SDK_INT)
        report("abi", Build.SUPPORTED_ABIS.joinToString("+"))
        report("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        report("maxHeapMiB", maxHeapBytes / (1024 * 1024))
        report("heapHalfMiB", heapHalfBytes / (1024 * 1024))
        report("nativeArgon2Available", NativeArgon2.available)

        // ① 解码闸门（KdbxKdfParameterCodec）：M = 堆/2 必须被接受；再 +1 MiB 必须被拒绝。
        //    这条闸门是「动态堆界限」的唯一定义处（M > 堆/2 一律按损坏文件拒绝）。
        KdbxKdfParameterCodec.validateArgon2Bounds(heapHalfBytes, 1L, 1, VERSION_13)
        report("codecAcceptsHeapHalf", true)

        val aboveHalfRejected = runCatching {
            KdbxKdfParameterCodec.validateArgon2Bounds(heapHalfBytes + MIB, 1L, 1, VERSION_13)
        }.exceptionOrNull()
        report("codecRejectsAboveHeapHalf", aboveHalfRejected is KdbxCorruptFileException)
        assertTrue(
            "堆/2 之上必须被解码闸门拒绝（否则动态堆界限失效）",
            aboveHalfRejected is KdbxCorruptFileException
        )

        // ② JVM 兜底路径的第二道闸门（0.6×maxHeap）：堆/2 = 0.5×maxHeap 应仍被视为可行
        //    —— 即「堆/2 与 0.6×maxHeap 两道闸」中，对 JVM 路径而言 codec 那道**更紧**。
        val jvmFeasible = Argon2KdfEngine.isMemoryParamFeasible(heapHalfBytes)
        report("jvmPathFeasibleAtHeapHalf", jvmFeasible)
        assertTrue("堆/2 必须通过 JVM 兜底的 0.6×maxHeap 预检", jvmFeasible)

        // ③ 原生路径**无第二道闸**：直接以 M = 堆/2 真实派生一次（I=1，耗时可控），
        //    成功即证明该内存量确实到达内核并被其接受（内核自身只有下界闸门）。
        assertTrue(
            "设备侧必须加载到原生内核，否则本项结论不成立",
            NativeArgon2.available
        )
        val nanos = deriveNanos(memoryBytes = heapHalfBytes, iterations = 1L, parallelism = 1)
        report("nativeHeapHalfI1Seconds", nanosToSeconds(nanos))
        report("nativeKernelMemoryKiB", heapHalfBytes / 1024)
        report("nativePathReachedKernel", true)
    }

    @Test
    fun `M-1 墙钟实测与联合预算量级`() {
        // 默认配置（本仓 `KdbxHeader.createDefault`：M=64 MiB / I=2 / P=2）
        val defaultNanos = deriveNanos(memoryBytes = 64 * MIB, iterations = 2L, parallelism = 2)
        report("default64MiB_I2_P2_seconds", nanosToSeconds(defaultNanos))

        // 重载配置：M = min(堆/2, 256 MiB)、I=8 —— 代表「用户偏执参数」量级
        val heavyMemory = minOf(heapHalfBytes, 256 * MIB)
        val heavyNanos = deriveNanos(memoryBytes = heavyMemory, iterations = 8L, parallelism = 2)
        report("heavyMiB", heavyMemory / MIB)
        report("heavyI8Seconds", nanosToSeconds(heavyNanos))

        // 实测吞吐（字节·轮 / 秒）：单位工作量 = M(字节) × I(轮)
        val defaultWork = (64 * MIB).toDouble() * 2.0
        val throughput = defaultWork / (defaultNanos / 1_000_000_000.0)
        report("throughputByteRoundsPerSecond", String.format("%.3e", throughput))

        // 联合预算上界（`KdbxKdfParameterCodec.ARGON2_MAX_TOTAL_WORK = 2^40`）的墙钟量级：
        // **按实测吞吐换算**（非独立推算；已明确标注）。
        val budget = Math.pow(2.0, 40.0)
        val worstSeconds = budget / throughput
        report("extrapolated_worstCaseSeconds_at2pow40", String.format("%.0f", worstSeconds))
        report("extrapolated_worstCaseHours_at2pow40", String.format("%.2f", worstSeconds / 3600.0))

        // 题面更正：条目原文的「I = 2^24, M = 堆/2」已不是合法 Header（联合预算拒绝）。
        val jointRejected = runCatching {
            KdbxKdfParameterCodec.validateArgon2Bounds(heapHalfBytes, 1L shl 24, 2, VERSION_13)
        }.exceptionOrNull()
        report("originalPremise_i2pow24_mHeapHalf_rejected", jointRejected is KdbxCorruptFileException)
        assertTrue(
            "I=2^24 与 M=堆/2 的组合必须被联合预算拒绝——条目原文的题面已失效（AC① 于 §48 落地后）",
            jointRejected is KdbxCorruptFileException
        )

        // 本机**有效内存上界**：静态封顶是 4 GiB，但动态堆门槛（M ≤ maxHeap/2）才是真机上的实际约束。
        // 实测（本机 maxHeap=192 MiB ⇒ 96 MiB）：512 MiB × 20 这类「偏执但合法」的配置在本机
        // **会被堆门槛拒绝**——这纠正了「静态上界即实际可达上界」的推定（ISSUE-P2-49 的
        // 「真机实际 M 上界 ≈128–256 MiB」由此获得实测值：本机 = 96 MiB）。
        val aboveHeapGate = runCatching {
            KdbxKdfParameterCodec.validateArgon2Bounds(512 * MIB, 20L, 2, VERSION_13)
        }.exceptionOrNull()
        report("benchmarkSuggestedUpperBoundRejectedByHeapGate", aboveHeapGate is KdbxCorruptFileException)
        report("effectiveMemoryCapMiB_onThisDevice", heapHalfBytes / MIB)
        assertTrue(
            "M=512 MiB 在本机必须被动态堆门槛拒绝（maxHeap=${maxHeapBytes / MIB} MiB）",
            aboveHeapGate is KdbxCorruptFileException
        )

        // 联合预算边界内的最坏合法配置（M 取本机堆门槛上界、I 取预算允许的最大值）
        // —— 其墙钟由上面 report 的换算值给出（约数小时量级），本用例**不实跑**。
        val maxIterationsAtHeapHalf = (budget / heapHalfBytes).toLong()
        report("maxIterationsWithinBudget_atHeapHalf", maxIterationsAtHeapHalf)
        KdbxKdfParameterCodec.validateArgon2Bounds(
            heapHalfBytes,
            maxIterationsAtHeapHalf,
            2,
            VERSION_13
        )
        report("worstCaseWithinBudget_acceptedByCodec", true)

        assertEquals("默认配置耗时应为正数", true, defaultNanos > 0)
    }

    private companion object {
        const val TAG = "KdfWallClock"
        const val MIB = 1024L * 1024L
        const val VERSION_13 = 0x13
    }
}
