package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxUnsupportedVersionException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * KDBX 4 外层 Header 解析与序列化器
 */
data class KdbxHeader(
    val signature1: Int = KdbxConstants.Signature.SIGNATURE_1,
    val signature2: Int = KdbxConstants.Signature.SIGNATURE_2_KDBX,
    val version: Int = KdbxConstants.Version.VERSION_4_0,
    val cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC,
    val compression: Int = KdbxConstants.Compression.GZIP,
    val masterSeed: ByteArray = ByteArray(32),
    val encryptionIv: ByteArray = ByteArray(16),
    val kdfParameters: KdfParameters,
    val publicCustomData: VariantDictionary? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KdbxHeader) return false
        if (signature1 != other.signature1) return false
        if (signature2 != other.signature2) return false
        if (version != other.version) return false
        if (cipherUuid != other.cipherUuid) return false
        if (compression != other.compression) return false
        if (!masterSeed.contentEquals(other.masterSeed)) return false
        if (!encryptionIv.contentEquals(other.encryptionIv)) return false
        if (kdfParameters != other.kdfParameters) return false
        return true
    }

    override fun hashCode(): Int {
        var result = signature1
        result = 31 * result + signature2
        result = 31 * result + version
        result = 31 * result + cipherUuid.hashCode()
        result = 31 * result + compression
        result = 31 * result + masterSeed.contentHashCode()
        result = 31 * result + encryptionIv.contentHashCode()
        result = 31 * result + kdfParameters.hashCode()
        return result
    }

    fun serialize(outputStream: OutputStream): ByteArray {
        val headerBytesStream = ByteArrayOutputStream()

        // 签名与版本
        LittleEndianUtil.writeInt(headerBytesStream, signature1)
        LittleEndianUtil.writeInt(headerBytesStream, signature2)
        LittleEndianUtil.writeInt(headerBytesStream, version)

        // 字段 2: CipherID
        writeField(headerBytesStream, KdbxConstants.HeaderFieldId.CIPHER_ID, cipherUuid.toByteArray())

        // 字段 3: CompressionFlags
        writeField(headerBytesStream, KdbxConstants.HeaderFieldId.COMPRESSION_FLAGS, LittleEndianUtil.intTo4Bytes(compression))

        // 字段 4: MasterSeed
        writeField(headerBytesStream, KdbxConstants.HeaderFieldId.MASTER_SEED, masterSeed)

        // 字段 7: EncryptionIV
        writeField(headerBytesStream, KdbxConstants.HeaderFieldId.ENCRYPTION_IV, encryptionIv)

        // 字段 11: KdfParameters
        val kdfVd = KdbxKdfParameterCodec.serialize(kdfParameters)
        writeField(headerBytesStream, KdbxConstants.HeaderFieldId.KDF_PARAMETERS, kdfVd.toByteArray())

        // 字段 12: PublicCustomData (可选)
        if (publicCustomData != null) {
            writeField(headerBytesStream, KdbxConstants.HeaderFieldId.PUBLIC_CUSTOM_DATA, publicCustomData.toByteArray())
        }

        // 字段 0: EndOfHeader —— 官方格式带 4 字节 \r\n\r\n 数据（对齐 KeePass 2.x WriteHeaderField），
        // 头部 SHA-256 / HMAC 覆盖含该数据的完整头部
        writeField(
            headerBytesStream,
            KdbxConstants.HeaderFieldId.END_OF_HEADER,
            byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)
        )

        val headerBytes = headerBytesStream.toByteArray()
        outputStream.write(headerBytes)
        return headerBytes
    }

    companion object {
        private val secureRandom = SecureRandom()

        /**
         * 外层 Header 单字段长度安全上限（1 MiB）。
         * 头部位于 SHA-256 / HMAC 认证之前，fieldLen 完全不可信（P0-5）：
         * 合法字段远小于此上限（End of Header 4B、种子 32B、IV 16B、KDF 参数与种子数 KB 内），
         * 超限长度在分配前即按损坏文件拒绝。
         */
        internal const val MAX_HEADER_FIELD_BYTES = 1024 * 1024

        /**
         * 外层 Header **累计字节数**安全上限（4 MiB，F-11 加固）。
         *
         * 取值依据：合法 KDBX4 头部仅数 KB 量级——本仓 [serialize] 写出的头部实测
         * 恒 < 3 KiB（16B CipherID + 4B CompressionFlags + 32B MasterSeed + 12/16B IV +
         * 数 KB KDF 参数 + 可选公有自定义数据 + 12B 签名版本 + 9B 收尾字段）；
         * 即便第三方客户端塞入大块 PublicCustomData，距本上限仍有三个数量级余量。
         * 因此本值是「合法库绝不触及、恶意库无法绕过」的紧界。
         *
         * 为什么必须有累计闸门（仅靠 [MAX_HEADER_FIELD_BYTES] 不足）：
         * 单字段上限只约束「一个字段」，攻击者可用海量合法尺寸字段让记录流无界增长；
         * 且**该检查发生在头部 SHA-256 / HMAC 认证之前**，不需要任何口令或密钥文件，
         * 是本仓唯一免凭据的解析期内存耗尽（DoS）面。故此处按 fail-closed 加固：
         * 一旦累计写入将越过本上限，立即以 [KdbxCorruptFileException] 拒绝，
         * 且**绝不允许先写入再判定**（写入受同一预算约束，见 deserialize 内的 record 闸门）。
         */
        internal const val MAX_HEADER_TOTAL_BYTES = 4 * 1024 * 1024

        /**
         * 外层 Header **字段数**安全上限（F-11 加固）。
         *
         * 取值依据：官方 KeePass 2.61.1 写入的 KDBX4 头部字段数恒为 6
         * （CipherID / CompressionFlags / MasterSeed / EncryptionIV / KdfParameters / EndOfHeader，
         * 可选 PublicCustomData 时为 7）；本仓 [serialize] 恒写 6 或 7 个。
         * 64 为两个数量级余量，足以容纳任何第三方客户端的合法扩展，同时使
         * 「不写入数据、只靠 5 字节字段头反复堆积」的零成本内存放大攻击立即失效。
         *
         * 与本上限同源的 [MAX_HEADER_TOTAL_BYTES] 共同构成认证前的双闸门；语义与取值依据见该类 KDoc。
         */
        internal const val MAX_HEADER_FIELD_COUNT = 64

        /** MasterSeed 合法长度（官方规范：32 字节） */
        private const val MASTER_SEED_SIZE = 32

        /** CompressionFlags 字段合法长度（小端 Int32） */
        private const val COMPRESSION_FIELD_SIZE = 4

        fun createDefault(
            cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC,
            useArgon2: Boolean = true
        ): KdbxHeader {
            val masterSeed = ByteArray(32)
            // IV 长度按算法官方规范生成（P2-8 配套）：ChaCha20 → 12B nonce，AES/Twofish → 16B，
            // 保证新建文件天然通过 [validateEncryptionIvSize] 校验
            val encryptionIv = ByteArray(
                if (cipherUuid == KdbxConstants.Cipher.CHACHA20) {
                    KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH
                } else {
                    KdbxConstants.Cipher.BLOCK_CIPHER_IV_LENGTH
                }
            )
            secureRandom.nextBytes(masterSeed)
            secureRandom.nextBytes(encryptionIv)

            val kdfParams = if (useArgon2) {
                val salt = ByteArray(32)
                secureRandom.nextBytes(salt)
                KdfParameters.Argon2(
                    type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
                    salt = salt,
                    parallelism = 2,
                    memoryInBytes = 64L * 1024 * 1024,
                    iterations = 2L
                )
            } else {
                val seed = ByteArray(32)
                secureRandom.nextBytes(seed)
                KdfParameters.Aes(
                    seed = seed,
                    rounds = KdbxConstants.Kdf.DEFAULT_AES_KDF_ROUNDS
                )
            }

            return KdbxHeader(
                cipherUuid = cipherUuid,
                masterSeed = masterSeed,
                encryptionIv = encryptionIv,
                kdfParameters = kdfParams
            )
        }

        fun deserialize(inputStream: InputStream): Pair<KdbxHeader, ByteArray> {
            val recordingStream = ByteArrayOutputStream()

            /**
             * F-11：记录流写入的唯一闸门——先按「写入后」的总量裁决，通过才写入。
             * 严禁先写后判：那是先分配再拒绝，闸门本身就成了 OOM 通道。
             */
            fun record(bytes: ByteArray) {
                if (recordingStream.size() + bytes.size > MAX_HEADER_TOTAL_BYTES) {
                    throw KdbxCorruptFileException(
                        "外层 Header 累计字节数超过安全上限: 将达 ${recordingStream.size() + bytes.size}" +
                                "（上限 $MAX_HEADER_TOTAL_BYTES 字节）——认证前 fail-closed 加固判定为损坏文件"
                    )
                }
                recordingStream.write(bytes)
            }

            fun record(byte: Int) {
                if (recordingStream.size() + 1 > MAX_HEADER_TOTAL_BYTES) {
                    throw KdbxCorruptFileException(
                        "外层 Header 累计字节数超过安全上限: 将达 ${recordingStream.size() + 1}" +
                                "（上限 $MAX_HEADER_TOTAL_BYTES 字节）——认证前 fail-closed 加固判定为损坏文件"
                    )
                }
                recordingStream.write(byte)
            }

            fun readAndRecordInt(): Int {
                val b = LittleEndianUtil.readBytes(inputStream, 4)
                record(b)
                return LittleEndianUtil.bytesToInt(b)
            }

            val sig1 = readAndRecordInt()
            val sig2 = readAndRecordInt()
            if (sig1 != KdbxConstants.Signature.SIGNATURE_1 ||
                (sig2 != KdbxConstants.Signature.SIGNATURE_2_KDBX &&
                        sig2 != KdbxConstants.Signature.SIGNATURE_2_KDBX_OLD &&
                        sig2 != KdbxConstants.Signature.SIGNATURE_2_KDBX_PRE)
            ) {
                throw KdbxCorruptFileException("非法的 KDBX 文件魔数签名: 0x${Integer.toHexString(sig1)}, 0x${Integer.toHexString(sig2)}")
            }

            val version = readAndRecordInt()
            val major = version and KdbxConstants.Version.VERSION_MAJOR_MASK
            if (major != KdbxConstants.Version.VERSION_4_0) {
                throw KdbxUnsupportedVersionException("不支持 KDBX v4 之前的版本")
            }

            var cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
            var compression: Int = KdbxConstants.Compression.GZIP
            var masterSeed: ByteArray? = null
            var encryptionIv: ByteArray? = null
            var kdfParams: KdfParameters? = null
            var publicCustomData: VariantDictionary? = null

            /** F-11：已消费的字段数（含 EndOfHeader 字段），受 [MAX_HEADER_FIELD_COUNT] 约束 */
            var fieldCount = 0
            while (true) {
                // F-11：字段数闸门同样位于认证之前——5 字节字段头即可占一个字段位，
                // 若不设限，攻击者可零成本构造海量空字段驱动解析循环与记录流无界增长
                if (fieldCount + 1 > MAX_HEADER_FIELD_COUNT) {
                    throw KdbxCorruptFileException(
                        "外层 Header 字段数超过安全上限: ${fieldCount + 1}（上限 $MAX_HEADER_FIELD_COUNT）" +
                                "——认证前 fail-closed 加固判定为损坏文件"
                    )
                }
                fieldCount++

                val fieldIdByte = inputStream.read()
                if (fieldIdByte < 0) throw KdbxCorruptFileException("意外到达头部流末尾")
                record(fieldIdByte)

                val fieldLenBytes = LittleEndianUtil.readBytes(inputStream, 4)
                record(fieldLenBytes)
                val fieldLen = LittleEndianUtil.bytesToInt(fieldLenBytes)

                // P0-5：fieldLen 来自未认证输入，必须在 ByteArray 分配前通过边界裁决，
                // 否则恶意长度（0xFFFFFFFF 负数 / 0x7FFFFFFF 超大值）直接造成崩溃或 OOM
                if (fieldLen < 0 || fieldLen > MAX_HEADER_FIELD_BYTES) {
                    throw KdbxCorruptFileException(
                        "头部字段长度非法或超过安全上限: fieldId=$fieldIdByte, length=$fieldLen（允许 0 ~ $MAX_HEADER_FIELD_BYTES）"
                    )
                }

                // F-11：累计预算必须在**读取字段数据之前**裁决——fieldLen 是未认证声明值，
                // 先按声明长度分配读取再判断预算，预算便失去约束分配的意义
                if (recordingStream.size() + fieldLen > MAX_HEADER_TOTAL_BYTES) {
                    throw KdbxCorruptFileException(
                        "外层 Header 累计字节数超过安全上限: 字段数据前已达 ${recordingStream.size()} 字节，" +
                                "本字段声明 $fieldLen 字节（上限 $MAX_HEADER_TOTAL_BYTES 字节）——认证前 fail-closed 加固判定为损坏文件"
                    )
                }

                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen, MAX_HEADER_FIELD_BYTES)
                record(fieldData)

                val fieldId = fieldIdByte.toByte()
                if (fieldId == KdbxConstants.HeaderFieldId.END_OF_HEADER) {
                    break
                }

                when (fieldId) {
                    KdbxConstants.HeaderFieldId.CIPHER_ID -> {
                        if (fieldData.size != KdbxUuid.UUID_SIZE) {
                            throw KdbxCorruptFileException(
                                "CipherID 头字段长度非法: ${fieldData.size}（期望 ${KdbxUuid.UUID_SIZE}）"
                            )
                        }
                        cipherUuid = KdbxUuid(fieldData)
                    }
                    KdbxConstants.HeaderFieldId.COMPRESSION_FLAGS -> {
                        if (fieldData.size != COMPRESSION_FIELD_SIZE) {
                            throw KdbxCorruptFileException(
                                "CompressionFlags 头字段长度非法: ${fieldData.size}（期望 $COMPRESSION_FIELD_SIZE）"
                            )
                        }
                        compression = LittleEndianUtil.bytesToInt(fieldData)
                        if (compression != KdbxConstants.Compression.NONE &&
                            compression != KdbxConstants.Compression.GZIP
                        ) {
                            throw KdbxCorruptFileException("未知的压缩算法标识: $compression")
                        }
                    }
                    KdbxConstants.HeaderFieldId.MASTER_SEED -> {
                        if (fieldData.size != MASTER_SEED_SIZE) {
                            throw KdbxCorruptFileException(
                                "MasterSeed 头字段长度非法: ${fieldData.size}（期望 $MASTER_SEED_SIZE）"
                            )
                        }
                        masterSeed = fieldData
                    }
                    KdbxConstants.HeaderFieldId.ENCRYPTION_IV -> {
                        encryptionIv = fieldData
                    }
                    KdbxConstants.HeaderFieldId.KDF_PARAMETERS -> {
                        kdfParams = KdbxKdfParameterCodec.deserialize(fieldData)
                    }
                    KdbxConstants.HeaderFieldId.PUBLIC_CUSTOM_DATA -> {
                        publicCustomData = VariantDictionary.deserialize(fieldData)
                    }
                }
            }

            val header = KdbxHeader(
                signature1 = sig1,
                signature2 = sig2,
                version = version,
                cipherUuid = cipherUuid,
                compression = compression,
                masterSeed = masterSeed ?: throw KdbxCorruptFileException("缺少 MasterSeed 头字段"),
                encryptionIv = encryptionIv ?: throw KdbxCorruptFileException("缺少 EncryptionIV 头字段"),
                kdfParameters = kdfParams ?: throw KdbxCorruptFileException("缺少 KdfParameters 头字段"),
                publicCustomData = publicCustomData
            )

            // P2-8：EncryptionIV 合法长度依赖 CipherID 字段，且字段出现顺序不作保证，
            // 必须在全部字段解析完成后统一裁决
            validateEncryptionIvSize(header.cipherUuid, header.encryptionIv)

            return Pair(header, recordingStream.toByteArray())
        }

        /**
         * 外层 Header 字段间语义校验（P2-8）：
         * 按官方规范校验 EncryptionIV 长度——ChaCha20 为 12 字节、AES-256-CBC / Twofish 为 16 字节；
         * 未知加密算法不在本校验范围（由 CipherFactory 在解密期以类型化异常裁决）。
         */
        internal fun validateEncryptionIvSize(cipherUuid: KdbxUuid, encryptionIv: ByteArray) {
            val expectedSize = when (cipherUuid) {
                KdbxConstants.Cipher.CHACHA20 -> KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH
                KdbxConstants.Cipher.AES_256_CBC, KdbxConstants.Cipher.TWOFISH ->
                    KdbxConstants.Cipher.BLOCK_CIPHER_IV_LENGTH
                else -> return
            }
            if (encryptionIv.size != expectedSize) {
                throw KdbxCorruptFileException(
                    "EncryptionIV 头字段长度非法: ${encryptionIv.size}" +
                            "（算法 ${cipherUuid.toFormattedString()} 期望 $expectedSize）"
                )
            }
        }

        private fun writeField(outputStream: OutputStream, fieldId: Byte, data: ByteArray) {
            outputStream.write(fieldId.toInt())
            LittleEndianUtil.writeInt(outputStream, data.size)
            if (data.isNotEmpty()) {
                outputStream.write(data)
            }
        }

        /**
         * KDF 参数上界校验（对照 KeePassDX Limits / KeePassXC 参数封顶语义）。
         *
         * ISSUE-P3-29：实现已拆至 [KdbxKdfParameterCodec]；本门面保持同签名与可见性，
         * 既有调用方（含单测）零改动。
         */
        internal fun validateArgon2Bounds(memoryInBytes: Long, iterations: Long, parallelism: Int, version: Int) =
            KdbxKdfParameterCodec.validateArgon2Bounds(memoryInBytes, iterations, parallelism, version)

        internal fun validateAesKdfBounds(rounds: Long) = KdbxKdfParameterCodec.validateAesKdfBounds(rounds)
    }
}
