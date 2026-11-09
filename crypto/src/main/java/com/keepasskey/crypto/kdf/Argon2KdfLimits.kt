package com.keepasskey.crypto.kdf

/**
 * Argon2 参数的**基础接受域**（内存、迭代、并行度与每通道内存下界）。
 *
 * ENG-20261028-16：这组边界此前在 [Argon2KdfEngine] 与 database 的 `KdbxKdfAcceptance`
 * 各留一份同值副本，靠跨模块用例双向锁定防漂移。模块依赖单向（database → crypto），
 * 故唯一定义放在本层，`KdbxKdfAcceptance` 直接引用同一组常量与判据。
 *
 * 本对象**不**取代各消费方仍独有的裁决：
 * - 数据库层（`KdbxKdfAcceptance`）：`I × M` 联合工作量、Argon2 版本取值集、设备 JVM 堆预算；
 * - 原生内核（`crypto/src/main/rust`）：JNI 入参的有符号闸门与 `memoryKib ≥ 8 × parallelism`
 *   自身有效性下界——这是内核最后一道防线，必须独立保留，不因本对象收敛而删除。
 *
 * 取值均为本仓 fail-closed 封顶，且宽于一切合法用户配置（官方默认 64 MiB / t=2 / p=2，
 * 本仓 `KdfBenchmark` 自荐上限 512 MiB × 20）。
 */
object Argon2KdfLimits {

    /** Argon2 内存下界：对齐官方 `Argon2Kdf.MinMemory`（8192 字节），属规范语义**不得上调**。 */
    const val MIN_MEMORY_BYTES = 8192L

    /** 每通道内存下界（8 × 1024）——`memory ≥ p × 本值`，与原生内核 `memoryKib ≥ 8×p` 同值。 */
    const val MIN_MEMORY_PER_LANE_BYTES = 8L * 1024

    /** Argon2 内存上界（4 GiB，本仓更严封顶，防恶意文件分配期 OOM）。 */
    const val MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

    /** Argon2 迭代上界（2^24，本仓更严封顶）。 */
    const val MAX_ITERATIONS = 1L shl 24

    /** Argon2 并行度上界（64，本仓更严封顶）。 */
    const val MAX_PARALLELISM = 64

    /**
     * 基础接受域判据：逐项范围 **且** 每通道下界（`memory ≥ p × 8 KiB`）。
     *
     * 这是 [Argon2KdfEngine.transform] 的公共接受域，越界即拒绝派生；不再是「是否走原生」的
     * 路由条件——回退只用于实现可用性差异（原生不可用 / 版本不支持 / AD 超原生上限）。
     */
    fun isWithinBounds(memoryInBytes: Long, iterations: Long, parallelism: Int): Boolean =
        outOfBoundsReason(memoryInBytes, iterations, parallelism) == null

    /**
     * 越界原因（`null` = 界内）。
     *
     * 文案即数据库层与设置页展示的拒绝原因，两处共用同一份表述，避免同义不同文的漂移。
     * 判定次序固定为「内存 → 迭代 → 并行度 → 每通道交叉」，供调用方按首项复述。
     */
    fun outOfBoundsReason(memoryInBytes: Long, iterations: Long, parallelism: Int): String? = when {
        memoryInBytes < MIN_MEMORY_BYTES || memoryInBytes > MAX_MEMORY_BYTES ->
            "Argon2 内存参数越界: $memoryInBytes 字节（允许 $MIN_MEMORY_BYTES ~ $MAX_MEMORY_BYTES）"
        iterations < 1 || iterations > MAX_ITERATIONS ->
            "Argon2 迭代参数越界: $iterations（允许 1 ~ $MAX_ITERATIONS）"
        parallelism < 1 || parallelism > MAX_PARALLELISM ->
            "Argon2 并行度越界: $parallelism（允许 1 ~ $MAX_PARALLELISM）"
        memoryInBytes < parallelism.toLong() * MIN_MEMORY_PER_LANE_BYTES ->
            "Argon2 内存参数低于每通道下界: $memoryInBytes 字节 < 8 × $parallelism × 1024（memory 须 ≥ 8 × parallelism × 1024）"
        else -> null
    }
}
