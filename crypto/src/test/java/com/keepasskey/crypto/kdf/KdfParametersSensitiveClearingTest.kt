package com.keepasskey.crypto.kdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * ISSUE-P2-60（审计 RUST-06）回归：KDF secret `K` 的显式擦除。
 *
 * 契约要点（详见 `KdfParameters` 类 KDoc）：
 * - `clearSensitive()` 对 Argon2 为「就地 fill(0) + 置 null」两步擦除——
 *   只 fill 不置 null 会让引擎把全零数组当作合法 secret 参与派生（静默错密钥）；
 * - 清零后引擎按「无 secret」跳过、序列化按「缺 K」不写出，均为可观测失效态；
 * - AES-KDF 无 secret 分量，`clearSensitive()` 为 no-op；
 * - `equals` / `hashCode` 刻意忽略 `K`（既有语义，防止以密钥差异参与比较）。
 */
class KdfParametersSensitiveClearingTest {

    @Test
    fun `argon2 clearSensitive 清零原数组并置 null`() {
        val secret = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32) { 0x42 },
            secretKey = secret
        )

        params.clearSensitive()

        assertNull("清零后 secretKey 必须为 null（引擎与序列化按缺 K 失效）", params.secretKey)
        assertArrayEquals(
            "原数组内容必须就地清零（浅拷贝共享者同步失效）",
            ByteArray(8),
            secret
        )
    }

    @Test
    fun `argon2 clearSensitive 不触碰 associatedData 与 salt`() {
        val assoc = byteArrayOf(9, 8, 7)
        val salt = ByteArray(32) { 0x11 }
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = salt,
            secretKey = byteArrayOf(1, 2, 3),
            associatedData = assoc
        )

        params.clearSensitive()

        assertNull(params.secretKey)
        assertArrayEquals("associatedData 非秘密（KDBX 语义），不得擦除", byteArrayOf(9, 8, 7), assoc)
        assertArrayEquals("salt 为公开参数，不得擦除", salt, params.salt)
    }

    @Test
    fun `argon2 重复 clearSensitive 幂等`() {
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32),
            secretKey = byteArrayOf(1)
        )
        params.clearSensitive()
        params.clearSensitive()
        assertNull(params.secretKey)
    }

    @Test
    fun `aes clearSensitive 为 no-op 且 seed 不受影响`() {
        val seed = ByteArray(32) { 0x5A }
        val params = KdfParameters.Aes(seed = seed, rounds = 100L)

        params.clearSensitive()

        assertSame("AES-KDF 无 secret 分量，seed 不得被动", seed, params.seed)
        assertEquals(100L, params.rounds)
    }

    @Test
    fun `equals 与 hashCode 刻意忽略 secretKey 差异`() {
        val base = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32) { 0x42 },
            iterations = 2L
        )
        val withSecret = base.copy(secretKey = byteArrayOf(1, 2, 3))

        assertEquals("K 不参与 equals（既有语义）", base, withSecret)
        assertEquals("K 不参与 hashCode（既有语义）", base.hashCode(), withSecret.hashCode())
        assertNotEquals("对照：非 secret 字段仍参与 equals", base, base.copy(iterations = 3L))
    }
}
