package com.keepasskey.database.file

import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.exception.KdbxCorruptFileException
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Argon2 KDF 盐长边界回归（ISSUE-P3-78）。
 *
 * 背景：官方 `Argon2Kdf.cs:57-58` 定义 `MinSalt = 8` / `MaxSalt = 0x3FFFFFFF`，
 * `:143-144` 越界即抛 `ArgumentOutOfRangeException`；本仓解码侧原先**完全不校验** `S` 长度，
 * 会接受官方拒绝的退化盐（如 0 字节）——属接受域不一致，非可利用缺陷。
 *
 * 三条断言口径：
 * 1. 官方下界 `len = 8` **必须通过**；
 * 2. `len = 7` 与 `len = 0`（退化盐）**必须拒绝**；
 * 3. 本仓自身写出的 **32 字节**盐必须通过（防误拒，见 `KdbxHeader` 的默认盐长）。
 *
 * 走「写侧序列化 → 读侧反序列化」的真实字节流，而非直接调校验函数，确保该边界
 * 确实接在解码路径上（防止「加了校验却没接线」的假绿）。
 */
class Argon2KdfSaltBoundsTest {

    private fun roundTripSaltSize(saltLength: Int): KdfParameters {
        val vd = KdbxKdfParameterCodec.serialize(
            KdfParameters.Argon2(
                type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
                salt = ByteArray(saltLength),
                parallelism = 2,
                memoryInBytes = 64L * 1024 * 1024,
                iterations = 2L,
                version = KdfParameters.Argon2.ARGON2_VERSION_13
            )
        )
        val out = ByteArrayOutputStream()
        vd.serialize(out)
        return KdbxKdfParameterCodec.deserialize(out.toByteArray())
    }

    private fun decodedSaltSize(saltLength: Int): Int =
        (roundTripSaltSize(saltLength) as KdfParameters.Argon2).salt.size

    @Test
    fun `官方下界 8 字节盐通过`() {
        assertEquals(8, decodedSaltSize(8))
    }

    @Test
    fun `7 字节盐按官方语义拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTripSaltSize(7) }
    }

    @Test
    fun `0 字节退化盐拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTripSaltSize(0) }
    }

    @Test
    fun `本仓自身写出的 32 字节盐必须通过（防误拒）`() {
        assertEquals(32, decodedSaltSize(32))
    }
}
