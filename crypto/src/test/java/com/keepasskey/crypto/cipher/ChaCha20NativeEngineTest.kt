package com.keepasskey.crypto.cipher

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P3-153 / §145：ChaCha20 引擎原生路径与 BC 兜底路径的**对拍**用例（宿主侧）。
 *
 * 口径对齐 `TwofishNativeParityTest`：契约是「原生路径与 BC 路径可观测行为一致」，
 * 对照对象是 BC `ChaCha7539`（完整 provider 实例，§143 解耦后的取用形态）的真实输出。
 * 原生不可用（宿主库未注入）时经 `Assume` 显式跳过——设备侧由
 * `ChaCha20NativeDeviceTest` 硬断言补位（跳过 ≠ 通过）。
 */
class ChaCha20NativeEngineTest {

    private val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
    private val nonce = ByteArray(12) { ((it * 7 + 1) and 0xFF).toByte() }
    private val engine = ChaCha20CipherEngine()

    private fun bcCipher(mode: Int): Cipher {
        val cipher = Cipher.getInstance("ChaCha7539", BouncyCastleProvider())
        cipher.init(mode, SecretKeySpec(key, "ChaCha7539"), IvParameterSpec(nonce))
        return cipher
    }

    private val lengths = listOf(0, 1, 15, 63, 64, 65, 4096, 65_535, 65_536, 65_537, 100_000)

    @Test
    fun `原生整块与BC doFinal逐字节一致`() {
        assumeTrue("宿主原生库未注入（Assume 跳过；设备侧用例硬断言补位）", NativeChaCha20.available)
        lengths.forEach { length ->
            val plain = ByteArray(length) { ((it * 89 + 17) and 0xFF).toByte() }
            val native = engine.encrypt(key, nonce, plain)
            val bc = bcCipher(Cipher.ENCRYPT_MODE).doFinal(plain)
            assertArrayEquals("整块加密必须与 BC 逐字节一致（length=$length）", bc, native)
            assertArrayEquals("解密必须还原明文（length=$length）", plain, engine.decrypt(key, nonce, native))
            native.fill(0)
            bc.fill(0)
        }
    }

    @Test
    fun `原生流与BC流逐字节一致且往返还原`() {
        assumeTrue("宿主原生库未注入（Assume 跳过；设备侧用例硬断言补位）", NativeChaCha20.available)
        lengths.forEach { length ->
            val plain = ByteArray(length) { ((it * 71 + 29) and 0xFF).toByte() }

            // 引擎原生流加密 vs BC 流加密
            val nativeStreamOut = ByteArrayOutputStream()
            engine.createEncryptingStream(nativeStreamOut, key, nonce).use { it.write(plain) }
            val bcStreamOut = ByteArrayOutputStream()
            CipherOutputStream(bcStreamOut, bcCipher(Cipher.ENCRYPT_MODE)).use { it.write(plain) }
            assertArrayEquals(
                "流加密必须与 BC 流逐字节一致（length=$length）",
                bcStreamOut.toByteArray(),
                nativeStreamOut.toByteArray()
            )

            // 引擎原生流解密（对 BC 流密文）还原明文
            val roundTrip = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(bcStreamOut.toByteArray()), key, nonce).use {
                it.copyTo(roundTrip)
            }
            assertArrayEquals("流解密必须还原明文（length=$length）", plain, roundTrip.toByteArray())

            plain.fill(0)
        }
    }

    @Test
    fun `RFC8439官方向量经原生内核复现`() {
        assumeTrue("宿主原生库未注入（Assume 跳过；设备侧用例硬断言补位）", NativeChaCha20.available)
        // RFC 8439 §2.4.2（块计数器 1 = 字节偏移 64）
        val katKey = ByteArray(32) { it.toByte() }
        val katNonce = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0)
        val plain =
            ("Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it.").toByteArray(Charsets.US_ASCII)
        val expected = (
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d"
            ).hexToByteArray()
        val out = NativeChaCha20.applyKeystreamChecked(katKey, katNonce, 64, plain)
        try {
            assertArrayEquals("RFC 8439 §2.4.2 官方向量必须逐字节复现", expected, out)
        } finally {
            expected.fill(0)
            out.fill(0)
        }
    }
}
