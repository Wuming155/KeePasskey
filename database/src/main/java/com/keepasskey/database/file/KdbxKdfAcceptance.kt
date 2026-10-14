package com.keepasskey.database.file

import com.keepasskey.crypto.kdf.KdfParameters

/**
 * `ISSUE-P1-595`：**KDF 可接受参数判据**（UI / 写侧 / 读侧共用的单一真相源）。
 *
 * 背景：设置页可选 256 MiB × 50 轮（= 12.5 GiB 字节·轮），而读侧联合预算上界为
 * `2^33 ≈ 8.6×10^9` 字节·轮 ⇒ 用户可写出「本应用自身读侧拒绝」的数据库（重新打开时在派生前
 * 即抛损坏文件异常）。根因是**接受域不统一**：UI 选项、写侧 `KdbxFile.save` 与读侧
 * `KdbxKdfParameterCodec` 三处各有一套隐式边界。
 *
 * 本对象把边界收敛为单一判据，三处共享：
 * - **读侧**：`KdbxKdfParameterCodec.validateArgon2Bounds` 委托 [argon2Rejection]；
 * - **写侧**：`KdbxFile.save` 在写任何目标字节 / 进行 KDF 之前调用 [requireAcceptableOrThrow]（fail-closed）；
 * - **UI / 设置控制器**：提交前调用 [memoryBytesFromMbOrNull] + [argon2Rejection] 拒绝非法组合。
 *
 * 判据取值与「规范 / 官方语义 vs 本仓封顶」的逐项对照见 `KdbxKdfParameterCodec` 的类 KDoc 表。
 * **不放宽任何既有防 DoS 门槛**（本判据即既有边界的原样抽取），也不保证任意低内存设备都能打开
 * 高预算库——设备预算（JVM 堆一半）作为**设备能力限制**参与裁决（见 [deviceMemoryBudgetBytes]），
 * 非 KDBX 格式标准上限。
 */
object KdbxKdfAcceptance {

    /** Argon2 内存下界：对齐官方 `Argon2Kdf.MinMemory`（8192 字节），属规范语义**不得上调**。 */
    const val ARGON2_MIN_MEMORY_BYTES = 8192L

    /** 每通道内存下界（8 × 1024）——`memory ≥ p × 本值`，与原生内核 `memoryKib ≥ 8×p` 同值。 */
    const val ARGON2_MIN_MEMORY_PER_LANE_BYTES = 8L * 1024

    /** Argon2 内存上界（4 GiB，本仓更严封顶，防恶意文件分配期 OOM）。 */
    const val ARGON2_MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

    /** Argon2 迭代上界（2^24，本仓更严封顶）。 */
    const val ARGON2_MAX_ITERATIONS = 1L shl 24

    /** Argon2 并行度上界（64，本仓更严封顶）。 */
    const val ARGON2_MAX_PARALLELISM = 64

    /** Argon2 盐长下界：对齐官方 `MinSalt = 8`。 */
    const val ARGON2_MIN_SALT_BYTES = 8

    /** Argon2 盐长上界：官方 `MaxSalt = 0x3FFFFFFF`（实际受变体字典值长度上限间接约束）。 */
    const val ARGON2_MAX_SALT_BYTES = 0x3FFFFFFF

    /** AES-KDF 轮数上界（2^28，本仓封顶）。 */
    const val AES_KDF_MAX_ROUNDS = 1L shl 28

    /** AES-KDF 种子定长（规范 Byte[32]）。 */
    const val AES_KDF_SEED_BYTES = 32

    /**
     * Argon2 工作量联合预算（`I × M` 上界，单位「字节·轮」）：`ISSUE-P2-49` / 审计 F-12。
     * `2^33` 由真机实测速率锚定（详见 `KdbxKdfParameterCodec` 类 KDoc）。
     */
    const val ARGON2_MAX_TOTAL_WORK = 1L shl 33

    /** MiB → 字节换算基数。 */
    const val BYTES_PER_MIB = 1024L * 1024L

    /**
     * MiB → 字节；**溢出即返回 null**（不得静默回绕成负数 / 小值后再判范围，`ISSUE-P1-595` AC③）。
     */
    fun memoryBytesFromMbOrNull(memoryMb: Long): Long? {
        if (memoryMb < 0) return null
        if (memoryMb > Long.MAX_VALUE / BYTES_PER_MIB) return null
        return memoryMb * BYTES_PER_MIB
    }

    /**
     * 本设备可用内存预算（JVM 堆一半）。作为 [argon2Rejection] 的 `deviceBudgetBytes` 默认值——
     * 判「本进程能否安全派生」，属设备能力限制，**非** KDBX 格式标准上限。
     */
    fun deviceMemoryBudgetBytes(): Long = Runtime.getRuntime().maxMemory() / 2

    /**
     * Argon2 参数接受域判据：返回 `null` = 可接受；否则返回**用户可读的拒绝原因**。
     *
     * 判定次序与读侧逐项一致（逐项 → 交叉 → 版本 → 联合预算 → 设备预算）；
     * 联合预算以**除法**判定规避乘法溢出（`memoryInBytes ≥ 8192 > 0` 已由首项保证，除数安全）。
     *
     * [deviceBudgetBytes] 可注入，供确定性测试固定堆预算。
     */
    fun argon2Rejection(
        memoryInBytes: Long,
        iterations: Long,
        parallelism: Int,
        version: Int,
        deviceBudgetBytes: Long = deviceMemoryBudgetBytes()
    ): String? {
        if (memoryInBytes < ARGON2_MIN_MEMORY_BYTES || memoryInBytes > ARGON2_MAX_MEMORY_BYTES) {
            return "Argon2 内存参数越界: $memoryInBytes 字节（允许 $ARGON2_MIN_MEMORY_BYTES ~ $ARGON2_MAX_MEMORY_BYTES）"
        }
        if (iterations < 1 || iterations > ARGON2_MAX_ITERATIONS) {
            return "Argon2 迭代参数越界: $iterations（允许 1 ~ $ARGON2_MAX_ITERATIONS）"
        }
        if (parallelism < 1 || parallelism > ARGON2_MAX_PARALLELISM) {
            return "Argon2 并行度越界: $parallelism（允许 1 ~ $ARGON2_MAX_PARALLELISM）"
        }
        if (memoryInBytes < parallelism.toLong() * ARGON2_MIN_MEMORY_PER_LANE_BYTES) {
            return "Argon2 内存参数低于每通道下界: $memoryInBytes 字节 < 8 × $parallelism × 1024（memory 须 ≥ 8 × parallelism × 1024）"
        }
        if (version != KdfParameters.Argon2.ARGON2_VERSION_10 && version != KdfParameters.Argon2.ARGON2_VERSION_13) {
            // V 由 UInt32 窄化到 Int，高位非零时为负数；按无符号 32 位渲染，避免 "0x-1" 这类不可读消息
            return "不支持的 Argon2 版本: 0x${(version.toLong() and 0xFFFFFFFFL).toString(16)}"
        }
        if (iterations > ARGON2_MAX_TOTAL_WORK / memoryInBytes) {
            return "Argon2 工作量（迭代 × 内存）越界: $iterations × $memoryInBytes 超过上界 $ARGON2_MAX_TOTAL_WORK 字节·轮"
        }
        if (memoryInBytes > deviceBudgetBytes) {
            return "Argon2 内存参数超出本设备可用内存上限"
        }
        return null
    }

    /** Argon2 盐长接受域：返回 `null` = 可接受。 */
    fun argon2SaltRejection(saltLength: Int): String? =
        if (saltLength < ARGON2_MIN_SALT_BYTES || saltLength > ARGON2_MAX_SALT_BYTES) {
            "Argon2 盐长度越界: $saltLength 字节（允许 $ARGON2_MIN_SALT_BYTES ~ $ARGON2_MAX_SALT_BYTES）"
        } else {
            null
        }

    /** AES-KDF 轮数接受域：返回 `null` = 可接受。 */
    fun aesKdfRejection(rounds: Long): String? =
        if (rounds < 1 || rounds > AES_KDF_MAX_ROUNDS) {
            "AES-KDF 轮数越界: $rounds（允许 1 ~ $AES_KDF_MAX_ROUNDS）"
        } else {
            null
        }

    /** AES-KDF 种子定长接受域：返回 `null` = 可接受。 */
    fun aesKdfSeedRejection(seedLength: Int): String? =
        if (seedLength != AES_KDF_SEED_BYTES) {
            "AES-KDF 种子长度非法: $seedLength 字节（规范固定 $AES_KDF_SEED_BYTES 字节）"
        } else {
            null
        }

    /**
     * 写前接受域校验（fail-closed）：不可接受即抛 [IllegalArgumentException]（由会话层归一为
     * `KdbxResult.Failure`），阻止绕过 UI 的调用者写出本应用读侧会拒绝的产物。
     * 调用点须在**写任何目标字节 / 进行 KDF 之前**（`KdbxFile.save`）。
     */
    fun requireAcceptableOrThrow(params: KdfParameters) {
        val rejection = when (params) {
            is KdfParameters.Argon2 -> argon2Rejection(
                memoryInBytes = params.memoryInBytes,
                iterations = params.iterations,
                parallelism = params.parallelism,
                version = params.version
            ) ?: argon2SaltRejection(params.salt.size)
            is KdfParameters.Aes -> aesKdfRejection(params.rounds)
                ?: aesKdfSeedRejection(params.seed.size)
        }
        if (rejection != null) throw IllegalArgumentException("KDF 参数不可接受，拒绝写出: $rejection")
    }
}
