package com.keepasskey.database.file

import com.keepasskey.crypto.kdf.KdfParameters

/**
 * 工作因子（KDF 参数）**低于本应用建库默认强度**的单个维度（ISSUE-P2-87）。
 *
 * @property baselineValue 本应用建库默认（[KdbxHeader.createDefault]）在该维度上的取值
 * @property actualValue 该库实际声明的取值
 */
data class KdbxKdfWeakness(
    val dimension: KdbxKdfStrengthDimension,
    val baselineValue: Long,
    val actualValue: Long
)

/**
 * 工作因子维度（[KdbxKdfWeakness.dimension] 的取值域）。
 *
 * 逐个维度独立表达，**不合并成「工作量乘积」**：乘积口径会漏掉「某维度低于基线、
 * 另一维度高于基线」的组合（例：`M = 2× 基线 且 I = 1` 时乘积仍不低于基线，
 * 但迭代维度确实低于本应用建库默认强度）。故判据必须逐维可比。
 */
enum class KdbxKdfStrengthDimension {
    /** Argon2 内存（字节） */
    ARGON2_MEMORY_BYTES,

    /** Argon2 迭代轮数 */
    ARGON2_ITERATIONS,

    /** AES-KDF 轮数 */
    AES_KDF_ROUNDS
}

/**
 * 工作因子评估结果（ISSUE-P2-87）。
 *
 * [weaknesses] 为空即**完全达到本应用建库默认强度**（不告警的唯一判据）；
 * 非空时逐个元素说明「哪个维度低于基线、基线是多少、实际是多少」。
 */
data class KdbxKdfStrengthAssessment(val weaknesses: List<KdbxKdfWeakness>) {

    /** 是否低于本应用建库默认强度（true 即需要非阻断提示） */
    val isBelowBaseline: Boolean get() = weaknesses.isNotEmpty()

    companion object {
        /** 完全达到基线：评估结果的唯一「无需提示」取值 */
        val AT_OR_ABOVE_BASELINE = KdbxKdfStrengthAssessment(emptyList())
    }
}

/**
 * 「工作因子是否低于本应用建库默认强度」的纯函数判据（ISSUE-P2-87）。
 *
 * ### 这是在回答什么、不是回答什么
 *
 * 本判据**只**回答「该库的 KDF 工作因子是否低于本应用建库默认强度」，
 * **不回答**「该库是否安全 / 是否已被攻破」——低于基线并不意味着库已被攻破，
 * 只意味着它以弱于本应用默认强度的参数保护。故消费方（文案 / 日志）**只能**表述为
 * 「低于本应用建库默认强度」，不得写成「不安全」「已被攻破」。
 *
 * ### 非阻断
 *
 * 本判据是**只读评估**：既不拒绝打开、也不修改任何 KDF 参数。
 * 读取路径接受官方允许范围内的低工作因子并**逐字保留**（保存只刷新盐 / 种子，
 * 见 `KdbxFile.save`），故本评估只用于向用户如实提示现状。
 *
 * ### 基线来源：本应用自身的建库默认（不硬编码数字）
 *
 * 基线逐次取自 [KdbxHeader.createDefault]（Argon2 分支 `ARGON2ID / P=2 / 64 MiB / I=2`；
 * AES 分支 `KdbxConstants.Kdf.DEFAULT_AES_KDF_ROUNDS`）。**刻意不在本文件复写
 * 64 MiB / 2 轮 / 600 万轮这些数字**：基线一旦在 `createDefault` 侧变更，
 * 判据自动跟随，不会漂移成「基线已升级、判据还在比老数字」的静默失配。
 *
 * ### 判据口径
 *
 * | KDF | 低于基线的条件 |
 * |-----|----------------|
 * | Argon2 | `memoryInBytes < 基线内存` **或** `iterations < 基线迭代` |
 * | AES-KDF | `rounds < 基线轮数` |
 *
 * 严格小于：**恰好等于基线不告警**（本应用自建库即取默认值，必须永不告警）。
 *
 * ### `parallelism` 刻意不作为判据（**不得「补上」**）
 *
 * Argon2 的 `p` 是路数 / 线程旋钮：在 `M × I` 不变时，它**不改变攻击者的总工作量**
 * （内存与时耗由 `M` 与 `I` 决定），只影响防守方的墙钟耗时（`p` 越大单次派生越快）。
 * 故 `p = 1` 的库**不得**因此告警——那是**误报**，而非「漏了 P」：
 * 官方 KeePass 允许 `P = 1`，本仓读取侧下界亦为 1（[KdbxKdfParameterCodec]，规范语义）。
 * 本文件**不提供** parallelism 维度，正是为了让「补 P 判据」在类型层面无从下手；
 * UI 若需要展示 `p`，应作为**如实回显**（非告警依据）另行处理。
 *
 * 与官方 `AreParametersWeak`（仅比 `I × M` 乘积）的差异见 [KdbxKdfStrengthDimension] 的说明。
 */
object KdbxKdfStrengthAssessor {

    /**
     * 本应用建库默认的 Argon2 工作因子。
     *
     * 惰性求值一次：`createDefault` 会生成随机盐 / 种子，本判据只关心它携带的**默认参数**，
     * 故缓存以避免每次评估都做一遍 `SecureRandom` 取值。
     */
    private val argon2Baseline: KdfParameters.Argon2 by lazy {
        // 类型安全：createDefault(useArgon2 = true) 必返回 Argon2 分支
        KdbxHeader.createDefault(useArgon2 = true).kdfParameters as KdfParameters.Argon2
    }

    /** 本应用建库默认的 AES-KDF 工作因子（轮数），来源同上 */
    private val aesBaseline: KdfParameters.Aes by lazy {
        KdbxHeader.createDefault(useArgon2 = false).kdfParameters as KdfParameters.Aes
    }

    /**
     * 评估 [params] 声明的工作因子是否低于本应用建库默认强度。
     *
     * 纯函数（无 IO、无状态变更、结果只依赖入参与本应用基线常量），可按需在任意线程调用。
     */
    fun assess(params: KdfParameters): KdbxKdfStrengthAssessment = when (params) {
        is KdfParameters.Argon2 -> assessArgon2(params)
        is KdfParameters.Aes -> assessAes(params)
    }

    private fun assessArgon2(params: KdfParameters.Argon2): KdbxKdfStrengthAssessment {
        val baseline = argon2Baseline
        val weaknesses = buildList {
            if (params.memoryInBytes < baseline.memoryInBytes) {
                add(
                    KdbxKdfWeakness(
                        dimension = KdbxKdfStrengthDimension.ARGON2_MEMORY_BYTES,
                        baselineValue = baseline.memoryInBytes,
                        actualValue = params.memoryInBytes
                    )
                )
            }
            if (params.iterations < baseline.iterations) {
                add(
                    KdbxKdfWeakness(
                        dimension = KdbxKdfStrengthDimension.ARGON2_ITERATIONS,
                        baselineValue = baseline.iterations,
                        actualValue = params.iterations
                    )
                )
            }
        }
        return if (weaknesses.isEmpty()) {
            KdbxKdfStrengthAssessment.AT_OR_ABOVE_BASELINE
        } else {
            KdbxKdfStrengthAssessment(weaknesses)
        }
    }

    private fun assessAes(params: KdfParameters.Aes): KdbxKdfStrengthAssessment {
        val baselineRounds = aesBaseline.rounds
        if (params.rounds >= baselineRounds) return KdbxKdfStrengthAssessment.AT_OR_ABOVE_BASELINE
        return KdbxKdfStrengthAssessment(
            listOf(
                KdbxKdfWeakness(
                    dimension = KdbxKdfStrengthDimension.AES_KDF_ROUNDS,
                    baselineValue = baselineRounds,
                    actualValue = params.rounds
                )
            )
        )
    }
}
