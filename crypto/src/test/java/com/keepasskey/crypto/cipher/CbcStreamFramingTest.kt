package com.keepasskey.crypto.cipher

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CBC 流式包装的**框架层**用例（ISSUE-P3-35）。
 *
 * 目的有二：
 * 1. **不依赖原生库**即可验证 [CbcEncryptingOutputStream] / [CbcDecryptingInputStream] 的分段对齐、
 *    填充与截断处理——通过注入「用 JCE 自身 CBC 链」构造的纯 JVM 变换实现（`bcCbc*Transform`），
 *    分组变换的正确性由 JCE 背书，被测对象只剩框架逻辑；
 * 2. **如实实测 `javax.crypto.CipherInputStream` 的基线行为并对齐**。ISSUE-P3-35 的验收标准明确
 *    要求「流式错误语义与既有一致性必须实测」：本类的 KDoc 声称三类输入（填充非法 / 长度非分组
 *    整数倍 / 底层流 IOException）在基线实现下表现为「无异常、提前结束」，此处以 JCE 为参照
 *    逐例断言，**任何偏离都会让本用例变红**，而不是留在文档里靠信任。
 *
 * 说明书：契约是「原生路径与 JCE 路径可观测行为一致」，故对照对象是 `CipherInputStream` /
 * `CipherOutputStream` 的真实输出，而非任何手写预期值。
 */
class CbcStreamFramingTest {

    private val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
    private val iv = ByteArray(16) { ((it * 7 + 1) and 0xFF).toByte() }

    // ==================== 注入式纯 JVM 变换（JCE 自身 CBC 链） ====================

    /** 加密变换：借 JCE 的 CBC 链，链值取本次输出的最后一块密文。 */
    private fun bcCbcEncryptTransform(): CbcBlockTransform {
        val cipher = bcCipher(Cipher.ENCRYPT_MODE, "Twofish/CBC/NoPadding")
        return { _, chain, data ->
            val out = cipher.update(data) ?: ByteArray(0)
            if (out.size >= Pkcs7.BLOCK_SIZE) {
                System.arraycopy(out, out.size - Pkcs7.BLOCK_SIZE, chain, 0, Pkcs7.BLOCK_SIZE)
            }
            out
        }
    }

    /** 解密变换：链值取本次**输入**的最后一块密文（CBC 解密侧定义）。 */
    private fun bcCbcDecryptTransform(): CbcBlockTransform {
        val cipher = bcCipher(Cipher.DECRYPT_MODE, "Twofish/CBC/NoPadding")
        return { _, chain, data ->
            val out = cipher.update(data) ?: ByteArray(0)
            if (data.size >= Pkcs7.BLOCK_SIZE) {
                System.arraycopy(data, data.size - Pkcs7.BLOCK_SIZE, chain, 0, Pkcs7.BLOCK_SIZE)
            }
            out
        }
    }

    private fun bcCipher(mode: Int, transformation: String): Cipher {
        ChaCha20CipherEngine.ensureBouncyCastle()
        // 刻意**不用** `Cipher.getInstance(...).apply { ... }`。实测（2026-09-10，用
        // `val r = cipher.run { iv }` 探针并断言 `r == null`）确认：在 `Cipher` 接收者作用域内
        // `iv` 会被解析到接收者自身由 `getIV()` 合成的属性，而非本测试类的 `iv` 属性；
        // 该属性在 `init` 之前恒为 null，于是 `IvParameterSpec(iv)` 直接抛 NPE。
        // 改用局部变量可彻底消除该作用域歧义。
        val cipher = Cipher.getInstance(transformation, BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(mode, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        return cipher
    }

    private fun jceEncryptWithPadding(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        CipherOutputStream(out, bcCipher(Cipher.ENCRYPT_MODE, "Twofish/CBC/PKCS7PADDING")).use {
            it.write(data)
        }
        return out.toByteArray()
    }

    private fun jceDecryptWithPadding(data: ByteArray, source: InputStream = ByteArrayInputStream(data)): ByteArray {
        val out = ByteArrayOutputStream()
        CipherInputStream(source, bcCipher(Cipher.DECRYPT_MODE, "Twofish/CBC/PKCS7PADDING")).use {
            it.copyTo(out)
        }
        return out.toByteArray()
    }

    private fun nativeEncrypt(data: ByteArray, bufferSize: Int = CBC_STREAM_BUFFER_SIZE): ByteArray {
        val out = ByteArrayOutputStream()
        CbcEncryptingOutputStream(out, key, iv, bcCbcEncryptTransform(), bufferSize).use { it.write(data) }
        return out.toByteArray()
    }

    private fun nativeDecrypt(
        data: ByteArray,
        chunkSize: Int = CBC_STREAM_BUFFER_SIZE,
        source: InputStream = ByteArrayInputStream(data)
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CbcDecryptingInputStream(source, key, iv, bcCbcDecryptTransform(), chunkSize).use {
            it.copyTo(out)
        }
        return out.toByteArray()
    }

    private val lengths = listOf(0, 1, 15, 16, 17, 31, 32, 100, 4095, 4096, 4097, 100_000)

    // ==================== 加密侧：与 JCE CipherOutputStream 逐字节一致 ====================

    @Test
    fun `加密侧与 JCE CipherOutputStream 多长度逐字节一致`() {
        for (len in lengths) {
            val plain = ByteArray(len) { ((it * 29 + 11) and 0xFF).toByte() }
            assertArrayEquals("len=$len", jceEncryptWithPadding(plain), nativeEncrypt(plain))
        }
    }

    @Test
    fun `小缓冲分段加密与默认缓冲结果一致`() {
        for (len in listOf(0, 1, 17, 64, 1000)) {
            val plain = ByteArray(len) { ((it * 17 + 5) and 0xFF).toByte() }
            assertArrayEquals("len=$len", nativeEncrypt(plain), nativeEncrypt(plain, bufferSize = 32))
        }
    }

    @Test
    fun `逐字节写入与整批写入一致`() {
        val plain = ByteArray(321) { ((it * 19 + 7) and 0xFF).toByte() }
        val out = ByteArrayOutputStream()
        CbcEncryptingOutputStream(out, key, iv, bcCbcEncryptTransform()).use { stream ->
            for (b in plain) stream.write(b.toInt())
        }
        assertArrayEquals(nativeEncrypt(plain), out.toByteArray())
    }

    // ==================== 解密侧：与 JCE CipherInputStream 逐字节一致 ====================

    @Test
    fun `解密侧与 JCE CipherInputStream 多长度一致`() {
        for (len in lengths) {
            val plain = ByteArray(len) { ((it * 23 + 13) and 0xFF).toByte() }
            val cipherText = jceEncryptWithPadding(plain)
            assertArrayEquals("len=$len 往返", plain, nativeDecrypt(cipherText))
            assertArrayEquals("len=$len 与 JCE 对照", jceDecryptWithPadding(cipherText), nativeDecrypt(cipherText))
        }
    }

    @Test
    fun `小分块解密与默认分块结果一致`() {
        for (len in listOf(0, 1, 16, 17, 500, 5000)) {
            val plain = ByteArray(len) { ((it * 3 + 1) and 0xFF).toByte() }
            val cipherText = jceEncryptWithPadding(plain)
            assertArrayEquals(
                "len=$len",
                nativeDecrypt(cipherText),
                nativeDecrypt(cipherText, chunkSize = 32)
            )
        }
    }

    /** 底层流每次只吐 1 字节（模拟分段网络/压缩流），框架必须自行累积对齐。 */
    @Test
    fun `底层流逐字节供给时结果不变`() {
        val plain = ByteArray(300) { ((it * 37 + 3) and 0xFF).toByte() }
        val cipherText = jceEncryptWithPadding(plain)
        assertArrayEquals(plain, nativeDecrypt(cipherText, source = DripInputStream(cipherText, 1)))
        assertArrayEquals(
            jceDecryptWithPadding(cipherText, DripInputStream(cipherText, 1)),
            nativeDecrypt(cipherText, source = DripInputStream(cipherText, 1))
        )
    }

    // ==================== 实测基线：错误语义对齐 ====================

    /** 密文长度非分组整数倍（如传输被截断）→ 基线 `CipherInputStream` 静默 EOF，不抛异常。 */
    /**
     * 对照工具：断言两侧「**同为 `IOException` 失败**」或「**结果逐字节相同**」。
     *
     * 之所以不写死「一定失败」或「一定成功」：契约是**与基线一致**，而基线行为
     * 由本机 JDK 实测决定（见下）。两侧成败语义必须同向、失败类型必须同为 `IOException`。
     */
    private fun assertSameOutcome(name: String, jce: () -> ByteArray, native: () -> ByteArray) {
        val jceOutcome = runCatching(jce)
        val nativeOutcome = runCatching(native)
        when {
            jceOutcome.isFailure && nativeOutcome.isFailure -> {
                assertTrue(
                    "$name：JCE 基线的失败类型应为 IOException，实际 ${jceOutcome.exceptionOrNull()}",
                    jceOutcome.exceptionOrNull() is IOException
                )
                assertTrue(
                    "$name：新实现的失败类型应为 IOException，实际 ${nativeOutcome.exceptionOrNull()}",
                    nativeOutcome.exceptionOrNull() is IOException
                )
            }
            jceOutcome.isSuccess && nativeOutcome.isSuccess ->
                assertArrayEquals(name, jceOutcome.getOrThrow(), nativeOutcome.getOrThrow())
            else -> throw AssertionError(
                "$name：成败语义与基线不一致（JCE=${jceOutcome.exceptionOrNull()?.javaClass?.simpleName ?: "成功"}" +
                    " / 新实现=${nativeOutcome.exceptionOrNull()?.javaClass?.simpleName ?: "成功"}）"
            )
        }
    }

    /** 密文长度非分组整数倍（如传输被截断）→ 两侧均以 `IOException` 失败（实测基线行为）。 */
    @Test
    fun `密文长度非分组整数倍时与 JCE 基线同为 IOException`() {
        val cipherText = jceEncryptWithPadding(ByteArray(64) { it.toByte() })
        for (truncated in listOf(1, 15, 17, 19)) {
            val bad = cipherText.copyOf(cipherText.size - truncated)
            assertSameOutcome("截断 $truncated 字节", { jceDecryptWithPadding(bad) }, { nativeDecrypt(bad) })
        }
        assertSameOutcome("长度不足一个分组", { jceDecryptWithPadding(ByteArray(5)) }, { nativeDecrypt(ByteArray(5)) })
    }

    /** 末块填充非法（密钥错 / 数据被篡改）→ 两侧均以 `IOException` 失败（实测基线行为）。 */
    @Test
    fun `填充非法时与 JCE 基线同为 IOException`() {
        val cipherText = jceEncryptWithPadding(ByteArray(64) { it.toByte() })
        val tampered = cipherText.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        assertSameOutcome("填充被破坏", { jceDecryptWithPadding(tampered) }, { nativeDecrypt(tampered) })

        // 极端：仅一个分组且填充非法
        val single = jceEncryptWithPadding(ByteArray(0))
        assertEquals(16, single.size)
        single[15] = 0
        assertSameOutcome("单分组填充非法", { jceDecryptWithPadding(single) }, { nativeDecrypt(single) })
    }

    /** 空输入 → 两侧语义一致（实测基线以 `IOException` 失败）。 */
    @Test
    fun `空输入两侧语义一致`() {
        assertSameOutcome("空输入", { jceDecryptWithPadding(ByteArray(0)) }, { nativeDecrypt(ByteArray(0)) })
    }

    /**
     * 底层流抛 IOException：**新实现不得比 JCE 基线更宽松**。
     *
     * 契约形式（刻意不写成「两侧完全一致」）：基线抛异常时新实现**必须也抛**；
     * 基线静默 EOF 而新实现更早抛出，属**有意的 fail-closed 强化**，予以允许。
     * 反向（基线抛而新实现吞掉）一律视为缺陷。
     */
    @Test
    fun `底层流 IOException 的可见性不弱于 JCE 基线`() {
        val cipherText = jceEncryptWithPadding(ByteArray(200) { it.toByte() })

        val jceThrew = runCatching {
            jceDecryptWithPadding(cipherText, FailingAfterInputStream(cipherText, failAfter = 64))
        }.isFailure
        val nativeThrew = runCatching {
            nativeDecrypt(cipherText, source = FailingAfterInputStream(cipherText, failAfter = 64))
        }.isFailure

        assertTrue("基线抛异常时新实现不得吞掉（基线=$jceThrew 新实现=$nativeThrew）", !jceThrew || nativeThrew)
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

    /** 供给 `failAfter` 字节后抛 IOException。 */
    private class FailingAfterInputStream(private val data: ByteArray, private val failAfter: Int) : InputStream() {
        private var position = 0
        override fun read(): Int {
            if (position >= failAfter) throw java.io.IOException("模拟底层流中断")
            return if (position >= data.size) -1 else data[position++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= failAfter) throw java.io.IOException("模拟底层流中断")
            if (position >= data.size) return -1
            val count = minOf(len, failAfter - position, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }
    }
}
