package com.keepasskey.crypto.cipher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P3-155 组合层用例：`AesCipherEngine.createDecryptingStream` 由
 * `CipherInputStream`（内部 512 B 缓冲）切换为 [CbcDecryptingInputStream]（64 KiB 分块 +
 * `AES/CBC/NoPadding` 变换）后，**与本仓默认 cipher 的 JCE 基线逐字节、逐语义对齐**。
 *
 * 口径与 `CbcStreamFramingTest` 一致：对照对象是 `javax.crypto` 基线实现的真实输出 / 真实异常，
 * 而非手写预期值；框架层逻辑（分段、填充、截断）已由该类锁定，本类锁定的是 **AES + NoPadding
 * 变换这一新组合**不引入任何可观测行为漂移——尤其 `KdbxCipherKeyResolver` 首块解密探针依赖的
 * 三类 `IOException` 语义。
 */
class AesCbcChunkedDecryptStreamTest {

    private val key = ByteArray(32) { ((it * 13 + 3) and 0xFF).toByte() }
    private val iv = ByteArray(16) { ((it * 7 + 1) and 0xFF).toByte() }
    private val engine = AesCipherEngine()

    /** 生产加密侧（本批未改动的 `CipherOutputStream` + PKCS5）产出密文。 */
    private fun productionEncrypt(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        engine.createEncryptingStream(out, key, iv).use { it.write(data) }
        return out.toByteArray()
    }

    /** JCE 基线解密（`CipherInputStream` + PKCS5，即切换前的生产实现）。 */
    private fun baselineDecrypt(cipherBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        CipherInputStream(ByteArrayInputStream(cipherBytes), cipher).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** 新解密路径（生产引擎）。 */
    private fun productionDecrypt(cipherBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        engine.createDecryptingStream(ByteArrayInputStream(cipherBytes), key, iv).use { it.copyTo(out) }
        return out.toByteArray()
    }

    /** 覆盖：空 / 单块边界 / 块间 / 分块缓冲边界（64 KiB ± 1）/ 多倍缓冲。 */
    private val lengths = listOf(
        0, 1, 15, 16, 17, 31, 32, 100, 4095, 4096, 4097,
        64 * 1024 - 1, 64 * 1024, 64 * 1024 + 1, 2 * 64 * 1024 + 33, 1_000_003
    )

    @Test
    fun `新解密路径与JCE基线逐字节一致且往返还原明文`() {
        lengths.forEach { length ->
            val plain = ByteArray(length) { ((it * 89 + 17) and 0xFF).toByte() }
            val cipherBytes = productionEncrypt(plain)
            try {
                val restored = productionDecrypt(cipherBytes)
                val baseline = baselineDecrypt(cipherBytes)
                try {
                    assertArrayEquals("往返必须逐字节还原明文（length=$length）", plain, restored)
                    assertArrayEquals("与 JCE 基线输出必须逐字节一致（length=$length）", baseline, restored)
                } finally {
                    baseline.fill(0)
                    restored.fill(0)
                }
            } finally {
                plain.fill(0)
                cipherBytes.fill(0)
            }
        }
    }

    @Test
    fun `新解密路径产出必须是分块流实现`() {
        val stream = engine.createDecryptingStream(
            ByteArrayInputStream(ByteArray(32)),
            key,
            iv
        )
        assertTrue(
            "解密流必须是 CbcDecryptingInputStream（64 KiB 分块），实际：${stream.javaClass.name}",
            stream is CbcDecryptingInputStream
        )
        stream.close()
    }

    @Test
    fun `三类错误语义与JCE基线逐例对齐`() {
        // ---- ① 填充非法：合法密文的最后一个字节（落在填充区 / 末块）被篡改 ----
        val plain = ByteArray(100) { ((it * 89 + 17) and 0xFF).toByte() }
        val corrupted = productionEncrypt(plain).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertBothFailWithIo("填充非法", corrupted)

        // ---- ② 密文长度非分组整数倍 ----
        val truncated = productionEncrypt(plain).copyOfRange(0, 30)
        assertBothFailWithIo("长度非分组整数倍", truncated)

        plain.fill(0)

        // ---- ③ 空输入（**已登记的可接受差异**，方向为 fail-closed）----
        // 宿主 SunJCE 基线：CipherInputStream 对空密文**不抛异常、静默产出空**（本机实测）；
        // 生产新路径：CbcDecryptingInputStream 对空输入恒抛 IOException（框架 fail-closed，
        // 与 `CbcStreamFramingTest` 锁定的行为一致）。差异**生产不可达**：KDBX 载荷恒非空
        // （HMAC 块流），`KdbxCipherKeyResolver` 首块探针恒喂 ≥ 一块的真实字节；
        // 且「静默产出空明文」对密码管理器而言是更危险的失败形态，故维持框架行为不放宽。
        val emptyBaselineThrew = catchIo { baselineDecrypt(ByteArray(0)) } != null
        val productionThrew = catchIo { productionDecrypt(ByteArray(0)) } != null
        assertTrue(
            "新路径对空输入必须 fail-closed 抛 IOException（基线本次是否抛出：$emptyBaselineThrew——" +
                "宿主 SunJCE 与设备 Conscrypt 在该边界上的行为差异属平台实现，不作为断言对象）",
            productionThrew
        )
    }

    /** 同一输入必须让「JCE 基线」与「新路径」抛出同类异常（均为 [IOException]）。 */
    private fun assertBothFailWithIo(scenario: String, cipherBytes: ByteArray) {
        val baselineError = catchIo { baselineDecrypt(cipherBytes) }
        val productionError = catchIo { productionDecrypt(cipherBytes) }
        if (baselineError == null) {
            fail("[$scenario] JCE 基线未抛异常，本用例的基线口径失效（基线行为漂移，需重测重对齐）")
        }
        if (productionError == null) {
            fail("[$scenario] 新路径未按基线抛 IOException（实得：正常产出）")
        }
        assertEquals(
            "[$scenario] 新路径异常类型必须与基线一致",
            baselineError!!::class,
            productionError!!::class
        )
    }

    private inline fun catchIo(block: () -> ByteArray): IOException? = try {
        block()
        null
    } catch (e: IOException) {
        e
    }
}
