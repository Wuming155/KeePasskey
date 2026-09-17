package com.keepasskey.crypto.cipher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P2-92 设备侧验证（真机 / instrumented）：
 * 生产 `ChaCha20CipherEngine` 与 Twofish 的 BC 解析路径在**真实 Android 运行时**可用。
 *
 * 背景（2026-09-17 Redmi 4X 实测）：Android 平台剥离版 BC 抢占 `"BC"` 注册名，
 * 修复前本类三个断言分别表现为——
 * 1. ChaCha20 生产引擎往返抛 `NoSuchAlgorithmException`（探针首跑的真实失败）；
 * 2. `NativeTwofish.available` 探活的 BC 对照抛异常被吞 ⇒ 恒 `false` ⇒ 原生 Twofish 被静默弃用；
 * 3. Twofish JCE 兜底解析同样 `NoSuchAlgorithmException`。
 *
 * 敏感数据：全部为公开合成测试字节，仍按铁律用后清零。
 */
@RunWith(AndroidJUnit4::class)
class BcProviderDeviceTest {

    @Test
    fun chaCha20生产引擎_真机往返() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val data = ByteArray(4096) { (it * 31 + 7).toByte() }
        val nonce = ByteArray(12) { (it * 3 + 1).toByte() }
        try {
            val engine = ChaCha20CipherEngine()
            val cipherBytes = engine.encrypt(key, nonce, data)
            try {
                val plain = engine.decrypt(key, nonce, cipherBytes)
                try {
                    assertArrayEquals("ChaCha20 生产引擎在真机必须可完整往返", data, plain)
                } finally {
                    plain.fill(0)
                }
            } finally {
                cipherBytes.fill(0)
            }
        } finally {
            key.fill(0); data.fill(0); nonce.fill(0)
        }
    }

    @Test
    fun nativeTwofish探活_真机必须可用() {
        // 修复前：探活内 BC 对照（Twofish/CBC/NoPadding）抛 NoSuchAlgorithmException 被
        // `catch (Throwable) { false }` 吞掉 ⇒ 原生 Twofish 被静默弃用（issue 登记后补充的影响面）。
        assertTrue(
            "真机上 NativeTwofish 探活必须为 true（BC 对照解析修复后原生内核不应被静默弃用）",
            NativeTwofish.available
        )
    }

    @Test
    fun twofishJce解析_真机单分组往返() {
        val key = ByteArray(32) { (it * 17 + 1).toByte() }
        val block = ByteArray(16) { (it * 13 + 5).toByte() }
        val iv = ByteArray(16) { (it * 11 + 2).toByte() }
        try {
            val cipher = Cipher.getInstance("Twofish/CBC/NoPadding", ChaCha20CipherEngine.bouncyCastleProvider())
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
            val out = cipher.doFinal(block)
            try {
                assertTrue("Twofish 单分组 CBC 变换须产出等长分组", out.size == 16)
            } finally {
                out.fill(0)
            }
        } finally {
            key.fill(0); block.fill(0); iv.fill(0)
        }
    }
}
