package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.LittleEndianUtil
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * P0-5 / P2-8 整改回归：外层 Header 字段长度与语义安全边界。
 * 恶意 fieldLen（负数 / 0x7FFFFFFF / 超 1 MiB）必须在 ByteArray 分配前以类型化
 * [KdbxCorruptFileException] 拒绝；masterSeed（32B）、encryptionIv
 * （ChaCha20=12B / AES・Twofish=16B）、cipherId（16B）、compression 按 KDBX4 官方规范校验。
 */
class KdbxHeaderFieldSecurityTest {

    /** 合法 AES-KDF 参数变体字典（$UUID + S + R） */
    private fun aesKdfParameterBytes(): ByteArray {
        val vd = VariantDictionary()
        vd.setByteArray("\$UUID", KdbxConstants.Kdf.AES_KDF.toByteArray())
        vd.setByteArray("S", ByteArray(32))
        vd.setUInt64("R", 6_000_000L)
        return vd.toByteArray()
    }

    /**
     * 构造头部字节流。字段以 (fieldId, 声明长度, 实际数据) 三元组描述，
     * 声明长度可与实际数据脱钩以模拟畸形文件。
     */
    private fun headerBytes(fields: List<Triple<Int, Int, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        LittleEndianUtil.writeInt(bos, KdbxConstants.Signature.SIGNATURE_1)
        LittleEndianUtil.writeInt(bos, KdbxConstants.Signature.SIGNATURE_2_KDBX)
        LittleEndianUtil.writeInt(bos, KdbxConstants.Version.VERSION_4_0)
        for ((fieldId, declaredLength, data) in fields) {
            bos.write(fieldId)
            LittleEndianUtil.writeInt(bos, declaredLength)
            bos.write(data)
        }
        // End of Header（官方 4 字节 \r\n\r\n）
        bos.write(KdbxConstants.HeaderFieldId.END_OF_HEADER.toInt())
        LittleEndianUtil.writeInt(bos, 4)
        bos.write(byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A))
        return bos.toByteArray()
    }

    private fun defaultValidFields(
        cipher: ByteArray = KdbxConstants.Cipher.AES_256_CBC.toByteArray(),
        compression: ByteArray = LittleEndianUtil.intTo4Bytes(KdbxConstants.Compression.GZIP),
        masterSeed: ByteArray = ByteArray(32),
        encryptionIv: ByteArray = ByteArray(16)
    ): List<Triple<Int, Int, ByteArray>> {
        val kdf = aesKdfParameterBytes()
        return listOf(
            Triple(KdbxConstants.HeaderFieldId.CIPHER_ID.toInt(), cipher.size, cipher),
            Triple(KdbxConstants.HeaderFieldId.COMPRESSION_FLAGS.toInt(), compression.size, compression),
            Triple(KdbxConstants.HeaderFieldId.MASTER_SEED.toInt(), masterSeed.size, masterSeed),
            Triple(KdbxConstants.HeaderFieldId.ENCRYPTION_IV.toInt(), encryptionIv.size, encryptionIv),
            Triple(KdbxConstants.HeaderFieldId.KDF_PARAMETERS.toInt(), kdf.size, kdf)
        )
    }

    private fun deserialize(bytes: ByteArray) =
        KdbxHeader.deserialize(ByteArrayInputStream(bytes))

    // ================= P0-5：fieldLen 边界 =================

    @Test
    fun `负数 fieldLen 被类型化异常拒绝而非 NegativeArraySizeException`() {
        // MasterSeed 字段声明 0xFFFFFFFF（即 -1）长度
        val bytes = headerBytes(defaultValidFields().map { (id, _, data) ->
            if (id == KdbxConstants.HeaderFieldId.MASTER_SEED.toInt()) Triple(id, -1, ByteArray(0))
            else Triple(id, data.size, data)
        })
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `超大 fieldLen 0x7FFFFFFF 被类型化异常拒绝而非 OOM`() {
        val bytes = headerBytes(defaultValidFields().map { (id, _, data) ->
            if (id == KdbxConstants.HeaderFieldId.MASTER_SEED.toInt()) Triple(id, 0x7FFFFFFF, ByteArray(0))
            else Triple(id, data.size, data)
        })
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `超过 1MiB 安全上限的 fieldLen 被拒绝`() {
        val bytes = headerBytes(defaultValidFields().map { (id, _, data) ->
            if (id == KdbxConstants.HeaderFieldId.MASTER_SEED.toInt()) {
                Triple(id, KdbxHeader.MAX_HEADER_FIELD_BYTES + 1, ByteArray(0))
            } else {
                Triple(id, data.size, data)
            }
        })
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `恰在上限内的声明长度数据不足时抛 EOF 而非 OOM`() {
        // COMMENT 字段声明 1MiB（上限内）但流中无数据：分配受控，按 EOF 拒绝
        val fields = defaultValidFields() + Triple(
            KdbxConstants.HeaderFieldId.COMMENT.toInt(),
            KdbxHeader.MAX_HEADER_FIELD_BYTES,
            ByteArray(0)
        )
        assertThrows(EOFException::class.java) { deserialize(headerBytes(fields)) }
    }

    // ================= F-11：头部累计总量 / 字段数闸门（认证前 fail-closed） =================

    /**
     * F-11 AC①：字段数超限必须在**认证之前**被类型化拒绝。
     * 每个空 COMMENT 字段仅 5 字节，攻击者可零成本堆积海量字段驱动记录流无界增长。
     */
    @Test
    fun `头部字段数超过安全上限被拒绝`() {
        val emptyFields = (0 until KdbxHeader.MAX_HEADER_FIELD_COUNT).map {
            Triple(KdbxConstants.HeaderFieldId.COMMENT.toInt(), 0, ByteArray(0))
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            deserialize(headerBytes(emptyFields))
        }
    }

    /** F-11 反向闸门：字段数恰好等于上限（含 EndOfHeader）时不得误伤合法头部 */
    @Test
    fun `字段数恰在上限内的头部正常解析`() {
        val fields = defaultValidFields() + (0 until KdbxHeader.MAX_HEADER_FIELD_COUNT - 6).map {
            Triple(KdbxConstants.HeaderFieldId.COMMENT.toInt(), 0, ByteArray(0))
        }
        val (header, recordedBytes) = deserialize(headerBytes(fields))
        assertEquals(KdbxConstants.Cipher.AES_256_CBC, header.cipherUuid)
        assertTrue(recordedBytes.isNotEmpty())
    }

    /**
     * F-11 AC①（总量）：单字段均在 1 MiB 单字段上限内，但累计写入将越过总上限——
     * 必须在写入/分配前以 [KdbxCorruptFileException] 拒绝，而非驱动 OOM。
     */
    @Test
    fun `头部累计字节数超过总上限被拒绝`() {
        // 5 个 1 MiB 字段：每个都合法（== 单字段上限），累计却已越过 4 MiB 总上限
        val bigFields = (0 until 5).map {
            Triple(KdbxConstants.HeaderFieldId.COMMENT.toInt(), 1024 * 1024, ByteArray(1024 * 1024))
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            deserialize(headerBytes(bigFields))
        }
    }

    /**
     * F-11 关键语义：总量闸门必须在**读取字段数据之前**依据未认证的声明长度裁决。
     *
     * 构造按「声明长度与实际数据脱钩」的畸形头部：每个字段声明 1 MiB（单字段上限内），
     * 但流中**完全没有**对应数据。若闸门写在读取之后，本用例只会抛 EOFException；
     * 只有在读取前依据声明长度裁决，才会得到类型化 [KdbxCorruptFileException]。
     */
    @Test
    fun `声明长度将使累计越界时在读取数据前即拒绝`() {
        // 构造要点：前 3 个字段**带真实数据**（各 1 MiB）把累计预算推到 3 MiB 级，
        // 第 4 个字段只声明 1 MiB 而流中没有任何数据：
        //   累计 ≈ 12 + 3×(1+4+1 MiB) + (1+4) = 3,145,760，再加声明 1 MiB = 4,194,336 > 4 MiB 上限
        // 若闸门在**读取数据之后**才判，第 4 个字段会先抛 EOFException；
        // 只有"依据未认证声明长度、在读取前裁决"才会得到含「累计字节数」的类型化异常。
        // （原用例让**每个**字段都声明 1 MiB 却零数据 → 第 1 个字段就读到流末尾，
        //   累计预算根本没有机会推进，属测试构造错误。）
        val realData = ByteArray(KdbxHeader.MAX_HEADER_FIELD_BYTES)
        val overBudget = (0 until 3).map {
            Triple(KdbxConstants.HeaderFieldId.COMMENT.toInt(), KdbxHeader.MAX_HEADER_FIELD_BYTES, realData)
        } + Triple(KdbxConstants.HeaderFieldId.COMMENT.toInt(), KdbxHeader.MAX_HEADER_FIELD_BYTES, ByteArray(0))

        val failure = assertThrows(KdbxCorruptFileException::class.java) {
            deserialize(headerBytes(overBudget))
        }
        assertTrue(
            "异常必须来自「累计字节数」预算闸门而非 EOF（证明裁决先于数据读取）：${failure.message}",
            failure.message!!.contains("累计字节数")
        )
    }

    /** F-11 反向闸门：合法头部（数 KB）距总上限有三个数量级余量，绝不误伤 */
    @Test
    fun `合法头部不受总量与字段数闸门影响`() {
        // 一个 256 KiB 的 COMMENT 字段：远高于任何真实库的头部体积，仍在上限内
        val bigComment = ByteArray(256 * 1024) { 0x5A }
        val fields = defaultValidFields() + Triple(
            KdbxConstants.HeaderFieldId.COMMENT.toInt(),
            bigComment.size,
            bigComment
        )
        val (header, recordedBytes) = deserialize(headerBytes(fields))
        assertEquals(KdbxConstants.Cipher.AES_256_CBC, header.cipherUuid)
        assertTrue("大块头部字段仍应完整进入认证覆盖范围", recordedBytes.size > bigComment.size)
    }

    // ================= P2-8：字段语义校验 =================

    @Test
    fun `MasterSeed 长度非 32 字节被拒绝`() {
        for (badSeed in listOf(ByteArray(31), ByteArray(33))) {
            val bytes = headerBytes(defaultValidFields(masterSeed = badSeed))
            assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
        }
    }

    @Test
    fun `AES 库 EncryptionIV 长度非 16 字节被拒绝`() {
        for (badIv in listOf(ByteArray(15), ByteArray(17))) {
            val bytes = headerBytes(defaultValidFields(encryptionIv = badIv))
            assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
        }
    }

    @Test
    fun `ChaCha20 库 EncryptionIV 长度非 12 字节被拒绝`() {
        // ChaCha20 携带 16B IV（AES 长度）应被拒绝
        val bytes = headerBytes(
            defaultValidFields(
                cipher = KdbxConstants.Cipher.CHACHA20.toByteArray(),
                encryptionIv = ByteArray(16)
            )
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `Twofish 库 EncryptionIV 长度非 16 字节被拒绝`() {
        val bytes = headerBytes(
            defaultValidFields(
                cipher = KdbxConstants.Cipher.TWOFISH.toByteArray(),
                encryptionIv = ByteArray(12)
            )
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `CipherID 长度非 16 字节被拒绝`() {
        val bytes = headerBytes(defaultValidFields(cipher = ByteArray(15)))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `CompressionFlags 长度非 4 字节被拒绝`() {
        val bytes = headerBytes(defaultValidFields(compression = ByteArray(3)))
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `未知压缩算法标识被拒绝`() {
        val bytes = headerBytes(
            defaultValidFields(compression = LittleEndianUtil.intTo4Bytes(7))
        )
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(bytes) }
    }

    @Test
    fun `缺少 MasterSeed 头字段被拒绝`() {
        val fields = defaultValidFields().filter {
            it.first != KdbxConstants.HeaderFieldId.MASTER_SEED.toInt()
        }
        assertThrows(KdbxCorruptFileException::class.java) { deserialize(headerBytes(fields)) }
    }

    // ================= 合法头部不受影响 =================

    @Test
    fun `合法 AES 头部完整解析`() {
        val seed = ByteArray(32) { 0x11 }
        val iv = ByteArray(16) { 0x22 }
        val (header, recordedBytes) =
            deserialize(headerBytes(defaultValidFields(masterSeed = seed, encryptionIv = iv)))
        assertEquals(KdbxConstants.Cipher.AES_256_CBC, header.cipherUuid)
        assertEquals(KdbxConstants.Compression.GZIP, header.compression)
        assertArrayEquals(seed, header.masterSeed)
        assertArrayEquals(iv, header.encryptionIv)
        // 记录流必须覆盖完整头部（供 SHA-256 / HMAC 认证）
        assertTrue(recordedBytes.isNotEmpty())
    }

    @Test
    fun `合法 ChaCha20 头部以 12 字节 IV 完整解析`() {
        val (header, _) = deserialize(
            headerBytes(
                defaultValidFields(
                    cipher = KdbxConstants.Cipher.CHACHA20.toByteArray(),
                    encryptionIv = ByteArray(12)
                )
            )
        )
        assertEquals(KdbxConstants.Cipher.CHACHA20, header.cipherUuid)
        assertEquals(12, header.encryptionIv.size)
    }
}
