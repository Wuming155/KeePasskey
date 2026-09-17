package com.keepasskey.database.file

import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-87 回归：工作因子是否**低于本应用建库默认强度**的纯函数判据。
 *
 * 用例的基线**一律取自 `KdbxHeader.createDefault`**（不硬编码 64 MiB / 2 / 600 万），
 * 与实现同源——基线若在 `createDefault` 侧变更，本文件自动跟随，不会因复制字面量而漂移。
 *
 * 覆盖点（含两个刻意的防误报 / 防漏维度边界）：
 * 1. Argon2 仅内存不达标；2. 仅迭代不达标；3. 两者同时不达标；4. **内存达标（甚至高于基线）
 * 但迭代极低且 `I×M` 乘积不低于基线**——锁「逐维判定、不可只比乘积」；5. `parallelism = 1`
 * 但 `M / I` 达标 ⇒ **不告警**；6. AES 轮数不达标 / 达标；7. Argon2 完全达标（含本应用自建库默认）。
 */
class KdbxKdfStrengthAssessorTest {

    /** 本应用建库默认的 Argon2 工作因子（基线的唯一来源） */
    private val argon2Baseline: KdfParameters.Argon2 =
        KdbxHeader.createDefault(useArgon2 = true).kdfParameters as KdfParameters.Argon2

    /** 本应用建库默认的 AES-KDF 轮数（基线的唯一来源） */
    private val aesBaselineRounds: Long =
        (KdbxHeader.createDefault(useArgon2 = false).kdfParameters as KdfParameters.Aes).rounds

    private fun argon2(
        memoryInBytes: Long = argon2Baseline.memoryInBytes,
        iterations: Long = argon2Baseline.iterations,
        parallelism: Int = argon2Baseline.parallelism
    ) = KdfParameters.Argon2(
        type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
        salt = ByteArray(32),
        parallelism = parallelism,
        memoryInBytes = memoryInBytes,
        iterations = iterations
    )

    private fun aes(rounds: Long) = KdfParameters.Aes(seed = ByteArray(32), rounds = rounds)

    // ================= Argon2 =================

    @Test
    fun `Argon2 内存低于基线时仅报内存维度并带上基线与实际值`() {
        val actualMemory = argon2Baseline.memoryInBytes / 4
        val assessment = KdbxKdfStrengthAssessor.assess(argon2(memoryInBytes = actualMemory))

        assertTrue("内存低于基线必须告警", assessment.isBelowBaseline)
        assertEquals(1, assessment.weaknesses.size)
        val weakness = assessment.weaknesses.single()
        assertEquals(KdbxKdfStrengthDimension.ARGON2_MEMORY_BYTES, weakness.dimension)
        assertEquals(argon2Baseline.memoryInBytes, weakness.baselineValue)
        assertEquals(actualMemory, weakness.actualValue)
    }

    @Test
    fun `Argon2 内存达标而迭代低于基线时仅报迭代维度`() {
        val assessment = KdbxKdfStrengthAssessor.assess(
            argon2(memoryInBytes = argon2Baseline.memoryInBytes, iterations = 1L)
        )

        assertTrue("迭代低于基线必须告警", assessment.isBelowBaseline)
        val weakness = assessment.weaknesses.single()
        assertEquals(KdbxKdfStrengthDimension.ARGON2_ITERATIONS, weakness.dimension)
        assertEquals(argon2Baseline.iterations, weakness.baselineValue)
        assertEquals(1L, weakness.actualValue)
        // 内存维度达标 → 不得出现在结论里（否则文案会指错维度）
        assertFalse(
            "内存达标时不得报内存维度",
            assessment.weaknesses.any { it.dimension == KdbxKdfStrengthDimension.ARGON2_MEMORY_BYTES }
        )
    }

    @Test
    fun `Argon2 内存与迭代同时低于基线时两个维度都报`() {
        val assessment = KdbxKdfStrengthAssessor.assess(
            argon2(memoryInBytes = argon2Baseline.memoryInBytes / 8, iterations = 1L)
        )

        assertTrue(assessment.isBelowBaseline)
        assertEquals(
            listOf(
                KdbxKdfStrengthDimension.ARGON2_MEMORY_BYTES,
                KdbxKdfStrengthDimension.ARGON2_ITERATIONS
            ),
            assessment.weaknesses.map { it.dimension }
        )
    }

    /**
     * 漏维度边界：内存**高于**基线、仅迭代极低（`I = 1`）——
     * 此时 `I × M` 乘积仍不低于基线乘积，「只比乘积」的口径会放行，逐维判定必须拦下。
     * 本用例同时锁定「不得改成乘积口径」与「不得因内存高而豁免迭代维度」。
     */
    @Test
    fun `Argon2 内存高于基线但迭代极低时仍报迭代维度（不可只比乘积）`() {
        val memory = argon2Baseline.memoryInBytes * 2
        val params = argon2(memoryInBytes = memory, iterations = 1L)

        assertTrue(
            "前提：本用例的内存 × 迭代乘积不低于基线乘积",
            memory * 1L >= argon2Baseline.memoryInBytes * argon2Baseline.iterations
        )

        val assessment = KdbxKdfStrengthAssessor.assess(params)
        assertTrue("迭代低于基线必须告警，与内存是否达标无关", assessment.isBelowBaseline)
        assertEquals(
            listOf(KdbxKdfStrengthDimension.ARGON2_ITERATIONS),
            assessment.weaknesses.map { it.dimension }
        )
    }

    /**
     * 防误报回归：`parallelism = 1` 而 `M / I` 均达标 ⇒ **不得告警**。
     * `p` 只是路数 / 线程旋钮，在 `M × I` 不变时不改变攻击者工作量（理由见
     * `KdbxKdfStrengthAssessor` KDoc），故它既不是维度、也不得影响任何维度的判定。
     */
    @Test
    fun `Argon2 并行度为 1 而内存与迭代达标时不告警`() {
        val assessment = KdbxKdfStrengthAssessor.assess(
            argon2(
                memoryInBytes = argon2Baseline.memoryInBytes,
                iterations = argon2Baseline.iterations,
                parallelism = 1
            )
        )

        assertFalse("P=1 不得触发告警（防误报）", assessment.isBelowBaseline)
        assertTrue(assessment.weaknesses.isEmpty())
    }

    /** 边界：严格小于——恰好等于基线不告警；略高于基线亦不告警。 */
    @Test
    fun `Argon2 恰好等于基线或高于基线时不告警`() {
        assertFalse(
            KdbxKdfStrengthAssessor.assess(
                argon2(
                    memoryInBytes = argon2Baseline.memoryInBytes,
                    iterations = argon2Baseline.iterations
                )
            ).isBelowBaseline
        )
        assertFalse(
            KdbxKdfStrengthAssessor.assess(
                argon2(
                    memoryInBytes = argon2Baseline.memoryInBytes * 2,
                    iterations = argon2Baseline.iterations * 4
                )
            ).isBelowBaseline
        )
    }

    /**
     * 本应用**自建库默认**必然达标：基线与判据同源（均取 `KdbxHeader.createDefault`），
     * 基线一旦漂移本用例即失败，从而保证「自建库永不告警」。
     */
    @Test
    fun `本应用建库默认的 Argon2 头部永不告警`() {
        val defaultParams = KdbxHeader.createDefault(useArgon2 = true).kdfParameters
        assertEquals(KdbxKdfStrengthAssessment.AT_OR_ABOVE_BASELINE, KdbxKdfStrengthAssessor.assess(defaultParams))
    }

    // ================= AES-KDF =================

    @Test
    fun `AES 轮数低于基线时告警并带上基线与实际值`() {
        val assessment = KdbxKdfStrengthAssessor.assess(aes(rounds = 1L))

        assertTrue("AES-KDF 轮数低于基线必须告警", assessment.isBelowBaseline)
        val weakness = assessment.weaknesses.single()
        assertEquals(KdbxKdfStrengthDimension.AES_KDF_ROUNDS, weakness.dimension)
        assertEquals(aesBaselineRounds, weakness.baselineValue)
        assertEquals(1L, weakness.actualValue)
    }

    @Test
    fun `AES 轮数达标或高于基线时不告警`() {
        assertFalse(
            "恰好等于基线不得告警",
            KdbxKdfStrengthAssessor.assess(aes(rounds = aesBaselineRounds)).isBelowBaseline
        )
        assertFalse(
            KdbxKdfStrengthAssessor.assess(aes(rounds = aesBaselineRounds * 2)).isBelowBaseline
        )
        // 本应用自建库默认（useArgon2=false）同样永不告警
        assertEquals(
            KdbxKdfStrengthAssessment.AT_OR_ABOVE_BASELINE,
            KdbxKdfStrengthAssessor.assess(KdbxHeader.createDefault(useArgon2 = false).kdfParameters)
        )
    }
}
