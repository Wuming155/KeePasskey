package com.keepasskey.crypto.cipher

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CBC 流式包装的 **direct 直扣形态**框架用例（`ISSUE-P3-198`）。
 *
 * direct 形态的变换注入签名（[CbcDirectBlockTransform]）与 byte[] 形态完全解耦——宿主侧
 * 用「JCE CBC 链 + 堆内中转」的纯 JVM 实现注入同一签名，框架逻辑（分段对齐、扣留末分组、
 * 三类 fail-closed 错误语义、堆外交付与收尾双段次序）无需原生库即可覆盖；原生内核与 JNI
 * 边界的正确性由设备侧套件（`AesNativeDeviceTest` / 吞吐探针）承担。
 *
 * 对照逻辑：同 key/iv/载荷下，direct 形态与 byte[] 形态（既有注入路径）的**可观测行为
 * 必须逐字节一致**——两种形态共用同一套框架代码，本类锁定 direct 分支没有引入行为漂移。
 */
class CbcDirectStreamsTest {

    private val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
    private val iv = ByteArray(16) { ((it * 7 + 1) and 0xFF).toByte() }

    // ==================== 注入式纯 JVM 变换（JCE CBC 链，两种形态同源） ====================

    private fun jceCipher(mode: Int): Cipher {
        ChaCha20CipherEngine.ensureBouncyCastle()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    /** byte[] 形态变换（对照物，与 `CbcStreamFramingTest` 同型）。 */
    private fun byteEncryptTransform(): CbcBlockTransform {
        val cipher = jceCipher(Cipher.ENCRYPT_MODE)
        return { _, _, data -> cipher.update(data) ?: ByteArray(0) }
    }

    private fun byteDecryptTransform(): CbcBlockTransform {
        val cipher = jceCipher(Cipher.DECRYPT_MODE)
        return { _, _, data -> cipher.update(data) ?: ByteArray(0) }
    }

    /**
     * direct 形态加密变换：读出 view → JCE update → 写回（堆内中转仅为模拟手段；
     * 真实原生实现为堆外原地变换，框架语义两者等价——变换不移动 position）。
     */
    private fun directEncryptTransform(): CbcDirectBlockTransform {
        val cipher = jceCipher(Cipher.ENCRYPT_MODE)
        return { _, _, view ->
            val n = view.capacity()
            val input = ByteArray(n)
            try {
                view.position(0)
                view.get(input, 0, n)
                val out = cipher.update(input) ?: ByteArray(0)
                try {
                    view.position(0)
                    view.put(out, 0, n)
                } finally {
                    Arrays.fill(out, 0)
                }
            } finally {
                Arrays.fill(input, 0)
            }
            view.position(0)
        }
    }

    /** direct 形态解密变换（链值由 JCE 自持，与真实原生「iv 原地演化」观测等价）。 */
    private fun directDecryptTransform(): CbcDirectBlockTransform {
        val cipher = jceCipher(Cipher.DECRYPT_MODE)
        return { _, _, view ->
            val n = view.capacity()
            val input = ByteArray(n)
            try {
                view.position(0)
                view.get(input, 0, n)
                val out = cipher.update(input) ?: ByteArray(0)
                try {
                    view.position(0)
                    view.put(out, 0, n)
                } finally {
                    Arrays.fill(out, 0)
                }
            } finally {
                Arrays.fill(input, 0)
            }
            view.position(0)
        }
    }

    // ==================== 流构造（两种形态，其余参数逐一同值） ====================

    private fun encryptDirect(data: ByteArray, bufferSize: Int = CBC_STREAM_BUFFER_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        CbcEncryptingOutputStream(
            sink = out, key = key, iv = iv, transform = null,
            bufferSize = bufferSize, directTransform = directEncryptTransform()
        ).use { it.write(data) }
        return out.toByteArray()
    }

    private fun encryptBytes(data: ByteArray, bufferSize: Int = CBC_STREAM_BUFFER_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        CbcEncryptingOutputStream(
            sink = out, key = key, iv = iv, transform = byteEncryptTransform(),
            bufferSize = bufferSize
        ).use { it.write(data) }
        return out.toByteArray()
    }

    private fun decryptDirect(data: ByteArray, chunkSize: Int = CBC_STREAM_BUFFER_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        CbcDecryptingInputStream(
            source = ByteArrayInputStream(data), key = key, iv = iv, transform = null,
            chunkSize = chunkSize, directTransform = directDecryptTransform()
        ).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun decryptBytes(data: ByteArray, chunkSize: Int = CBC_STREAM_BUFFER_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        CbcDecryptingInputStream(
            source = ByteArrayInputStream(data), key = key, iv = iv,
            transform = byteDecryptTransform(), chunkSize = chunkSize
        ).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 100, 4095, 4096, 4097, 100_000)

    // ==================== 两形态逐字节一致 ====================

    @Test
    fun `direct形态加密与byte数组形态多长度逐字节一致`() {
        for (len in lengths) {
            val plain = ByteArray(len) { ((it * 29 + 11) and 0xFF).toByte() }
            assertArrayEquals("len=$len", encryptBytes(plain), encryptDirect(plain))
        }
    }

    @Test
    fun `direct形态解密与byte数组形态多长度逐字节一致`() {
        for (len in lengths) {
            val plain = ByteArray(len) { ((it * 23 + 13) and 0xFF).toByte() }
            val cipherText = encryptBytes(plain)
            assertArrayEquals("len=$len 往返", plain, decryptDirect(cipherText))
            assertArrayEquals("len=$len 与 byte[] 形态对照", decryptBytes(cipherText), decryptDirect(cipherText))
        }
    }

    @Test
    fun `direct形态小分块与逐字节供给结果不变`() {
        val plain = ByteArray(1000) { ((it * 37 + 3) and 0xFF).toByte() }
        val cipherText = encryptBytes(plain)
        for (chunkSize in listOf(32, 48, 4096)) {
            assertArrayEquals("chunkSize=$chunkSize", plain, decryptDirect(cipherText, chunkSize))
            assertArrayEquals(
                "chunkSize=$chunkSize 与 byte[] 形态对照",
                decryptBytes(cipherText, chunkSize),
                decryptDirect(cipherText, chunkSize)
            )
        }
        // 底层流每次只吐 1 字节：框架必须自行累积对齐（direct 形态同样成立）
        assertArrayEquals(plain, decryptDirect(DripInputStream(cipherText, 1).readBytes()))
    }

    /** 调用方以小于 refill 粒度的步长读取：锁定堆外交付游标（plainPos）跨 refill 的正确性。 */
    @Test
    fun `direct形态小步长读取跨多轮refill正确`() {
        val plain = ByteArray(3000) { ((it * 17 + 5) and 0xFF).toByte() }
        val cipherText = encryptBytes(plain)
        CbcDecryptingInputStream(
            source = ByteArrayInputStream(cipherText), key = key, iv = iv,
            transform = null, chunkSize = 512, directTransform = directDecryptTransform()
        ).use { stream ->
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(7)
            while (true) {
                val n = stream.read(buffer, 0, buffer.size)
                if (n < 0) break
                collected.write(buffer, 0, n)
                Arrays.fill(buffer, 0)
            }
            assertArrayEquals(plain, collected.toByteArray())
        }
    }

    /** 逐字节写入（write(Int) 路径）在 direct 形态下与整批写入一致。 */
    @Test
    fun `direct形态逐字节写入与整批写入一致`() {
        val plain = ByteArray(321) { ((it * 19 + 7) and 0xFF).toByte() }
        val out = ByteArrayOutputStream()
        CbcEncryptingOutputStream(
            sink = out, key = key, iv = iv, transform = null,
            directTransform = directEncryptTransform()
        ).use { stream ->
            for (b in plain) stream.write(b.toInt())
        }
        assertArrayEquals(encryptBytes(plain), out.toByteArray())
    }

    // ==================== fail-closed 错误语义（与 byte[] 形态同向） ====================

    @Test
    fun `direct形态密文长度非分组整数倍时以IOException失败`() {
        val cipherText = encryptBytes(ByteArray(64) { it.toByte() })
        for (truncated in listOf(1, 15, 17, 19)) {
            val bad = cipherText.copyOf(cipherText.size - truncated)
            val thrown = assertThrows(IOException::class.java) { decryptDirect(bad) }
            assertTrue(thrown.message.orEmpty().contains("整数倍"))
        }
    }

    @Test
    fun `direct形态填充非法时以IOException失败`() {
        val cipherText = encryptBytes(ByteArray(64) { it.toByte() })
        val tampered = cipherText.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        assertThrows(IOException::class.java) { decryptDirect(tampered) }

        // 极端：仅一个分组且填充非法（keep == 0，头段为空的收尾路径）
        val single = encryptBytes(ByteArray(0))
        assertEquals(16, single.size)
        single[15] = 0
        assertThrows(IOException::class.java) { decryptDirect(single) }
    }

    @Test
    fun `direct形态空输入以IOException失败`() {
        val thrown = assertThrows(IOException::class.java) { decryptDirect(ByteArray(0)) }
        assertTrue(thrown.message.orEmpty().contains("密文为空"))
    }

    @Test
    fun `transform与directTransform同时注入必须被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            CbcEncryptingOutputStream(
                sink = ByteArrayOutputStream(), key = key, iv = iv,
                transform = byteEncryptTransform(), directTransform = directEncryptTransform()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbcDecryptingInputStream(
                source = ByteArrayInputStream(ByteArray(16)), key = key, iv = iv,
                transform = byteDecryptTransform(), directTransform = directDecryptTransform()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CbcDecryptingInputStream(
                source = ByteArrayInputStream(ByteArray(16)), key = key, iv = iv,
                transform = null, directTransform = null
            )
        }
    }

    // ==================== 工具流 ====================

    /** 每次 `read` 至多返回指定字节数。 */
    private class DripInputStream(private val data: ByteArray, private val dripSize: Int) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position >= data.size) -1 else data[position++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(len, dripSize, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }
    }
}
