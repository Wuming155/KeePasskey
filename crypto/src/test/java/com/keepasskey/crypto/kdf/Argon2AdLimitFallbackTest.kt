package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.kdf.KdfParameters.Argon2.Argon2Type
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 缓解回归锁（ISSUE-P2-14 · Rust 秘密飞地 PoC Batch 3）
 *
 * 背景：Rust Argon2 原生内核（RustCrypto argon2 0.6.0）的 `AssociatedData` 上限为 32B，
 * 而旧 C 内核 / BouncyCastle 接受任意长度 AD。为避免行为回退，[Argon2KdfEngine.transform]
 * 在 `associatedData.size > NATIVE_MAX_AD_LEN(32)` 时强制改走 BouncyCastle 兜底路径。
 *
 * 本测试锁定「兜底目的地（BC）能正确派生长 AD」这一前提：以 Batch 0 冻结向量
 * `argon2id_v13_ad64_probe`（64B AD）的完全相同输入驱动引擎，断言输出与该冻结期望逐字节一致。
 * 桌面 JVM 无 `.so`（NativeArgon2.available=false），故必然走 BC 路径——与守卫在真机上
 * 将 AD>32 路由到 BC 的目的地一致。守卫分支本身（native 可用 + AD>32）由 Batch 4 真机验证。
 */
class Argon2AdLimitFallbackTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `engine derives 64B associated-data via BC fallback matching frozen vector`() {
        // 取自 argon2-bc-vectors.json 的 argon2id_v13_ad64_probe（Batch 0 冻结）
        val password = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val salt = hex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
        val ad = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
        )
        val expected = hex("8530ffbabd2516a6cde793ac7d2bae848f18ef69774b19480fb7146d9e9ffb52")

        // 前置：AD 确实超过原生内核 32B 上限（守卫条件成立）
        assertTrue("测试前提：AD 应 > ${Argon2KdfEngine.NATIVE_MAX_AD_LEN}B", ad.size > Argon2KdfEngine.NATIVE_MAX_AD_LEN)
        assertEquals(64, ad.size)

        val params = KdfParameters.Argon2(
            type = Argon2Type.ARGON2ID,
            salt = salt,
            parallelism = 2,
            memoryInBytes = 256L * 1024, // 256 KiB，与冻结向量 memoryKib 对齐
            iterations = 2L,
            version = KdfParameters.Argon2.ARGON2_VERSION_13, // 0x13 = 19
            secretKey = null,
            associatedData = ad
        )

        val engine = Argon2KdfEngine(Argon2Type.ARGON2ID)
        val transformed = engine.transform(password, params)

        assertEquals(32, transformed.size)
        assertArrayEquals(
            "长 AD（64B）经 BC 兜底派生结果应与冻结向量逐字节一致（R2 缓解前提）",
            expected,
            transformed
        )
    }
}
