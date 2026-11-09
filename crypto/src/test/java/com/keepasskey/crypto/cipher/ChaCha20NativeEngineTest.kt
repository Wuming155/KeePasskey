package com.keepasskey.crypto.cipher

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Arrays
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

    // ================= ENG-20261028-14：读取粒度 / 有界预读 / 擦除 =================

    /**
     * **读取粒度回归**（单字节 / 短读 / 批量 / EOF）：本类其余用例只经 `copyTo`（8 KiB 批量）
     * 读取，覆盖不到「`read()` 单字节」与「小于预读下限的短读」——这两条粒度正是
     * `ENG-20261028-14` 登记的逐字节跨 JNI 退化面。同一份密文按单字节、3 字节、4 KiB、
     * 64 KiB（跨流分块边界，明文长 64 KiB + 7）读取，必须逐字节还原明文；EOF 之后重复读取
     * 仍返回 -1；`len == 0` 按 `InputStream` 契约返回 0。底层来源另以「每次最多 5 字节」
     * 的短读流包装，锁定 refill 的循环读满语义。
     */
    @Test
    fun `单字节短读批量与EOF读取语义一致`() {
        assumeTrue("宿主原生库未注入（Assume 跳过；设备侧用例硬断言补位）", NativeChaCha20.available)
        val plain = ByteArray(64 * 1024 + 7) { ((it * 41 + 11) and 0xFF).toByte() }
        val sink = ByteArrayOutputStream()
        engine.createEncryptingStream(sink, key, nonce).use { it.write(plain) }
        val cipherBytes = sink.toByteArray()
        try {
            listOf(0, 3, 4096, 64 * 1024).forEach { step ->
                val restored = readAllAtGranularity(cipherBytes, step)
                try {
                    assertArrayEquals("读取粒度 step=$step 必须逐字节还原明文", plain, restored)
                } finally {
                    restored.fill(0)
                }
            }

            val trickled = readAllAtGranularity(cipherBytes, 4096, maxSourceRead = 5)
            try {
                assertArrayEquals("底层每次仅交付 5 字节时也必须逐字节还原明文", plain, trickled)
            } finally {
                trickled.fill(0)
            }

            engine.createDecryptingStream(ByteArrayInputStream(cipherBytes), key, nonce).use { stream ->
                val buffer = ByteArray(64 * 1024)
                try {
                    while (stream.read(buffer) != -1) Unit
                    assertEquals("首次 EOF 必须返回 -1", -1, stream.read(buffer))
                    assertEquals("EOF 之后单字节读取仍返回 -1", -1, stream.read())
                    assertEquals("len=0 的读取按 InputStream 契约返回 0", 0, stream.read(buffer, 0, 0))
                } finally {
                    Arrays.fill(buffer, 0)
                }
            }
        } finally {
            cipherBytes.fill(0)
            plain.fill(0)
        }
    }

    /**
     * **有界预读 + 交付后擦除 + 关闭清理**（`ENG-20261028-14` 的关闭条件本体）。
     *
     * 两个观察点：
     * 1. 密文来源的累计读取量——单次 `read()` 必须一次向前取多于 1 字节（固定 JNI 成本由整段
     *    预读摊薄），但不得一次吞掉整段密文（预读有界，未交付明文驻留随之有界）；
     * 2. 流自持的堆外明文区（经仅测试可见的 `observePlain` 交出引用）——已交付区间必须就地
     *    归零、未交付区间仍逐字节承载明文（证明确实观察到承载明文的缓冲，非空转断言）；
     *    `close()` 后整段归零、自持 key / nonce 副本归零，重复 close 不抛，关闭后读取抛
     *    `IOException`，而**已交付给调用方的字节保持有效**（所有权已转移，不得被连带清零）。
     */
    @Test
    fun `小粒度读取按有界预读取数且交付与关闭确定性擦除`() {
        assumeTrue("宿主原生库未注入（Assume 跳过；设备侧用例硬断言补位）", NativeChaCha20.available)
        val plain = ByteArray(10_000) { ((it * 29 + 3) and 0xFF).toByte() }
        val sink = ByteArrayOutputStream()
        engine.createEncryptingStream(sink, key, nonce).use { it.write(plain) }
        val cipherBytes = sink.toByteArray()

        val ownedKey = key.copyOf()
        val ownedNonce = nonce.copyOf()
        val observed = mutableListOf<ByteBuffer>()
        var consumedFromSource = 0
        val countingSource = object : InputStream() {
            private val delegate = ByteArrayInputStream(cipherBytes)
            override fun read(): Int = delegate.read().also { if (it > 0) consumedFromSource += 1 }
            override fun read(data: ByteArray, off: Int, len: Int): Int =
                delegate.read(data, off, len).also { if (it > 0) consumedFromSource += it }
            override fun close() {
                delegate.close()
            }
        }
        val stream = ChaCha20CipherEngine.NativeDecryptingInputStream(
            countingSource, ownedKey, ownedNonce
        ) { observed += it }
        val delivered = ByteArray(8)
        try {
            assertEquals("单字节读取必须还原明文首字节", plain[0].toInt() and 0xFF, stream.read())
            assertEquals("短读必须交付请求的 8 字节", 8, stream.read(delivered, 0, 8))
            assertArrayEquals(
                "交付给调用方的字节必须与明文一致",
                plain.copyOfRange(1, 9),
                delivered
            )

            val plainRegion = observed.single()
            assertTrue(
                "单次小粒度读取必须向前预读多于交付量（实际 $consumedFromSource 字节）",
                consumedFromSource > 9
            )
            assertTrue(
                "预读必须有界，不得一次吞掉整段密文（实际 $consumedFromSource / ${cipherBytes.size} 字节）",
                consumedFromSource < cipherBytes.size
            )
            assertTrue(
                "已交付区间必须就地归零",
                (0 until 9).all { plainRegion.get(it) == 0.toByte() }
            )
            assertTrue(
                "未交付的预读区间必须仍承载明文（观察到的是真实承载缓冲）",
                (9 until consumedFromSource).all { plainRegion.get(it) == plain[it] }
            )

            stream.close()
            assertThrows("关闭后 read() 必须抛 IOException", IOException::class.java) { stream.read() }
            assertThrows(
                "关闭后批量 read 必须抛 IOException",
                IOException::class.java
            ) { stream.read(delivered, 0, 8) }
            stream.close()

            assertTrue(
                "close 后堆外明文区必须整段归零",
                (0 until plainRegion.capacity()).all { plainRegion.get(it) == 0.toByte() }
            )
            assertTrue("close 后自持 key 副本必须归零", ownedKey.all { it == 0.toByte() })
            assertTrue("close 后自持 nonce 副本必须归零", ownedNonce.all { it == 0.toByte() })
            assertArrayEquals(
                "close 不得影响已交付给调用方的字节",
                plain.copyOfRange(1, 9),
                delivered
            )
        } finally {
            stream.close()
            Arrays.fill(delivered, 0)
            cipherBytes.fill(0)
            ownedKey.fill(0)
            ownedNonce.fill(0)
            plain.fill(0)
        }
    }

    /**
     * 按 [step] 粒度读完解密流：`step == 0` 走 [InputStream.read] 单字节路径，否则以
     * `read(buf, 0, step)` 读取；[maxSourceRead] 非空时把密文来源包一层「每次最多交付 N 字节」
     * 的短读流。
     */
    private fun readAllAtGranularity(
        cipherBytes: ByteArray,
        step: Int,
        maxSourceRead: Int? = null
    ): ByteArray {
        val source = if (maxSourceRead == null) {
            ByteArrayInputStream(cipherBytes)
        } else {
            object : InputStream() {
                private val delegate = ByteArrayInputStream(cipherBytes)
                override fun read(): Int = delegate.read()
                override fun read(data: ByteArray, off: Int, len: Int): Int =
                    delegate.read(data, off, minOf(len, maxSourceRead))
                override fun close() {
                    delegate.close()
                }
            }
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(if (step == 0) 1 else step)
        engine.createDecryptingStream(source, key, nonce).use { stream ->
            if (step == 0) {
                while (true) {
                    val value = stream.read()
                    if (value < 0) break
                    out.write(value)
                }
            } else {
                while (true) {
                    val count = stream.read(buffer, 0, step)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
        }
        Arrays.fill(buffer, 0)
        return out.toByteArray()
    }
}
