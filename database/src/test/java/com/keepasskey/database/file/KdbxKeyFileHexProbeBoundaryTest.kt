package com.keepasskey.database.file

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * `ISSUE-P3-181` ①：`compactHexKeyOrNull` 的**形态边界**回归。
 *
 * 该函数把「无条件 `stripWhitespace` 复制整份密钥文件」改为「单趟探测 + 命中才构造」，
 * 因此它的判定边界就是本次改动的唯一风险点：
 * 「非空白字节数 == 64」与「64 个字节全为 hex」两条同时成立才走 hex 解码，否则必须
 * **逐字**回落到既有的整文件 SHA-256 分支。本类对四条边界各锁定一次，并与独立计算的
 * 期望值（`MessageDigest` 直算）对比——避免「用被测实现自己的工具算期望值」。
 */
class KdbxKeyFileHexProbeBoundaryTest {

    @Test
    fun `恰 64 位 hex 文本走 hex 解码`() {
        val key = KdbxKeyFile.extractKey(HEX_64.toByteArray(Charsets.US_ASCII))
        assertArrayEquals("64 位 hex 文本必须解出 32 字节密钥", hexDecoded(HEX_64), key)
    }

    @Test
    fun `含空白与换行的 64 位 hex 文本仍走 hex 解码`() {
        val raw = (
            "01020304 05060708\t090A0B0C 0D0E0F10\n" +
                "11121314 15161718 191A1B1C 1D1E1F20\r\n"
            ).toByteArray(Charsets.US_ASCII)
        assertArrayEquals(
            "空白必须被跳过且不计入 64 位长度判定",
            hexDecoded(HEX_64),
            KdbxKeyFile.extractKey(raw)
        )
    }

    @Test
    fun `63 位与 65 位 hex 文本均回落整文件 SHA-256`() {
        listOf(HEX_64.dropLast(1), HEX_64 + "0").forEach { text ->
            val raw = text.toByteArray(Charsets.US_ASCII)
            assertArrayEquals(
                "长度不等于 64 时必须回落整文件 SHA-256（本次长度 ${text.length}）",
                sha256(raw),
                KdbxKeyFile.extractKey(raw)
            )
        }
    }

    @Test
    fun `64 个非空字节中含非 hex 字符时回落整文件 SHA-256`() {
        val raw = ("0".repeat(63) + "Z").toByteArray(Charsets.US_ASCII)
        assertEquals(64, raw.size)
        assertArrayEquals(
            "64 字节但含 hex 字母表外字符时必须回落 SHA-256",
            sha256(raw),
            KdbxKeyFile.extractKey(raw)
        )
    }

    @Test
    fun `非 hex 字节位于末位前后的判定一致`() {
        // 探测在「第 65 个非空白字节」或「非 hex 字节」处提前返回；两处都必须落入 SHA-256，
        // 且判定结果不得依赖非 hex 字节出现的位置
        val headBad = ("Z" + "0".repeat(63)).toByteArray(Charsets.US_ASCII)
        val tailBad = ("0".repeat(63) + "Z").toByteArray(Charsets.US_ASCII)
        assertArrayEquals(sha256(headBad), KdbxKeyFile.extractKey(headBad))
        assertArrayEquals(sha256(tailBad), KdbxKeyFile.extractKey(tailBad))
    }

    private fun hexDecoded(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun sha256(raw: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(raw)

    private companion object {
        const val HEX_64 = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"
    }
}
