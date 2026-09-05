package com.keepasskey.crypto.kdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KDF 基准测试、参数外推与内存安全门槛单元测试
 */
class KdfBenchmarkTest {

    @Test
    fun `测试 AES-KDF 轮数外推计算与注入采样`() {
        // 采样 50ms, 目标 1000ms -> (1000 * 50_000) / 50 = 1,000,000 轮
        val rounds1 = KdfBenchmark.benchmarkAesKdf(targetTimeMillis = 1000L, sampler = { 50L })
        assertEquals(1_000_000L, rounds1)

        // 采样 25ms, 目标 500ms -> (500 * 50_000) / 25 = 1,000,000 轮
        val rounds2 = KdfBenchmark.benchmarkAesKdf(targetTimeMillis = 500L, sampler = { 25L })
        assertEquals(1_000_000L, rounds2)
    }

    @Test
    fun `测试 AES-KDF 轮数上限与下限边界夹紧`() {
        // 极快设备 (耗时 1ms) -> 外推 50,000,000 轮，必须夹紧至上限 10,000,000 轮
        val fastResult = KdfBenchmark.benchmarkAesKdf(targetTimeMillis = 1000L, sampler = { 1L })
        assertEquals(KdfBenchmark.MAX_AES_ROUNDS, fastResult)

        // 极慢设备 (耗时 10,000ms) -> 外推 5,000 轮，必须夹紧至下限 10,000 轮
        val slowResult = KdfBenchmark.benchmarkAesKdf(targetTimeMillis = 1000L, sampler = { 10_000L })
        assertEquals(KdfBenchmark.MIN_AES_ROUNDS, slowResult)
    }

    @Test
    fun `测试 AES-KDF 真实运行采样测试`() {
        val rounds = KdfBenchmark.benchmarkAesKdf(targetTimeMillis = 100L)
        assertTrue(rounds in KdfBenchmark.MIN_AES_ROUNDS..KdfBenchmark.MAX_AES_ROUNDS)
    }

    @Test
    fun `测试 Argon2 内存建议基于可用内存 60 百分比且夹紧在 8MiB 到 512MiB`() {
        // 1. 超大物理内存 (8 GB 可用) -> 60% 为 4.8 GB，必须夹紧至 512 MiB
        val bigMem = 8L * 1024 * 1024 * 1024
        val recBig = KdfBenchmark.benchmarkArgon2(availableMemoryBytes = bigMem, sampler = { 200L })
        assertEquals(512L * 1024 * 1024, recBig.memoryBytes)

        // 2. 极小物理内存 (10 MB 可用) -> 60% 为 6 MB，低于 8 MiB，必须夹紧至 8 MiB
        val smallMem = 10L * 1024 * 1024
        val recSmall = KdfBenchmark.benchmarkArgon2(availableMemoryBytes = smallMem, sampler = { 200L })
        assertEquals(8L * 1024 * 1024, recSmall.memoryBytes)

        // 3. 中等物理内存 (100 MB 可用) -> 60% 为 60 MB
        val midMem = 100L * 1024 * 1024
        val recMid = KdfBenchmark.benchmarkArgon2(availableMemoryBytes = midMem, sampler = { 200L })
        assertEquals(60L * 1024 * 1024, recMid.memoryBytes)
    }

    @Test
    fun `测试 Argon2 迭代次数与并行度夹紧`() {
        val mem = 128L * 1024 * 1024

        // 单次耗时 250ms, 目标 1000ms -> 1000 / 250 = 4 轮
        val rec1 = KdfBenchmark.benchmarkArgon2(availableMemoryBytes = mem, targetTimeMillis = 1000L, sampler = { 250L })
        assertEquals(4L, rec1.iterations)

        // 极端耗时 (3000ms) -> 1000 / 3000 = 0 -> 夹紧至下限 1 轮
        val recSlow = KdfBenchmark.benchmarkArgon2(availableMemoryBytes = mem, targetTimeMillis = 1000L, sampler = { 3000L })
        assertEquals(KdfBenchmark.MIN_ARGON2_ITERATIONS, recSlow.iterations)

        // 极快耗时 (10ms) -> 1000 / 10 = 100 -> 夹紧至上限 20 轮
        val recFast = KdfBenchmark.benchmarkArgon2(availableMemoryBytes = mem, targetTimeMillis = 1000L, sampler = { 10L })
        assertEquals(KdfBenchmark.MAX_ARGON2_ITERATIONS, recFast.iterations)

        // 并行度必须在 [1, 4] 之间
        assertTrue(rec1.parallelism in KdfBenchmark.MIN_ARGON2_PARALLELISM..KdfBenchmark.MAX_ARGON2_PARALLELISM)
    }

    @Test
    fun `测试 isMemorySufficient 内存安全门槛校验`() {
        val available = 100L * 1024 * 1024 // 100 MiB 可用，上限 60 MiB

        // 60 MiB -> 刚好等于 60%，合法
        assertTrue(KdfBenchmark.isMemorySufficient(60L * 1024 * 1024, available))

        // 32 MiB -> 合法
        assertTrue(KdfBenchmark.isMemorySufficient(32L * 1024 * 1024, available))

        // 61 MiB -> 超出 60%，非法
        assertFalse(KdfBenchmark.isMemorySufficient(61L * 1024 * 1024, available))

        // 4 MiB -> 低于 8 MiB 下限，非法
        assertFalse(KdfBenchmark.isMemorySufficient(4L * 1024 * 1024, available))

        // 600 MiB -> 超过 512 MiB 上限，非法
        assertFalse(KdfBenchmark.isMemorySufficient(600L * 1024 * 1024, 2L * 1024 * 1024 * 1024))

        // 可用内存异常非正数
        assertFalse(KdfBenchmark.isMemorySufficient(16L * 1024 * 1024, 0L))
        assertFalse(KdfBenchmark.isMemorySufficient(16L * 1024 * 1024, -1L))
    }
}
