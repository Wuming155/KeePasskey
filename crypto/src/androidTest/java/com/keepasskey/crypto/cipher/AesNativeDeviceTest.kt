package com.keepasskey.crypto.cipher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P3-155 追问 / §147 设备侧验证（真机 / instrumented）：
 * AES-256-CBC 原生内核在**真实 Android 运行时**可用，与平台 JCE 互操作，且引擎整块 / 流路径往返正确。
 *
 * 背景：§147 将 AES-CBC 统一到 Rust 内核（性能判据见
 * `docs/records/真机吞吐实测记录_2026-09-17.md` §8.1 / §8.3：现代设备额外 20~39 ms / 10 MiB，属无感区；
 * 取向为**跨平台统一与审计一致性**，非性能）。本用例钉死真机上的功能正确性与跨实现互操作。
 */
@RunWith(AndroidJUnit4::class)
class AesNativeDeviceTest {

    /** NIST CBC-AES256 官方向量（SP 800-38A F.2.5 / F.2.6）；与 `aes_cbc_tests.rs` 同源。 */
    private val nistKey = "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4".hexToByteArray()
    private val nistIv = "000102030405060708090a0b0c0d0e0f".hexToByteArray()
    private val nistPlain = (
        "6bc1bee22e409f96e93d7e117393172a" +
            "ae2d8a571e03ac9c9eb76fac45af8e51" +
            "30c81c46a35ce411e5fbc1191a0a52ef" +
            "f69f2445df4f9b17ad2b417be66c3710"
        ).hexToByteArray()
    private val nistCipher = (
        "f58c4c04d6e5f1ba779eabfb5f7bfbd6" +
            "9cfc4e967edb808d679f777bc6702c7d" +
            "39f23369a9d9bacfa530e26304231461" +
            "b2eb05e2c39be9fcda6c19078c6a9d1b"
        ).hexToByteArray()

    @Test
    fun 原生内核探活_真机必须可用() {
        assertTrue(
            "真机上 NativeAes 探活必须为 true（NIST 官方向量双向自测；false = 原生库未加载或内核异常）",
            NativeAes.available
        )
    }

    @Test
    fun NIST_CBC_AES256官方向量_真机复现() {
        val encIv = nistIv.copyOf()
        val cipherText = NativeAes.cbcEncryptBlocks(nistKey, encIv, nistPlain)
            ?: throw AssertionError("NIST 官方向量加密返回 null")
        try {
            assertArrayEquals("NIST 官方向量密文必须在真机逐字节复现", nistCipher, cipherText)
            assertArrayEquals(
                "加密后 IV 必须原地演进为最后一组密文",
                nistCipher.copyOfRange(nistCipher.size - NativeAes.BLOCK_SIZE, nistCipher.size),
                encIv
            )
        } finally {
            cipherText.fill(0)
            encIv.fill(0)
        }

        val decIv = nistIv.copyOf()
        val plain = NativeAes.cbcDecryptBlocks(nistKey, decIv, nistCipher)
            ?: throw AssertionError("NIST 官方向量解密返回 null")
        try {
            assertArrayEquals("NIST 官方向量明文必须在真机逐字节复现", nistPlain, plain)
        } finally {
            plain.fill(0)
            decIv.fill(0)
        }
    }

    @Test
    fun 生产引擎整块与流路径_真机往返() {
        val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
        val iv = ByteArray(16) { ((it * 7 + 1) and 0xFF).toByte() }
        val ivExpected = iv.copyOf()
        val engine = AesCipherEngine()
        val plain = ByteArray(100_000) { ((it * 89 + 17) and 0xFF).toByte() }
        try {
            // 整块路径
            val bulk = engine.encrypt(key, iv, plain)
            try {
                assertArrayEquals("整型路径不得改写调用方 IV", ivExpected, iv)
                val restored = engine.decrypt(key, iv, bulk)
                try {
                    assertArrayEquals("整块路径往返必须还原明文", plain, restored)
                } finally {
                    restored.fill(0)
                }
            } finally {
                bulk.fill(0)
            }

            // 流路径（跨 64 KiB 分块边界）
            val cipherOut = ByteArrayOutputStream()
            engine.createEncryptingStream(cipherOut, key, iv).use { it.write(plain) }
            val cipherBytes = cipherOut.toByteArray()
            assertArrayEquals("流式路径不得改写调用方 IV", ivExpected, iv)
            val plainOut = ByteArrayOutputStream()
            engine.createDecryptingStream(ByteArrayInputStream(cipherBytes), key, iv).use {
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
            iv.fill(0)
            ivExpected.fill(0)
            plain.fill(0)
        }
    }

    @Test
    fun 平台JCE密文_真机由原生路径解密_互操作() {
        // 跨实现互操作：平台 JCE 加密（存量库 / 其它客户端产物）必须能被原生路径读回。
        val key = ByteArray(32) { ((it * 17 + 5) and 0xFF).toByte() }
        val iv = ByteArray(16) { ((it * 3 + 9) and 0xFF).toByte() }
        val plain = ByteArray(70_000) { ((it * 23 + 7) and 0xFF).toByte() }
        try {
            val cipherText = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }.doFinal(plain)
            val restored = AesCipherEngine().decrypt(key, iv, cipherText)
            try {
                assertEquals("JCE 密文长度应为明文 + 一个填充块", plain.size + 16, cipherText.size)
                assertArrayEquals("原生路径必须解回 JCE 密文", plain, restored)
            } finally {
                restored.fill(0)
            }
        } finally {
            key.fill(0)
            iv.fill(0)
            plain.fill(0)
        }
    }
}
