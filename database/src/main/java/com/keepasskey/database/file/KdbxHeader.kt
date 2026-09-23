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
 *
 * ### ISSUE-P3-143（第四轮复核 NEW-B01-6）：本相等性是**秘密不敏感**语义
 *
 * [equals]（[kdfParameters] 一行）**传递**比较 [KdfParameters]，而
 * `KdfParameters.Argon2.equals` / `hashCode` 刻意忽略 KDBX4 VariantDictionary 的
 * `K`（`secretKey`，秘密材料）与 `A`（`associatedData`）⇒ **两个 KDF secret 不同、
 * 其余字段相同的头部会被判为相等**（含 [hashCode] 相同）。
 *
 * 该忽略在 `KdfParameters` 侧是有意设计（`secretKey` 是 `var`，
 * `clearSensitive()` 就地置 null；纳入哈希会破坏清零后的哈希稳定性）。
 * 缺口在于「秘密不敏感相等性」**经本类对外暴露**——[KdbxDatabase] 是 `data class`
 * 且持有本类型，而 `core.database` 恰是 `MutableStateFlow`（`SessionCore.kt:21`，
 * StateFlow 按 `equals` 合并）⇒ 未来若出现「仅 `secretKey` / `associatedData` 变化」
 * 的赋值或集合去重路径，相应赋值 / 去重会被**静默吞掉**。
 *
 * **当前不可达（故本条为 P3/INFO 而非漏洞）**：`secretKey` 仅由反序列化赋值
 * （`KdbxKdfParameterCodec.deserialize`），且保存会刷新 KDF salt / seed
 * （`KdbxFile.save`）⇒ 不存在「其余字段全等、仅 secret 不同」的生产赋值路径。
 *
 * ⚠ **禁止**把本相等性用于「凭据 / 秘密材料是否变化」一类裁决（如 `old != new`
 * 决定是否重建会话 / 重新派生 / 失效缓存），也不得据此对含本类型实例的集合去重。
 * **未来接入约束**：若确有「仅 secret 变化」的路径，须改为**显式变更标记**
 * （如会话级 `revision`）驱动裁决，**不得**依赖结构相等，也不得为迁就它把
 * `secretKey` 纳入 `equals` / `hashCode`（那会引入「清零后哈希变化」的新缺陷）。
 *
 * 该语义由 `database` 模块回归用例锁定，见
 * `src/test/java/com/keepasskey/database/file/KdbxHeaderSecretInsensitiveEqualityTest.kt`。
 */
data class KdbxHeader(
    val signature1: Int = KdbxConstants.Signature.SIGNATURE_1,
    val signature2: Int = KdbxConstants.Signature.SIGNATURE_2_KDBX,
    // ISSUE-P2-266：新建库声明 KDBX 4.1（0x00040001）——写出的 XML 恒含 4.1 专有元素
    // （SettingsChanged 等，见 KdbxXmlMetaSerializer / KdbxXmlEntrySerializer），
    // 版本声明必须与之匹配，禁止「声明 4.0、夹带 4.1」；对齐官方 KeePass 2.53+ / KeePassXC
    // 新写文件即 4.1 的口径。读取侧仅校验 major，4.0 / 4.1 路径完全一致。
    // 注意：反序列化路径不走本默认值（deserialize 经 toHeader 原样传入读到的 version），
    // 故既有 4.0 库往返保留原版本，缺陷面仅限新建库。
    val version: Int = KdbxConstants.Version.VERSION_4_1,
    val cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC,
    val compression: Int = KdbxConstants.Compression.GZIP,
    val masterSeed: ByteArray = ByteArray(32),
    val encryptionIv: ByteArray = ByteArray(16),
    val kdfParameters: KdfParameters,
    val publicCustomData: VariantDictionary? = null
) {
    /**
     * ISSUE-P3-143：**秘密不敏感**相等性——[kdfParameters] 传递比较时，
     * `KdfParameters.Argon2` 刻意忽略 `secretKey`（`K`）与 `associatedData`（`A`）。
     * 理由、禁用场景与未来接入约束见类 KDoc。
     */
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

    /**
     * ISSUE-P3-143：与 [equals] 同源的**秘密不敏感**哈希——经 [kdfParameters]
     * 间接忽略 `secretKey` / `associatedData`，故 KDF secret 清零前后哈希值稳定。
     */
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
            END_OF_HEADER_MARKER
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

        /** EndOfHeader 字段的固定数据（官方规范 `\r\n\r\n`，对齐 KeePass 2.x WriteHeaderField） */
        private val END_OF_HEADER_MARKER = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

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
            val recorder = HeaderRecorder(inputStream)

            val sig1 = recorder.readAndRecordInt()
            val sig2 = recorder.readAndRecordInt()
            if (sig1 != KdbxConstants.Signature.SIGNATURE_1 ||
                (sig2 != KdbxConstants.Signature.SIGNATURE_2_KDBX &&
                        sig2 != KdbxConstants.Signature.SIGNATURE_2_KDBX_OLD &&
                        sig2 != KdbxConstants.Signature.SIGNATURE_2_KDBX_PRE)
            ) {
                throw KdbxCorruptFileException("非法的 KDBX 文件魔数签名: 0x${Integer.toHexString(sig1)}, 0x${Integer.toHexString(sig2)}")
            }

            val version = recorder.readAndRecordInt()
            validateVersion(version)

            val fields = OuterHeaderFields()
            // F-11：字段数闸门同样位于认证之前——5 字节字段头即可占一个字段位，
            // 若不设限，攻击者可零成本构造海量空字段驱动解析循环与记录流无界增长
            var fieldCount = 0
            while (true) {
                if (fieldCount + 1 > MAX_HEADER_FIELD_COUNT) {
                    throw KdbxCorruptFileException(
                        "外层 Header 字段数超过安全上限: ${fieldCount + 1}（上限 $MAX_HEADER_FIELD_COUNT）" +
                                "——认证前 fail-closed 加固判定为损坏文件"
                    )
                }
                fieldCount++

                val (fieldId, fieldData) = recorder.readField()
                if (fieldId == KdbxConstants.HeaderFieldId.END_OF_HEADER) break
                fields.accept(fieldId, fieldData)
            }

            val header = fields.toHeader(sig1, sig2, version)
            // P2-8：EncryptionIV 合法长度依赖 CipherID 字段，且字段出现顺序不作保证，
            // 必须在全部字段解析完成后统一裁决
            validateEncryptionIvSize(header.cipherUuid, header.encryptionIv)

            return Pair(header, recorder.bytes())
        }

        /**
         * ISSUE-P3-126③：**版本策略显式声明为「仅校验 major」**。
         * 4.x 的 minor 递增只引入本仓不依赖的可选特性（本仓读取路径对 4.0 / 4.1 完全一致），
         * 故 minor 原样接受、**不**据此拒绝文件——拒之反而会打不开官方新写的库。
         * 该声明用于消除「看起来校验了版本」的误读：`KdbxConstants.Version` 中的 `VERSION_4_1`
         * 是**写侧**常量（新建库声明 4.1，见 ISSUE-P2-266），读取侧不按 minor 分流。
         */
        private fun validateVersion(version: Int) {
            val major = version and KdbxConstants.Version.VERSION_MAJOR_MASK
            if (major != KdbxConstants.Version.VERSION_4_0) {
                throw KdbxUnsupportedVersionException("不支持 KDBX v4 之前的版本")
            }
        }

        /**
         * F-11：外层 Header 的记录流与认证前预算闸门。
         *
         * [record] 是记录流写入的唯一闸门——先按「写入后」的总量裁决，通过才写入；
         * 严禁先写后判：那是先分配再拒绝，闸门本身就成了 OOM 通道。
         */
        private class HeaderRecorder(private val inputStream: InputStream) {
            private val recordingStream = ByteArrayOutputStream()

            /** 已记录的原始头部字节（参与后续 SHA-256 / HMAC 校验，故须逐字保真） */
            fun bytes(): ByteArray = recordingStream.toByteArray()

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

            /**
             * 读取一个头字段，返回 `(fieldId, fieldData)`。
             *
             * P0-5：`fieldLen` 来自未认证输入，必须在 `ByteArray` 分配前通过边界裁决，
             * 否则恶意长度（0xFFFFFFFF 负数 / 0x7FFFFFFF 超大值）直接造成崩溃或 OOM。
             * F-11：累计预算必须在**读取字段数据之前**裁决——`fieldLen` 是未认证声明值，
             * 先按声明长度分配读取再判断预算，预算便失去约束分配的意义。
             */
            fun readField(): Pair<Byte, ByteArray> {
                val fieldIdByte = inputStream.read()
                if (fieldIdByte < 0) throw KdbxCorruptFileException("意外到达头部流末尾")
                record(fieldIdByte)

                val fieldLenBytes = LittleEndianUtil.readBytes(inputStream, 4)
                record(fieldLenBytes)
                val fieldLen = LittleEndianUtil.bytesToInt(fieldLenBytes)
                if (fieldLen < 0 || fieldLen > MAX_HEADER_FIELD_BYTES) {
                    throw KdbxCorruptFileException(
                        "头部字段长度非法或超过安全上限: fieldId=$fieldIdByte, length=$fieldLen（允许 0 ~ $MAX_HEADER_FIELD_BYTES）"
                    )
                }
                if (recordingStream.size() + fieldLen > MAX_HEADER_TOTAL_BYTES) {
                    throw KdbxCorruptFileException(
                        "外层 Header 累计字节数超过安全上限: 字段数据前已达 ${recordingStream.size()} 字节，" +
                                "本字段声明 $fieldLen 字节（上限 $MAX_HEADER_TOTAL_BYTES 字节）——认证前 fail-closed 加固判定为损坏文件"
                    )
                }

                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen, MAX_HEADER_FIELD_BYTES)
                record(fieldData)
                return fieldIdByte.toByte() to fieldData
            }
        }

        /** 外层 Header 各字段的累积容器（字段出现顺序不作保证，跨字段校验在装配后统一执行） */
        private class OuterHeaderFields {
            var cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
            var compression: Int = KdbxConstants.Compression.GZIP
            var masterSeed: ByteArray? = null
            var encryptionIv: ByteArray? = null
            var kdfParams: KdfParameters? = null
            var publicCustomData: VariantDictionary? = null

            /** 单个字段的长度裁决与落地；未知字段原样忽略（前向兼容） */
            fun accept(fieldId: Byte, fieldData: ByteArray) {
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

            fun toHeader(sig1: Int, sig2: Int, version: Int): KdbxHeader = KdbxHeader(
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
