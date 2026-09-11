package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 写侧变体字典编码契约测试（ISSUE-P1-13 整改验收标准 ②）。
 *
 * 背景：写侧曾把 Argon2 `P` 以 UInt64（type 0x05）写出，违反 KDBX4 规范
 * （`P` / `V` 必须为 UInt32；`I` / `M` 为 UInt64）。本 App 读侧类型宽容故自读无碍，
 * 但官方 KeePass / KeePassXC 按严格 uint 读取——类型不符即以默认值派生密钥而解锁失败。
 *
 * 本测试直接断言**序列化字节流中的类型 ID**（而非经本仓宽容读侧回读），
 * 与 `tools/kdbx-corpus/generate_corpus.py --verify` 的地面真值判定逐键对齐：
 * - `$UUID` / `S` / `K` / `A` → ByteArray (0x42)
 * - `P` / `V` → UInt32 (0x04)，长度 4
 * - `I` / `M` → UInt64 (0x05)，长度 8
 * - AES-KDF：`S` → ByteArray，`R` → UInt64
 */
class KdbxKdfParameterCodecWriteContractTest {

    /** 逐键解析序列化产物：key → (typeId, valueLength, rawValueBytes)。 */
    private fun parseEntries(bytes: ByteArray): Map<String, Pair<Byte, ByteArray>> {
        val result = mutableMapOf<String, Pair<Byte, ByteArray>>()
        var offset = 2 // version (short)
        while (offset < bytes.size) {
            val type = bytes[offset]
            if (type.toInt() == 0x00) break // 终止符
            offset += 1
            val keyLen = readInt32(bytes, offset)
            offset += 4
            val key = String(bytes, offset, keyLen, Charsets.UTF_8)
            offset += keyLen
            val valueLen = readInt32(bytes, offset)
            offset += 4
            result[key] = type to bytes.copyOfRange(offset, offset + valueLen)
            offset += valueLen
        }
        return result
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun serializeToBytes(params: KdfParameters): ByteArray {
        val vd = KdbxKdfParameterCodec.serialize(params)
        val out = ByteArrayOutputStream()
        vd.serialize(out)
        return out.toByteArray()
    }

    @Test
    fun `Argon2 写侧逐键类型符合 KDBX4 规范（P 与 V 为 UInt32）`() {
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32),
            parallelism = 2,
            memoryInBytes = 64L * 1024 * 1024,
            iterations = 2L,
            version = KdfParameters.Argon2.ARGON2_VERSION_13
        )

        val entries = parseEntries(serializeToBytes(params))

        val typeUuid = 0x42.toByte()
        val typeUInt32 = 0x04.toByte()
        val typeUInt64 = 0x05.toByte()

        assertEquals(typeUuid to 16, entries.getValue("\$UUID").let { it.first to it.second.size })
        assertEquals(typeUuid to 32, entries.getValue("S").let { it.first to it.second.size })
        assertEquals(
            "P 必须以 UInt32 (0x04) 编码（ISSUE-P1-13）",
            typeUInt32, entries.getValue("P").first
        )
        assertEquals(4, entries.getValue("P").second.size)
        assertEquals(
            "V 必须以 UInt32 (0x04) 编码",
            typeUInt32, entries.getValue("V").first
        )
        assertEquals(typeUInt64, entries.getValue("I").first)
        assertEquals(8, entries.getValue("I").second.size)
        assertEquals(typeUInt64, entries.getValue("M").first)
        assertEquals(8, entries.getValue("M").second.size)
    }

    @Test
    fun `Argon2 P 值经官方严格 uint 语义回读一致`() {
        val params = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2D,
            salt = ByteArray(32),
            parallelism = 4,
            memoryInBytes = 64L * 1024 * 1024,
            iterations = 89L
        )

        val entries = parseEntries(serializeToBytes(params))
        val pBytes = entries.getValue("P").second
        val pValue =
            (pBytes[0].toLong() and 0xFF) or
                ((pBytes[1].toLong() and 0xFF) shl 8) or
                ((pBytes[2].toLong() and 0xFF) shl 16) or
                ((pBytes[3].toLong() and 0xFF) shl 24)
        assertEquals(4L, pValue)
    }

    @Test
    fun `AES-KDF 写侧逐键类型符合 KDBX4 规范（R 为 UInt64）`() {
        val params = KdfParameters.Aes(
            seed = ByteArray(32),
            rounds = 600_000L
        )

        val entries = parseEntries(serializeToBytes(params))
        assertEquals(0x42.toByte(), entries.getValue("S").first)
        assertEquals(0x05.toByte(), entries.getValue("R").first)
        assertEquals(8, entries.getValue("R").second.size)
    }

    @Test
    fun `UUID 键与 KDF 变体一致`() {
        val argon2id = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32)
        )
        val entries = parseEntries(serializeToBytes(argon2id))
        val uuid = KdbxUuid(entries.getValue("\$UUID").second)
        assertEquals(KdbxConstants.Kdf.ARGON2ID, uuid)
    }
}
