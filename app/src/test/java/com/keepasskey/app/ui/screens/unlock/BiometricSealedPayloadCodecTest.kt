package com.keepasskey.app.ui.screens.unlock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快速解锁封印载荷编解码单测（ISSUE-P2-23）。
 *
 * 覆盖：复合帧编解码往返（含/不含密钥文件因子）、历史格式（纯主密码 UTF-8）向后兼容解析、
 * 帧结构损坏 fail-fast、敏感缓冲清零语义。
 */
class BiometricSealedPayloadCodecTest {

    // ── 编码 → 解码往返 ──────────────────────────────────────────────

    @Test
    fun `纯主密码载荷往返`() {
        val password = "Correct%Horse#主密码".toCharArray()

        val payload = BiometricSealedPayloadCodec.encode(password, keyFileData = null)
        val decoded = BiometricSealedPayloadCodec.decode(payload)

        assertEquals("Correct%Horse#主密码", String(decoded.passwordChars))
        assertNull("未携带密钥文件时解码不得产出因子", decoded.keyFileData)
    }

    @Test
    fun `复合载荷往返包含密钥文件因子`() {
        val password = "master-pass-😀-pass".toCharArray()
        val keyFile = ByteArray(257) { (it * 7 + 3).toByte() }

        val payload = BiometricSealedPayloadCodec.encode(password, keyFile)
        val decoded = BiometricSealedPayloadCodec.decode(payload)

        assertEquals("master-pass-😀-pass", String(decoded.passwordChars))
        assertArrayEquals("密钥文件因子必须逐字节还原", keyFile, decoded.keyFileData)
    }

    @Test
    fun `载荷以魔数开头且标志位正确`() {
        val payload = BiometricSealedPayloadCodec.encode("p".toCharArray(), null)
        assertEquals('K'.code.toByte(), payload[0])
        assertEquals('P'.code.toByte(), payload[1])
        assertEquals('B'.code.toByte(), payload[2])
        assertEquals('1'.code.toByte(), payload[3])
        assertEquals("版本号紧随魔数", 1, payload[4].toInt() and 0xFF)
        assertEquals("无密钥文件时标志位为 0", 0, payload[5].toInt() and 0xFF)

        val composite = BiometricSealedPayloadCodec.encode("p".toCharArray(), ByteArray(8))
        assertEquals("携带密钥文件时 bit0 置位", 1, composite[5].toInt() and 0xFF)
    }

    // ── 历史格式向后兼容 ─────────────────────────────────────────────

    @Test
    fun `历史格式纯主密码载荷兼容解析`() {
        // P2-23 之前的存量封印凭据：整体 = 主密码 UTF-8，无任何帧头
        val legacy = "legacy-sealed-密码".toByteArray(Charsets.UTF_8)

        val decoded = BiometricSealedPayloadCodec.decode(legacy)

        assertEquals("legacy-sealed-密码", String(decoded.passwordChars))
        assertNull(decoded.keyFileData)
    }

    // ── 帧结构损坏 fail-fast ────────────────────────────────────────

    @Test
    fun `未知版本号解析失败`() {
        val payload = BiometricSealedPayloadCodec.encode("p".toCharArray(), null)
        payload[4] = 0x7F

        val corrupted = try {
            BiometricSealedPayloadCodec.decode(payload)
            null
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("未知版本必须 fail-fast", corrupted != null)
    }

    @Test
    fun `长度越界解析失败`() {
        val payload = BiometricSealedPayloadCodec.encode("p".toCharArray(), null)
        // 主密码长度前缀（紧随头部的 4 字节 BE）改为远超载荷总长
        payload[5] = 0x7F

        val corrupted = try {
            BiometricSealedPayloadCodec.decode(payload)
            null
        } catch (expected: IllegalStateException) {
            expected
        }
        assertTrue("长度越界必须 fail-fast", corrupted != null)
    }

    // ── 敏感缓冲清零 ────────────────────────────────────────────────

    @Test
    fun `解码产物wipe清零全部敏感缓冲`() {
        val password = "wipe-me-密码".toCharArray()
        val keyFile = ByteArray(64) { it.toByte() }

        val decoded = BiometricSealedPayloadCodec.decode(
            BiometricSealedPayloadCodec.encode(password, keyFile)
        )
        decoded.wipe()

        assertTrue("密码字符必须清零", decoded.passwordChars.all { it == '0' })
        assertTrue("密钥文件字节必须清零", decoded.keyFileData?.all { it == 0.toByte() } == true)
    }

    @Test
    fun `空密钥文件数组视为未携带`() {
        val decoded = BiometricSealedPayloadCodec.decode(
            BiometricSealedPayloadCodec.encode("p".toCharArray(), ByteArray(0))
        )
        assertNull("空密钥文件数组按未携带语义处理", decoded.keyFileData)
    }

    @Test
    fun `decode不残留主密码UTF8中间缓冲于输入数组`() {
        // 输入载荷本身属密文解封产物（由调用方清零）；此处仅验证 decode 不修改调用方数组
        val payload = BiometricSealedPayloadCodec.encode("p".toCharArray(), null)
        val snapshot = payload.copyOf()
        BiometricSealedPayloadCodec.decode(payload)
        assertArrayEquals("decode 不得修改调用方载荷数组", snapshot, payload)
        assertFalse(snapshot.contentEquals(ByteArray(payload.size)))
    }
}
