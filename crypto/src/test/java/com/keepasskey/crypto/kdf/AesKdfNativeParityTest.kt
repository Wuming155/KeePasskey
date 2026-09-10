package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.exception.CryptoException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * AES-KDF 原生内核与 JCE 参考实现的**差分等价**用例（ISSUE-P3-34）。
 *
 * 验证分层（对齐 `NativeArgon2HostJniTest` 的既有范式）：
 * - Rust 侧 `cargo test` 覆盖纯函数与导出符号的静态签名；
 * - **本用例**在宿主 JVM 上（`crypto/build.gradle.kts` 的 `cargoHostBuild` 把宿主 cdylib 经
 *   `-Djava.library.path` 注入单测 JVM）真实走 JNI 通路，断言与 [AesKdfJce] 逐字节等价；
 * - 设备侧由 `crypto/src/androidTest` 承担（APK 内打包的 `.so`）。
 *
 * 降级语义：未安装 cargo / 宿主库构建失败时 `NativeAesKdf.available == false`，
 * 依赖原生的用例按 `Assume` 跳过（与 `LiveSyncServersTest` 同策略），**不阻断** `gradlew test`；
 * 但 JCE 参考实现自身的用例**永不跳过**。
 */
class AesKdfNativeParityTest {

    private fun key(seed: Int): ByteArray = ByteArray(32) { ((it * seed + 7) and 0xFF).toByte() }

    // ==================== 独立已知答案向量（不依赖本实现） ====================

    /**
     * 固定向量由 Python `cryptography` 的 AES-ECB 独立复算（2026-09-10），
     * 与 `crypto/src/main/rust/src/aes_kdf.rs` 的同名向量**同源同值**：
     * seed = 0x00..0x1f（AES 密钥），compositeKey = 0x20..0x3f（明文），rounds = 4。
     */
    @Test
    fun `JCE 参考实现命中独立复算的已知答案向量`() {
        val seed = ByteArray(32) { it.toByte() }
        val compositeKey = ByteArray(32) { (it + 32).toByte() }
        val out = AesKdfJce.transform(compositeKey, seed, 4)
        try {
            assertEquals(
                "f836155ae7cb1d120da836de66f478ec1dbf042d041d9ca50b49f400d398eff5",
                out.toHex()
            )
        } finally {
            out.fill(0)
        }
    }

    // ==================== 原生 ⇄ JCE 差分等价 ====================

    @Test
    fun `原生内核与 JCE 参考实现在多组参数下逐字节等价`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过差分用例", NativeAesKdf.available)

        val roundsList = listOf(1L, 2L, 7L, 1_000L, 50_000L)
        for (rounds in roundsList) {
            for (salt in 1..3) {
                val compositeKey = key(salt)
                val seed = key(salt + 10)
                val native = NativeAesKdf.derive(compositeKey, seed, rounds)
                val reference = AesKdfJce.transform(compositeKey, seed, rounds)
                try {
                    assertArrayEquals("rounds=$rounds salt=$salt 原生与 JCE 不一致", reference, native)
                } finally {
                    native.fill(0)
                    reference.fill(0)
                    compositeKey.fill(0)
                    seed.fill(0)
                }
            }
        }
    }

    /// 引擎级：原生可用时必须走原生（结果仍与 JCE 一致）。
    @Test
    fun `引擎输出与 JCE 参考实现一致`() {
        val engine = AesKdfEngine()
        val compositeKey = ByteArray(32) { (it * 3 + 1).toByte() }
        val seed = ByteArray(32) { (it * 5 + 2).toByte() }
        try {
            val actual = engine.transform(compositeKey, KdfParameters.Aes(seed = seed, rounds = 2_000L))
            val expected = AesKdfJce.transform(compositeKey, seed, 2_000L)
            try {
                assertArrayEquals(expected, actual)
            } finally {
                actual.fill(0)
                expected.fill(0)
            }
        } finally {
            compositeKey.fill(0)
            seed.fill(0)
        }
    }

    // ==================== 参数闸门 ====================

    @Test
    fun `原生派生对越界参数返回 null 而不抛异常`() {
        Assume.assumeTrue("原生内核不可用（未构建宿主库），跳过闸门用例", NativeAesKdf.available)
        val ok = ByteArray(32)
        // 长度非法
        assertEquals(null, NativeAesKdf.deriveKey(ByteArray(31), ok, 1L))
        assertEquals(null, NativeAesKdf.deriveKey(ok, ByteArray(31), 1L))
        // 轮数为 0 与负数（有符号闸门先行，负数不得经窄化绕过）
        assertEquals(null, NativeAesKdf.deriveKey(ok, ok, 0L))
        assertEquals(null, NativeAesKdf.deriveKey(ok, ok, -1L))
        // 合法下界
        assertNotNull(NativeAesKdf.deriveKey(ok, ok, 1L))
    }

    @Test
    fun `引擎对非法长度抛出 KdfException 而非静默放行`() {
        val engine = AesKdfEngine()
        val params = KdfParameters.Aes(seed = ByteArray(32), rounds = 1L)
        val failure = runCatching { engine.transform(ByteArray(31), params) }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException（require 契约）", failure is IllegalArgumentException)
    }

    @Test
    fun `参数类型不符抛出 KdfException`() {
        val engine = AesKdfEngine()
        val wrong = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32)
        )
        val failure = runCatching { engine.transform(ByteArray(32), wrong) }.exceptionOrNull()
        assertTrue("应抛 KdfException", failure is CryptoException.KdfException)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
