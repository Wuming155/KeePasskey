package com.keepasskey.database.file

import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.exception.KdbxCorruptFileException
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * AES-KDF 种子 `S` 定长回归（ISSUE-P3-267）。
 *
 * 规格定 AES-KDF 的 `S` 为 Byte[32]。整改前解码侧**完全不校验** `S` 长度：
 * - 恰 16 字节时 `SecretKeySpec(seed)` 静默按 AES-128 派生——官方客户端不会产出该文件，
 *   派生结果必错、表现为「主密码错误」且难排查（本类重点回归项）；
 * - 其它非法长度要到派生期才抛非类型化 JCE 异常，不走「文件损坏」通道。
 *
 * 断言口径：
 * 1. 规范定长 32 字节**必须通过**（防误拒本仓自身写出的库）；
 * 2. 0 / 16 / 31 / 33 字节**必须拒绝**，且在解析期抛 [KdbxCorruptFileException]。
 *
 * 走「写侧序列化 → 读侧反序列化」的真实字节流，而非直接调校验函数，确保该边界
 * 确实接在解码路径上（防止「加了校验却没接线」的假绿）。
 */
class AesKdfSeedBoundsTest {

    private fun roundTripSeedSize(seedLength: Int): KdfParameters {
        val vd = KdbxKdfParameterCodec.serialize(
            KdfParameters.Aes(seed = ByteArray(seedLength), rounds = 60_000L)
        )
        val out = ByteArrayOutputStream()
        vd.serialize(out)
        return KdbxKdfParameterCodec.deserialize(out.toByteArray())
    }

    private fun decodedSeedSize(seedLength: Int): Int =
        (roundTripSeedSize(seedLength) as KdfParameters.Aes).seed.size

    @Test
    fun `规范定长 32 字节种子通过（防误拒）`() {
        assertEquals(32, decodedSeedSize(32))
    }

    @Test
    fun `0 字节退化种子拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTripSeedSize(0) }
    }

    @Test
    fun `恰 16 字节不得静默按 AES-128 派生（回归项）`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTripSeedSize(16) }
    }

    @Test
    fun `31 字节种子拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTripSeedSize(31) }
    }

    @Test
    fun `33 字节种子拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) { roundTripSeedSize(33) }
    }
}
