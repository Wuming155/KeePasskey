package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-143（第四轮复核 NEW-B01-6）回归：`KdbxHeader` / `KdfParameters.Argon2`
 * 相等性是**秘密不敏感**语义——**防未来接入**的语义锁定。
 *
 * ## 为什么需要本用例
 *
 * 缺口不在 `KdfParameters.Argon2` 侧（忽略 `secretKey` 是**有意设计**：它是 `var`，
 * `clearSensitive()` 就地置 null，纳入哈希会破坏清零后的哈希稳定性），而在于该
 * 「秘密不敏感相等性」**经 `KdbxHeader` 对外暴露** —— [KdbxDatabase] 是 `data class`
 * 且持有 [KdbxHeader]，而 `core.database` 恰是 `MutableStateFlow`（`SessionCore.kt:21`，
 * StateFlow 按 `equals` 合并）⇒ 未来若出现「仅 `secretKey` / `associatedData` 变化」的
 * 赋值或集合去重路径，相应赋值 / 去重会被**静默吞掉**。
 *
 * **当前不可达**（故条目为 P3/INFO 而非漏洞）：`secretKey` 仅由反序列化赋值
 * （`KdbxKdfParameterCodec.deserialize`），且保存会刷新 KDF salt / masterSeed
 * （`KdbxFile.save`）⇒ 不存在「其余字段全等、仅 secret 不同」的生产赋值路径。
 *
 * ## 本用例锁定的契约（不得据本语义做裁决）
 *
 * 1. 两个**仅** `secretKey`（`K`）或 `associatedData`（`A`）不同的 `KdbxHeader`
 *    **被判相等**，且 `hashCode()` 相同——此为**刻意语义**，不是待修缺陷；
 * 2. `clearSensitive()` 就地清零 `secretKey` 后，**同一实例与等价实例的 `hashCode()` 稳定**
 *    （这正是 `secretKey` 不得纳入哈希的理由，也是本用例不得改变既有 `equals` 语义的原因）；
 * 3. 对照：非秘密字段（salt / iterations / 外层 masterSeed）确实参与比较——
 *    保证第 1、2 条不是「equals 退化为永真」造成的假绿。
 *
 * ## 禁用场景（可核对）
 *
 * ⚠ **禁止**把该相等性用于「凭据 / 秘密材料是否变化」一类裁决（如 `old != new`
 * 决定是否重建会话 / 重新派生 / 失效缓存），也不得据此对含 `KdbxHeader` 的集合去重。
 * **未来接入约束**：若确有「仅 secret 变化」的路径，须改为**显式变更标记**
 * （如会话级 `revision`）驱动裁决，**不得**依赖结构相等。
 */
class KdbxHeaderSecretInsensitiveEqualityTest {

    /** 构造 Argon2 头部；`secretKey` / `associatedData` / `salt` 可由调用方指定。 */
    private fun argon2Header(
        salt: ByteArray = ByteArray(SALT_SIZE) { SALT_FILL },
        iterations: Long = DEFAULT_ITERATIONS,
        secretKey: ByteArray? = null,
        associatedData: ByteArray? = null,
        masterSeed: ByteArray = ByteArray(MASTER_SEED_SIZE) { MASTER_SEED_FILL }
    ): KdbxHeader = KdbxHeader(
        cipherUuid = KdbxUuid(ByteArray(KdbxUuid.UUID_SIZE) { CIPHER_FILL }),
        masterSeed = masterSeed,
        encryptionIv = ByteArray(ENCRYPTION_IV_SIZE) { IV_FILL },
        kdfParameters = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = salt,
            iterations = iterations,
            secretKey = secretKey,
            associatedData = associatedData
        )
    )

    @Test
    fun `仅 KDF secretKey 不同的两个头部被判相等且哈希相同`() {
        val withSecret = argon2Header(secretKey = byteArrayOf(1, 2, 3, 4))
        val withoutSecret = argon2Header(secretKey = null)
        val otherSecret = argon2Header(secretKey = byteArrayOf(9, 9, 9, 9))

        assertEquals(
            "KDBX4 `K`（secretKey）刻意不参与相等性——本条锁定该语义（ISSUE-P3-143），" +
                "禁止据此判定「凭据是否变化」",
            withoutSecret,
            withSecret
        )
        assertEquals(
            "仅 `K` 不同的实例哈希必须相同（否则集合去重行为与 equals 不一致）",
            withoutSecret.hashCode(),
            withSecret.hashCode()
        )
        assertEquals("`K` 的内容差异同样不参与比较", withSecret, otherSecret)
        assertEquals(withSecret.hashCode(), otherSecret.hashCode())
    }

    @Test
    fun `仅 KDBX4 associatedData 不同的两个头部被判相等且哈希相同`() {
        val withData = argon2Header(associatedData = byteArrayOf(7, 7, 7))
        val withoutData = argon2Header(associatedData = null)
        val otherData = argon2Header(associatedData = byteArrayOf(0x0A, 0x0B))

        assertEquals("KDBX4 `A`（associatedData）刻意不参与相等性", withoutData, withData)
        assertEquals(
            "仅 `A` 不同的实例哈希必须相同（与差量 KDF 的既有语义一致）",
            withoutData.hashCode(),
            withData.hashCode()
        )
        assertEquals("`A` 的内容差异同样不参与比较", withData, otherData)
    }

    @Test
    fun `clearSensitive 前后 hashCode 稳定且与清零前的等价实例相等`() {
        val before = argon2Header(secretKey = byteArrayOf(3, 1, 4, 1, 5))
        val snapshotHash = before.hashCode()

        before.kdfParameters.clearSensitive()

        assertEquals(
            "清零后哈希必须与清零前一致——这正是 secretKey 不得纳入 hashCode 的理由" +
                "（纳入会让集合内对象在清零瞬间失联）",
            snapshotHash,
            before.hashCode()
        )
        assertEquals(
            "清零后仍与「同一 KDF 参数、无 secret」的实例相等",
            argon2Header(secretKey = null),
            before
        )
        assertEquals(argon2Header(secretKey = null).hashCode(), before.hashCode())
    }

    @Test
    fun `Argon2 层同样忽略 K 与 A 并保持清零后哈希稳定`() {
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(SALT_SIZE) { SALT_FILL },
            secretKey = byteArrayOf(1, 2, 3),
            associatedData = byteArrayOf(4, 5)
        )
        val noSecrets = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(SALT_SIZE) { SALT_FILL }
        )
        val beforeHash = params.hashCode()

        assertEquals("KdfParameters.Argon2 层同样刻意忽略 `K` / `A`", noSecrets, params)

        params.clearSensitive()

        assertNull("前置条件：clearSensitive 确实把 `K` 置 null", params.secretKey)
        assertEquals("清零后 hashCode 稳定", beforeHash, params.hashCode())
        assertEquals(noSecrets, params)
    }

    @Test
    fun `对照 非秘密字段差异仍被判不等`() {
        val base = argon2Header()
        val saltDiffers = argon2Header(salt = ByteArray(SALT_SIZE) { OTHER_SALT_FILL })
        val iterationsDiffer = argon2Header(iterations = DEFAULT_ITERATIONS + 1)
        val masterSeedDiffers = argon2Header(masterSeed = ByteArray(MASTER_SEED_SIZE) { OTHER_SEED_FILL })

        assertTrue("对照：salt 为公开参数，必须参与相等性", base != saltDiffers)
        assertNotEquals(
            "对照：iterations 必须参与相等性（否则相等性已退化为永真，本类其余断言即假绿）",
            base,
            iterationsDiffer
        )
        assertNotEquals("对照：外层 masterSeed 必须参与相等性", base, masterSeedDiffers)
        assertNotEquals(base.hashCode(), masterSeedDiffers.hashCode())
    }

    private companion object {
        /** 固定盐长度（Argon2 盐 32 字节） */
        const val SALT_SIZE = 32
        const val MASTER_SEED_SIZE = 32
        const val ENCRYPTION_IV_SIZE = 16

        /** 固定填充值——测试用虚构参数，非真实库参数（`const` 字面量直接满足 `ByteArray` 的 `Byte` lambda） */
        const val SALT_FILL: Byte = 0x42
        const val OTHER_SALT_FILL: Byte = 0x24
        const val MASTER_SEED_FILL: Byte = 0x11
        const val OTHER_SEED_FILL: Byte = 0x22
        const val IV_FILL: Byte = 0x33
        const val CIPHER_FILL: Byte = 0x44
        const val DEFAULT_ITERATIONS = 2L
    }
}
