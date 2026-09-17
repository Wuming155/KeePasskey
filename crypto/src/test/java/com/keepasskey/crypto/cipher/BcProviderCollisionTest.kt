package com.keepasskey.crypto.cipher

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.Provider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P2-92 回归用例（宿主侧）：**模拟真机的「注册表 "BC" 被剥离版抢占」环境**。
 *
 * 真机事实（2026-09-17 Redmi 4X 实测）：Android 平台自带剥离版 BC provider（注册名同为 `"BC"`，
 * 无 `ChaCha7539` / `Twofish`），旧实现按 `Security.getProvider("BC")` 取 provider 必然
 * `NoSuchAlgorithmException`。本用例在宿主注册表注入一个同名的「空服务」假 BC 以复现该环境，
 * 断言：
 * 1. 注册表中的 `"BC"` 确实取不到 `ChaCha7539`（环境有效的负向对照）；
 * 2. [ChaCha20CipherEngine.bouncyCastleProvider] 返回的**不是**注册表条目（解耦断言）；
 * 3. ChaCha20 生产引擎在该环境下完整往返（修复前此处必然失败）；
 * 4. Twofish 的 JCE 解析路径（[NativeTwofish] 探活对照与 [TwofishCipherEngine] 兜底共用）可用。
 *
 * 敏感数据：全部为公开合成测试字节，仍按铁律用后清零。
 */
class BcProviderCollisionTest {

    @Test
    fun 注册表BC被剥离版抢占时_引擎仍取用完整BC() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val data = ByteArray(1024) { (it * 31 + 7).toByte() }
        val nonce = ByteArray(12) { (it * 3 + 1).toByte() }

        val previous = Security.getProvider("BC")
        Security.removeProvider("BC")
        val stripped = object : Provider("BC", 1.0, "test: 模拟平台剥离版 BC（无轻量算法）") {}
        Security.addProvider(stripped)
        try {
            // ---- 负向对照：环境确实复现「注册表 BC 无 ChaCha7539」----
            try {
                Cipher.getInstance("ChaCha7539", Security.getProvider("BC"))
                fail("环境构造失败：注册表假 BC 竟提供 ChaCha7539，本用例失去模拟意义")
            } catch (e: Exception) {
                assertTrue(
                    "预期 NoSuchAlgorithmException，实际：$e",
                    e is java.security.NoSuchAlgorithmException
                )
            }

            // ---- 解耦断言：解析结果不得是注册表条目 ----
            val resolved = ChaCha20CipherEngine.bouncyCastleProvider()
            assertNotSame("bouncyCastleProvider() 不得返回注册表中被抢占的 \"BC\"", stripped, resolved)

            // ---- ChaCha20 生产引擎完整往返（修复前必挂）----
            val engine = ChaCha20CipherEngine()
            val cipherBytes = engine.encrypt(key, nonce, data)
            try {
                val plain = engine.decrypt(key, nonce, cipherBytes)
                try {
                    assertArrayEquals("ChaCha20 往返必须还原明文", data, plain)
                } finally {
                    plain.fill(0)
                }
            } finally {
                cipherBytes.fill(0)
            }

            // ---- Twofish 解析路径（NativeTwofish 探活 / Twofish 兜底共用）----
            val block = ByteArray(16) { (it * 13 + 5).toByte() }
            val iv = ByteArray(16) { (it * 11 + 2).toByte() }
            val twofish = Cipher.getInstance("Twofish/CBC/NoPadding", resolved)
            twofish.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
            val out = twofish.doFinal(block)
            try {
                assertTrue("Twofish 单分组 CBC 变换须产出等长分组", out.size == 16)
            } finally {
                out.fill(0)
            }
        } finally {
            Security.removeProvider("BC")
            previous?.let { Security.addProvider(it) }
            key.fill(0); data.fill(0); nonce.fill(0)
        }
    }
}
