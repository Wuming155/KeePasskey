package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.crypto.VariantDictionary
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxUnsupportedVersionException
import com.keepasskey.database.io.LittleEndianUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        val kdfVd = serializeKdfParameters(kdfParameters)
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

        /** Argon2 内存下界：官方最小合法工作区（1 MB） */
        private const val ARGON2_MIN_MEMORY_BYTES = 1024L * 1024

        /** Argon2 内存上界：远超一切合法用户配置的绝对封顶（4 GiB），防恶意文件分配期 OOM */
        private const val ARGON2_MAX_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

        /** Argon2 迭代上界（合法配置通常 ≤ 数千轮） */
        private const val ARGON2_MAX_ITERATIONS = 1L shl 24

        /** Argon2 并行度上界（合法配置通常 ≤ CPU 核数） */
        private const val ARGON2_MAX_PARALLELISM = 64

        /** AES-KDF 轮数上界：合法偏执配置通常 ≤ 1 亿轮，此处封顶 2^28 防无限期占用 CPU */
        private const val AES_KDF_MAX_ROUNDS = 1L shl 28

        fun createDefault(
            cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC,
            useArgon2: Boolean = true
        ): KdbxHeader {
            val masterSeed = ByteArray(32)
            val encryptionIv = ByteArray(16)
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

            fun readAndRecordInt(): Int {
                val b = LittleEndianUtil.readBytes(inputStream, 4)
                recordingStream.write(b)
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
                throw KdbxUnsupportedVersionException("目前仅支持 KDBX v4 版本，实际文件主版本为: 0x${Integer.toHexString(major)}")
            }

            var cipherUuid: KdbxUuid = KdbxConstants.Cipher.AES_256_CBC
            var compression: Int = KdbxConstants.Compression.GZIP
            var masterSeed: ByteArray? = null
            var encryptionIv: ByteArray? = null
            var kdfParams: KdfParameters? = null
            var publicCustomData: VariantDictionary? = null

            while (true) {
                val fieldIdByte = inputStream.read()
                if (fieldIdByte < 0) throw KdbxCorruptFileException("意外到达头部流末尾")
                recordingStream.write(fieldIdByte)

                val fieldLenBytes = LittleEndianUtil.readBytes(inputStream, 4)
                recordingStream.write(fieldLenBytes)
                val fieldLen = LittleEndianUtil.bytesToInt(fieldLenBytes)

                val fieldData = LittleEndianUtil.readBytes(inputStream, fieldLen)
                recordingStream.write(fieldData)

                val fieldId = fieldIdByte.toByte()
                if (fieldId == KdbxConstants.HeaderFieldId.END_OF_HEADER) {
                    break
                }

                when (fieldId) {
                    KdbxConstants.HeaderFieldId.CIPHER_ID -> {
                        cipherUuid = KdbxUuid(fieldData)
                    }
                    KdbxConstants.HeaderFieldId.COMPRESSION_FLAGS -> {
                        compression = LittleEndianUtil.bytesToInt(fieldData)
                    }
                    KdbxConstants.HeaderFieldId.MASTER_SEED -> {
                        masterSeed = fieldData
                    }
                    KdbxConstants.HeaderFieldId.ENCRYPTION_IV -> {
                        encryptionIv = fieldData
                    }
                    KdbxConstants.HeaderFieldId.KDF_PARAMETERS -> {
                        kdfParams = deserializeKdfParameters(fieldData)
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

            return Pair(header, recordingStream.toByteArray())
        }

        private fun writeField(outputStream: OutputStream, fieldId: Byte, data: ByteArray) {
            outputStream.write(fieldId.toInt())
            LittleEndianUtil.writeInt(outputStream, data.size)
            if (data.isNotEmpty()) {
                outputStream.write(data)
            }
        }

        private fun serializeKdfParameters(params: KdfParameters): VariantDictionary {
            val vd = VariantDictionary()
            vd.setByteArray("\$UUID", params.kdfUuid.toByteArray())
            when (params) {
                is KdfParameters.Aes -> {
                    vd.setByteArray("S", params.seed)
                    vd.setUInt64("R", params.rounds)
                }
                is KdfParameters.Argon2 -> {
                    vd.setByteArray("S", params.salt)
                    vd.setUInt64("P", params.parallelism.toLong())
                    vd.setUInt64("M", params.memoryInBytes)
                    vd.setUInt64("I", params.iterations)
                    vd.setUInt32("V", params.version.toLong())
                    val secret = params.secretKey
                    if (secret != null) vd.setByteArray("K", secret)
                    val assoc = params.associatedData
                    if (assoc != null) vd.setByteArray("A", assoc)
                }
            }
            return vd
        }

        private fun deserializeKdfParameters(bytes: ByteArray): KdfParameters {
            val vd = VariantDictionary.deserialize(bytes)
            val uuidBytes = vd.getByteArray("\$UUID") ?: throw KdbxCorruptFileException("KDF 参数中缺失 \$UUID")
            val uuid = KdbxUuid(uuidBytes)

            return when (uuid) {
                KdbxConstants.Kdf.AES_KDF -> {
                    val seed = vd.getByteArray("S") ?: throw KdbxCorruptFileException("AES-KDF 缺少 S 参数")
                    val rounds = vd.getUInt64("R") ?: throw KdbxCorruptFileException("AES-KDF 缺少 R 参数")
                    validateAesKdfBounds(rounds)
                    KdfParameters.Aes(seed = seed, rounds = rounds)
                }
                KdbxConstants.Kdf.ARGON2D, KdbxConstants.Kdf.ARGON2ID -> {
                    val type = if (uuid == KdbxConstants.Kdf.ARGON2D)
                        KdfParameters.Argon2.Argon2Type.ARGON2D
                    else
                        KdfParameters.Argon2.Argon2Type.ARGON2ID
                    val salt = vd.getByteArray("S") ?: throw KdbxCorruptFileException("Argon2 缺少 S 参数")
                    // 对齐 KeePassDX / 官方规范：P 与 V 在 KDBX4 变体字典中以 UInt32 类型写出，按 UInt32 读取
                    val p = vd.getUInt32("P")?.toInt() ?: 2
                    val m = vd.getUInt64("M") ?: (64L * 1024 * 1024)
                    val i = vd.getUInt64("I") ?: 2L
                    val v = vd.getUInt32("V")?.toInt() ?: KdfParameters.Argon2.ARGON2_VERSION_13
                    val k = vd.getByteArray("K")
                    val a = vd.getByteArray("A")
                    validateArgon2Bounds(m, i, p, v)
                    KdfParameters.Argon2(
                        type = type,
                        salt = salt,
                        parallelism = p,
                        memoryInBytes = m,
                        iterations = i,
                        version = v,
                        secretKey = k,
                        associatedData = a
                    )
                }
                else -> throw KdbxCorruptFileException("未知的 KDF 算法: $uuid")
            }
        }

        /**
         * KDF 参数上界校验（对照 KeePassDX Limits / KeePassXC 参数封顶语义）：
         * 文件中的 VariantDictionary 参数在进入计算前必须先通过边界裁决，
         * 否则恶意构造的 KDBX 可声明 1TB 级 Argon2 内存（分配期 OOM 崩溃）、
         * 2^60 级 AES 轮数或迭代数（无限期占用 CPU 线程）造成拒绝服务。
         * 上界取值宽于一切合法用户配置（合法范围见 KdfBenchmark / 各引擎默认值），正常文件不受影响。
         */
        internal fun validateArgon2Bounds(memoryInBytes: Long, iterations: Long, parallelism: Int, version: Int) {
            if (memoryInBytes < ARGON2_MIN_MEMORY_BYTES || memoryInBytes > ARGON2_MAX_MEMORY_BYTES) {
                throw KdbxCorruptFileException(
                    "Argon2 内存参数越界: $memoryInBytes 字节（允许 $ARGON2_MIN_MEMORY_BYTES ~ $ARGON2_MAX_MEMORY_BYTES）"
                )
            }
            if (iterations < 1 || iterations > ARGON2_MAX_ITERATIONS) {
                throw KdbxCorruptFileException("Argon2 迭代参数越界: $iterations（允许 1 ~ $ARGON2_MAX_ITERATIONS）")
            }
            if (parallelism < 1 || parallelism > ARGON2_MAX_PARALLELISM) {
                throw KdbxCorruptFileException("Argon2 并行度越界: $parallelism（允许 1 ~ $ARGON2_MAX_PARALLELISM）")
            }
            if (version != KdfParameters.Argon2.ARGON2_VERSION_10 && version != KdfParameters.Argon2.ARGON2_VERSION_13) {
                throw KdbxCorruptFileException("不支持的 Argon2 版本: 0x${version.toString(16)}")
            }
            // 动态内存门槛：请求内存超过 JVM 堆一半时按损坏文件拒绝（分配发生在 Java 堆上）
            val heapCap = Runtime.getRuntime().maxMemory() / 2
            if (memoryInBytes > heapCap) {
                throw KdbxCorruptFileException("Argon2 内存参数超出本设备可用内存上限")
            }
        }

        internal fun validateAesKdfBounds(rounds: Long) {
            if (rounds < 1 || rounds > AES_KDF_MAX_ROUNDS) {
                throw KdbxCorruptFileException("AES-KDF 轮数越界: $rounds（允许 1 ~ $AES_KDF_MAX_ROUNDS）")
            }
        }
    }
}
