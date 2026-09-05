package com.keepasskey.crypto.kdf

import java.util.Arrays

/**
 * Argon2 推荐参数数据类
 */
data class Argon2Recommendation(
    /**
     * 推荐分配的内存字节数 (Memory in Bytes)
     */
    val memoryBytes: Long,

    /**
     * 推荐迭代轮数 (Iterations)
     */
    val iterations: Long,

    /**
     * 推荐并行线程数 (Parallelism / Lanes)
     */
    val parallelism: Int
)

/**
 * KDF (密钥派生函数) 性能基准测试与设备资源适配引擎。
 * 遵循纯 Kotlin 规范（零 Android 平台依赖）：
 * 1. AES-KDF：通过小样本轮数实测设备吞吐率，线性外推达到目标耗时所需轮数，对齐 KeePass 官方 600 万轮量级；
 * 2. Argon2：结合设备可用内存、CPU 核心数与目标耗时，推荐满足安全防爆破门槛的最佳内存/迭代/并行组合；
 * 3. 内存门槛安全检验：严控 Argon2 内存占用不超过可用内存 60%，防范移动端系统 OOM / LMK 杀进程。
 */
object KdfBenchmark {

    // AES-KDF 轮数常量（对齐 KeePass 2.x 官方事实标准）
    const val DEFAULT_AES_ROUNDS: Long = 6_000_000L
    const val MIN_AES_ROUNDS: Long = 10_000L
    const val MAX_AES_ROUNDS: Long = 10_000_000L
    const val AES_SAMPLE_ROUNDS: Long = 50_000L

    // 默认目标耗时 (毫秒)
    const val DEFAULT_TARGET_TIME_MILLIS: Long = 1000L

    // Argon2 内存边界常量
    const val MIN_ARGON2_MEMORY_BYTES: Long = 8L * 1024 * 1024      // 8 MiB
    const val MAX_ARGON2_MEMORY_BYTES: Long = 512L * 1024 * 1024    // 512 MiB
    const val MAX_MEMORY_RATIO: Double = 0.60                       // 设备可用内存的 60%

    // Argon2 迭代与并行度边界常量
    const val MIN_ARGON2_ITERATIONS: Long = 1L
    const val MAX_ARGON2_ITERATIONS: Long = 20L
    const val MIN_ARGON2_PARALLELISM: Int = 1
    const val MAX_ARGON2_PARALLELISM: Int = 4

    /**
     * 对 AES-KDF 执行基准测试并外推轮数
     *
     * @param targetTimeMillis 预期解密耗时 (毫秒，默认 1000ms)
     * @param sampler 可注入的基准耗时采样器，用于测试环境返回固定毫秒数，若为 null 则在本地真实测速
     * @return 夹紧在 [MIN_AES_ROUNDS, MAX_AES_ROUNDS] (10,000 ~ 10,000,000) 之间的推荐轮数
     */
    fun benchmarkAesKdf(
        targetTimeMillis: Long = DEFAULT_TARGET_TIME_MILLIS,
        sampler: (() -> Long)? = null
    ): Long {
        val elapsedMillis = sampler?.invoke() ?: measureRealAesKdfMillis(AES_SAMPLE_ROUNDS)
        val safeElapsed = elapsedMillis.coerceAtLeast(1L)

        val estimatedRounds = (targetTimeMillis * AES_SAMPLE_ROUNDS) / safeElapsed
        return estimatedRounds.coerceIn(MIN_AES_ROUNDS, MAX_AES_ROUNDS)
    }

    /**
     * 对 Argon2 执行基准测试并提供符合硬件承载力的参数推荐
     *
     * @param availableMemoryBytes 设备当前可用物理内存字节数
     * @param targetTimeMillis 预期解密耗时 (毫秒，默认 1000ms)
     * @param sampler 可注入的单次基准采样耗时 (毫秒)，用于测试环境注入确定性耗时
     * @return 包含 memoryBytes, iterations, parallelism 的 [Argon2Recommendation]
     */
    fun benchmarkArgon2(
        availableMemoryBytes: Long,
        targetTimeMillis: Long = DEFAULT_TARGET_TIME_MILLIS,
        sampler: (() -> Long)? = null
    ): Argon2Recommendation {
        // 1. 内存夹紧: [8MiB, 512MiB] 且 <= availableMemoryBytes * 60%
        val maxAllowedMemory = (availableMemoryBytes * MAX_MEMORY_RATIO).toLong()
        val memoryBytes = maxAllowedMemory.coerceIn(MIN_ARGON2_MEMORY_BYTES, MAX_ARGON2_MEMORY_BYTES)

        // 2. 并行度夹紧: [1, 4]
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val parallelism = cpuCores.coerceIn(MIN_ARGON2_PARALLELISM, MAX_ARGON2_PARALLELISM)

        // 3. 迭代次数外推与夹紧: [1, 20]
        val elapsedMillis = sampler?.invoke() ?: measureRealArgon2Millis(memoryBytes, parallelism)
        val safeElapsed = elapsedMillis.coerceAtLeast(1L)

        val estimatedIterations = (targetTimeMillis / safeElapsed).coerceIn(MIN_ARGON2_ITERATIONS, MAX_ARGON2_ITERATIONS)

        return Argon2Recommendation(
            memoryBytes = memoryBytes,
            iterations = estimatedIterations,
            parallelism = parallelism
        )
    }

    /**
     * 校验指定内存大小是否在设备允许的安全门槛内
     *
     * @param memoryBytes 待校验的 Argon2 内存占用字节
     * @param availableMemoryBytes 设备当前可用物理内存字节
     * @return 若内存满足 [8MiB, 512MiB] 且不超过可用内存的 60% 则返回 true
     */
    fun isMemorySufficient(memoryBytes: Long, availableMemoryBytes: Long): Boolean {
        if (availableMemoryBytes <= 0) return false
        val maxAllowed = (availableMemoryBytes * MAX_MEMORY_RATIO).toLong()
        return memoryBytes in MIN_ARGON2_MEMORY_BYTES..MAX_ARGON2_MEMORY_BYTES && memoryBytes <= maxAllowed
    }

    // ================= 私有真实测速实现 =================

    private fun measureRealAesKdfMillis(rounds: Long): Long {
        val engine = AesKdfEngine()
        val dummyKey = ByteArray(32) { 0x42.toByte() }
        val dummySeed = ByteArray(32) { 0x24.toByte() }
        val params = KdfParameters.Aes(seed = dummySeed, rounds = rounds)

        val start = System.nanoTime()
        try {
            val result = engine.transform(dummyKey, params)
            Arrays.fill(result, 0.toByte())
        } finally {
            Arrays.fill(dummyKey, 0.toByte())
            Arrays.fill(dummySeed, 0.toByte())
        }
        val end = System.nanoTime()
        return (end - start) / 1_000_000L
    }

    private fun measureRealArgon2Millis(memoryBytes: Long, parallelism: Int): Long {
        val engine = Argon2KdfEngine(KdfParameters.Argon2.Argon2Type.ARGON2ID)
        val dummyKey = ByteArray(32) { 0x55.toByte() }
        val dummySalt = ByteArray(32) { 0xAA.toByte() }
        // 采样 1 轮以推导单轮耗时
        val sampleParams = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = dummySalt,
            parallelism = parallelism,
            memoryInBytes = memoryBytes,
            iterations = 1L
        )

        val start = System.nanoTime()
        try {
            val result = engine.transform(dummyKey, sampleParams)
            Arrays.fill(result, 0.toByte())
        } finally {
            Arrays.fill(dummyKey, 0.toByte())
            Arrays.fill(dummySalt, 0.toByte())
        }
        val end = System.nanoTime()
        return (end - start) / 1_000_000L
    }
}
