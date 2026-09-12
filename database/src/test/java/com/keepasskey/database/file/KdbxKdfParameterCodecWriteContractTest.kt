package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.exception.KdbxCorruptFileException
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
 *
 * ## D7 整改补充：读侧缺参数 fail-closed
 * 官方 `Argon2Kdf.Transform`（KeePass 2.61.1 `Argon2Kdf.cs:146-160`）对 `P` / `M` / `I` / `V`
 * 一律以 0 作默认再走范围检查 → **缺参数必然抛异常**。本仓原先静默填 `p=2 / m=64MiB / i=2 / v=0x13`，
 * 掩盖了「变体字典被裁剪 / 损坏」这一事实。现改为缺失任一键即抛
 * [KdbxCorruptFileException] 并点名缺键；下述用例锁定该 fail-closed 契约。
 * 同时锁定 P1-13 的**读侧类型宽容**契约不得回退（以 UInt64 写出的 `P` / `V` 仍按 UInt32 语义读）。
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

    // ================= D7：读侧缺参数 fail-closed =================

    /**
     * 构造 Argon2 变体字典（ARGON2ID）。
     *
     * - `null` 表示**故意缺该键**（用于 fail-closed 缺失参数用例）；
     * - `pAsUInt64` / `vAsUInt64` 模拟「以 UInt64 写出 P/V」的非规范编码，
     *   用于锁定读侧必须按 UInt32 语义窄化（P1-13 契约）。
     */
    private fun argon2VariantDict(
        p: Long? = 2L,
        m: Long? = 64L * 1024 * 1024,
        i: Long? = 2L,
        v: Long? = 0x13L,
        salt: ByteArray? = ByteArray(32),
        pAsUInt64: Boolean = false,
        vAsUInt64: Boolean = false
    ): ByteArray {
        val vd = VariantDictionary()
        vd.setByteArray("\$UUID", KdbxConstants.Kdf.ARGON2ID.toByteArray())
        salt?.let { vd.setByteArray("S", it) }
        p?.let { if (pAsUInt64) vd.setUInt64("P", it) else vd.setUInt32("P", it) }
        m?.let { vd.setUInt64("M", it) }
        i?.let { vd.setUInt64("I", it) }
        v?.let { if (vAsUInt64) vd.setUInt64("V", it) else vd.setUInt32("V", it) }
        return vd.toByteArray()
    }

    /**
     * 缺失 `P` / `M` / `I` / `V` 任一者都必须以 [KdbxCorruptFileException] 拒绝，
     * 且错误消息点名缺失的键。
     *
     * 回归意义：原实现为缺失项静默填入 `p=2 / m=64MiB / i=2 / v=0x13`（数据类默认值），
     * 使「被裁剪 / 损坏的变体字典」被伪装成「口令错误」——本用例锁定 fail-closed 语义，
     * 任何一处回退为默认值填充都会让对应用例失败。
     */
    @Test
    fun `Argon2 缺失 P M I V 任一参数均被拒绝且消息点名缺键`() {
        val cases = listOf(
            "P" to argon2VariantDict(p = null),
            "M" to argon2VariantDict(m = null),
            "I" to argon2VariantDict(i = null),
            "V" to argon2VariantDict(v = null)
        )
        for ((missingKey, bytes) in cases) {
            val ex = assertThrows(
                "缺失 Argon2 $missingKey 参数必须以损坏文件拒绝（官方 Argon2Kdf 为 fail-closed）",
                KdbxCorruptFileException::class.java
            ) { KdbxKdfParameterCodec.deserialize(bytes) }
            assertTrue(
                "错误消息须点名缺失的键 $missingKey，实际: ${ex.message}",
                ex.message?.contains("Argon2") == true && ex.message?.contains(missingKey) == true
            )
        }
    }

    /** 缺失 `$UUID` / `S` 同样拒绝（既有契约回归，防止 fail-closed 改动波及这两条）。 */
    @Test
    fun `Argon2 缺失 UUID 或 S 参数被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.deserialize(argon2VariantDict(salt = null))
        }
        val noUuid = VariantDictionary().apply {
            setByteArray("S", ByteArray(32))
            setUInt32("P", 2L)
            setUInt64("M", 8192L)
            setUInt64("I", 1L)
            setUInt32("V", 0x13L)
        }.toByteArray()
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.deserialize(noUuid)
        }
    }

    /**
     * P1-13 读侧契约不得回退：`P` / `V` 即便以 UInt64（0x05）编码，
     * 读侧仍按 **UInt32 语义**窄化取值（而非 Int64 直读）。
     */
    @Test
    fun `P 与 V 以 UInt64 编码时仍按 UInt32 语义读取`() {
        val params = KdbxKdfParameterCodec.deserialize(
            argon2VariantDict(p = 3L, v = 0x10L, pAsUInt64 = true, vAsUInt64 = true)
        ) as KdfParameters.Argon2

        assertEquals("P 须按 UInt32 语义读为 3", 3, params.parallelism)
        assertEquals("V 须按 UInt32 语义读为 0x10", 0x10, params.version)
    }

    /**
     * D7：官方下界 `M = 8192` 字节（`Argon2Kdf.MinMemory`）的合法库必须能读入。
     * 原下界 1 MiB 会在此误拒——本用例是该误拒缺陷的回归锁。
     */
    @Test
    fun `M 为官方下界 8192 的 Argon2 库读取合法`() {
        val params = KdbxKdfParameterCodec.deserialize(
            argon2VariantDict(p = 1L, m = 8192L, i = 1L)
        ) as KdfParameters.Argon2

        assertEquals(8192L, params.memoryInBytes)
        assertEquals(1L, params.iterations)
        assertEquals(1, params.parallelism)
    }

    /** `M` 低于官方下界（8191）必须拒绝。 */
    @Test
    fun `M 低于官方下界 8192 被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.deserialize(argon2VariantDict(m = 8191L))
        }
    }

    /**
     * 严格读侧的配套回归：写侧 `serialize` 必然写出全部 `P` / `M` / `I` / `V`，
     * 故「写 → 读」往返必须畅通——防止 fail-closed 改动把本仓自己的产物也拒掉。
     */
    @Test
    fun `Argon2 写侧产物可被严格读侧完整回读`() {
        val original = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32) { it.toByte() },
            parallelism = 3,
            memoryInBytes = 8192L,
            iterations = 5L,
            version = KdfParameters.Argon2.ARGON2_VERSION_13
        )

        assertEquals(original, KdbxKdfParameterCodec.deserialize(serializeToBytes(original)))
    }

    /** AES-KDF 缺 `S` / `R` 亦为 fail-closed（既有契约回归）。 */
    @Test
    fun `AES-KDF 缺失 S 或 R 参数被拒绝`() {
        val missingS = VariantDictionary().apply {
            setByteArray("\$UUID", KdbxConstants.Kdf.AES_KDF.toByteArray())
            setUInt64("R", 600_000L)
        }.toByteArray()
        val missingR = VariantDictionary().apply {
            setByteArray("\$UUID", KdbxConstants.Kdf.AES_KDF.toByteArray())
            setByteArray("S", ByteArray(32))
        }.toByteArray()

        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.deserialize(missingS)
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxKdfParameterCodec.deserialize(missingR)
        }
    }
}
