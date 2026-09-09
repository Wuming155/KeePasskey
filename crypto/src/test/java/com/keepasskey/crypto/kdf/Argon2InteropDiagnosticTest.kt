package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.kdf.KdfParameters.Argon2.Argon2Type
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume
import org.junit.Test
import java.security.MessageDigest

/**
 * Argon2 互操作已知答案测试（KAT）：以安全的合成测试口令与自造的密钥文件密钥 / 盐为输入，
 * 用独立参考实现（libargon2，经 pykeepass 同款 argon2-cffi）预计算的期望值，验证本工程
 * Bouncy Castle Argon2 派生与 KDBX 复合密钥组装公式的一致性：
 * composite = SHA-256(SHA-256(password) ‖ keyFileKey)，transformedKey = Argon2d(composite, salt, ...)。
 *
 * 凭据泄露整改（P0-2）：原诊断向量中的真实主密码 / 真实密钥文件密钥 / 真实库盐已全部替换为
 * 合成测试向量；期望值由独立参考实现按同一公式离线计算，保持跨实现互操作校验语义不变。
 *
 * 参考基准（argon2-cffi 25.1.0 / libargon2，Argon2d t=3 m=8192KiB p=2 v=19）：
 * composite sha256 = cf8a8915ff0670c9171c416970665fc9401614131daf1cf64ae938bde15a813a
 * transformedKey sha256 = 4423de6810bd7f08812cac7b9d40c96b19939c3de38ac8c3d60e407e9759a9ce
 */
class Argon2InteropDiagnosticTest {

    @Test
    fun `transformed key matches libargon2 reference for synthetic vector`() {
        assertMatchesLibArgon2Reference(
            Argon2KdfEngine(Argon2Type.ARGON2D).transform(compositeKey(), referenceParams())
        )
    }

    /**
     * 同一 libargon2 参考基准，但**强制走原生 Rust 内核**（PoC Batch 4 互操作验证）。
     *
     * 宿主库可用（Gradle `cargoHostBuild` 产出宿主 cdylib）且 version∈{0x10,0x13}、无 AD 时，
     * `Argon2KdfEngine` 必定选原生分支，故本用例等价于「Rust 内核 ≡ libargon2（C 参考实现）」
     * 的运行时互操作验证 —— 真实 KeePass/KeePassXC 生成库不设 KDF 的 `A` 字段，此参数形态
     * 即真实解锁路径。宿主库缺失时经 `Assume` 跳过（与 `LiveSyncServersTest` 同策略）。
     */
    @Test
    fun `native rust kernel reproduces libargon2 reference`() {
        Assume.assumeTrue(
            "宿主 Rust 原生库不可用（未安装 cargo / 构建失败），跳过原生互操作验证",
            NativeArgon2.available
        )
        assertMatchesLibArgon2Reference(
            Argon2KdfEngine(Argon2Type.ARGON2D).transform(compositeKey(), referenceParams())
        )
    }

    /** 复合密钥 = SHA256(SHA256(pwd) ‖ keyfileKey32)，keyfileKey 为自造的 32 字节测试十六进制串。 */
    private fun compositeKey(): ByteArray {
        val password = "TestMasterPassword!2026#Secure"
        val passwordHash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val keyFileKey = "CAFEBABEDEADBEEF00112233445566778899AABBCCDDEEFF0123456789ABCDEF"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(passwordHash + keyFileKey)
    }

    /** 自造的 32 字节测试盐与轻量参数（保持测试快速可重复执行）。 */
    private fun referenceParams(): KdfParameters.Argon2 = KdfParameters.Argon2(
        type = Argon2Type.ARGON2D,
        salt = "FEEDFACE0BADC0DE00112233445566778899AABBCCDDEEFFDEADBEEFCAFEBABE"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        parallelism = 2,
        memoryInBytes = 8L * 1024 * 1024,
        iterations = 3L,
        version = 19
    )

    private fun assertMatchesLibArgon2Reference(transformed: ByteArray) {
        val composite = compositeKey()
        assertArrayEquals(
            "composite 与 libargon2/pykeepass 参考基准不一致",
            "cf8a8915ff0670c9171c416970665fc9401614131daf1cf64ae938bde15a813a".chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray(),
            composite
        )

        // 派生成功且非平凡：32 字节输出、非全零、确与输入不同（真实完成 KDF 变换而非直通）
        assertEquals(32, transformed.size)
        assertFalse(transformed.all { it == 0.toByte() })
        assertFalse(composite.contentEquals(transformed))

        // 一致性：同一输入与参数重复派生必须逐字节一致（确定性）
        assertArrayEquals(
            "同参数重复派生结果应一致",
            transformed,
            Argon2KdfEngine(Argon2Type.ARGON2D).transform(composite, referenceParams())
        )

        // 与独立参考实现（libargon2）预计算基准比对
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(transformed).joinToString("") { "%02x".format(it) }
        assertEquals(
            "transformedKey 与 libargon2/pykeepass 参考基准不一致",
            "4423de6810bd7f08812cac7b9d40c96b19939c3de38ac8c3d60e407e9759a9ce",
            hex
        )
    }
}
