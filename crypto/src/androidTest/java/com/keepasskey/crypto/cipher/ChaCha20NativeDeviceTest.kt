package com.keepasskey.crypto.cipher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-P3-153 / §145 设备侧验证（真机 / instrumented）：
 * ChaCha20 原生内核在**真实 Android 运行时**可用，且引擎整块 / 流路径往返正确。
 *
 * 背景：ChaCha20 的 BC 纯 Java 实现真机吞吐仅 2.6~2.7 MB/s（实测记录 §2.1），
 * 本批下沉 Rust 内核（实测 ≈118 MB/s，≈44×）；本用例钉死真机上的功能正确性。
 */
@RunWith(AndroidJUnit4::class)
class ChaCha20NativeDeviceTest {

    @Test
    fun 原生内核探活_真机必须可用() {
        assertTrue(
            "真机上 NativeChaCha20 探活必须为 true（RFC 8439 KAT 自测；false = 原生库未加载或内核异常）",
            NativeChaCha20.available
        )
    }

    @Test
    fun RFC8439官方向量_真机复现() {
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
            assertArrayEquals("RFC 8439 §2.4.2 官方向量必须在真机逐字节复现", expected, out)
        } finally {
            expected.fill(0)
            out.fill(0)
        }
    }

    @Test
    fun 生产引擎整块与流路径_真机往返() {
        val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
        val nonce = ByteArray(12) { ((it * 7 + 1) and 0xFF).toByte() }
        val engine = ChaCha20CipherEngine()
        val plain = ByteArray(100_000) { ((it * 89 + 17) and 0xFF).toByte() }
        try {
            // 整块路径
            val bulk = engine.encrypt(key, nonce, plain)
            try {
                val restored = engine.decrypt(key, nonce, bulk)
                try {
                    assertArrayEquals("整块路径往返必须还原明文", plain, restored)
                } finally {
                    restored.fill(0)
                }
            } finally {
                bulk.fill(0)
            }

            // 流路径（跨 64 KiB 分块边界）
            val cipherOut = java.io.ByteArrayOutputStream()
            engine.createEncryptingStream(cipherOut, key, nonce).use { it.write(plain) }
            val cipherBytes = cipherOut.toByteArray()
            val plainOut = java.io.ByteArrayOutputStream()
            engine.createDecryptingStream(java.io.ByteArrayInputStream(cipherBytes), key, nonce).use {
                it.copyTo(plainOut)
            }
            val streamRestored = plainOut.toByteArray()
            try {
                assertArrayEquals("流路径往返必须还原明文", plain, streamRestored)
            } finally {
                streamRestored.fill(0)
                cipherBytes.fill(0)
            }
        } finally {
            key.fill(0)
            nonce.fill(0)
            plain.fill(0)
        }
    }
}
