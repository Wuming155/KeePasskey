package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.io.LittleEndianUtil
import com.keepasskey.database.io.NonClosingInputStream
import com.keepasskey.database.io.NonClosingOutputStream
import com.keepasskey.database.xml.KdbxMetaData
import com.keepasskey.database.xml.KdbxVersion41Features
import com.keepasskey.database.xml.KdbxXmlParser
import com.keepasskey.database.xml.KdbxXmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.SecureRandom
import java.util.Arrays
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * KDBX v4 文件读写解析引擎。
 *
 * 遵循敏感数据铁律：主密码与派生密钥在完成变换后立即清零，绝不驻留 GC 堆。
 * 遵循官方标准（KeePass 2.61.1 §4 与 §8.1 / KeePassDX §5）：
 * - cipherKey: SHA-256(masterSeed ‖ transformedKey)
 * - hmacKey64: SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
 * - 兼顾读取历史旧派生产物（SHA-512 截断公式），保存自动迁移至官方标准。
 *
 * 全链路流式管线（对应官方 Read/Write 流栈：HmacBlockStream → Cipher → GZip → XML 状态机）：
 * 读写均不再将整条密文/明文/压缩数据物化为字节数组，大库加载与保存的峰值内存
 * 由「数倍库体积」降为「单个块缓冲 + 对象树」。
 *
 * ISSUE-P3-29：复合密钥派生与 cipherKey 派生裁决探针已分别拆至 `KdbxKeyDerivation.kt`
 * 与 `KdbxCipherKeyResolver.kt`；本类保留读写管线本体与 `deriveKeys` 门面委托
 * （同签名，既有调用方与单测零改动）。拆分为**纯结构性**，行为零变更。
 */
object KdbxFile {

    private val secureRandom = SecureRandom()

    /**
     * 为一次根库、子库或外部库解析创建按次 spill 所有权事务。
     *
     * 调用方在消费/接管结果后必须显式 [AttachmentSpillTransaction.commit] 或
     * [AttachmentSpillTransaction.purge]。事务回收只触及本次成功写入的 key。
     */
    fun beginAttachmentSpillTransaction(binaryStore: BinaryStore?): AttachmentSpillTransaction =
        AttachmentSpillTransaction(binaryStore)

    /** KDBX4 内层流密码密钥长度（官方 InnerRandomStreamKey 恒为 64 字节） */
    private const val INNER_RANDOM_STREAM_KEY_SIZE = 64

    /**
     * GZip 流内部缓冲尺寸（64 KiB）。
     *
     * ISSUE-P3-151：`java.util.zip` 的 `GZIPInputStream` / `GZIPOutputStream` 单参构造
     * 使用 **512 字节**的内部缓冲，多 MB 载荷会以该粒度反复进出 inflate / deflate
     * （每次调用都带一层本地方法往返）；显式给出 64 KiB 缓冲后，同一载荷的调用次数下降两个数量级。
     * 输出字节与压缩级别不变，纯缓冲参数调整。
     */
    private const val COMPRESSION_BUFFER_BYTES = 64 * 1024

    /**
     * 载荷解压输出（或未压缩载荷）累计字节数安全上限：128 MiB（Wave 12 解析炸弹防线；
     * ISSUE-P3-10 子项 1 由原 512 MiB 下调）。
     *
     * **本常量是三处解析资源上限的唯一真源（ISSUE-P3-27 子项 1）**：内层 Header 的
     * 单字段上限与二进制池累计上限均由本值派生（[InnerHeader.MAX_INNER_FIELD_BYTES] =
     * 本值 / 2；[InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES] = min(设计值, 本值)），
     * 并在 [InnerHeader] 伴生对象初始化期以 `require` 断言
     * 「单字段 ≤ 池累计 ≤ 整包」。因此「内层某上限高于整包上限」这类语义不自洽
     * 在常量漂移时会立即暴露，而不会退化成永不生效的死守卫。
     *
     * 取值依据：
     * - **binding 级守卫**：本上限包住内层 Header（含附件二进制池）与 XML 正文的**全部**
     *   解压输出，恶意 .kdbx（导入场景，用户持有其密码）无法越过它解出更多明文，
     *   解析期峰值内存被约束在移动端可承受的常数界内（原 512 MiB 已属高危水位）；
     * - `guardPayloadSize` 对 GZip 与未压缩（Compression.NONE）载荷一视同仁，
     *   两种压缩模式共用同一预算，不存在绕过通道；
     * - 下限约束：不得低于内层 Header 单字段上限（本值 / 2 = 64 MiB，承载单个附件），
     *   否则「一个合法大附件 + XML 正文」即被误拒；本值为单字段上限留出同等量级余量；
     * - 正常 KDBX 库（含常见附件规模）远低于该界，且流式解析本就不整体物化载荷，
     *   保留同一量级的正常库解析能力；
     * - 取舍结论（ISSUE-P3-27 子项 1）：放宽本值会直接削弱解压炸弹防护，收紧会误拒
     *   合法大附件，故本值不动，只把内层各级上限收敛为它的派生值。
     */
    internal const val MAX_DECOMPRESSED_PAYLOAD_BYTES = 128L * 1024 * 1024

    /**
     * 为解密后的载荷流套上「解压输出尺寸护栏」。
     *
     * 独立成函数以便单测直接以生产常量（[MAX_DECOMPRESSED_PAYLOAD_BYTES]）验证
     * 「超限被拒 / 限额内通过」，无需构造超大真实库（见 KdbxParsingResourceLimitsTest）。
     */
    internal fun guardPayloadSize(raw: InputStream, isGzipCompressed: Boolean): InputStream {
        val decompressed = if (isGzipCompressed) GZIPInputStream(raw, COMPRESSION_BUFFER_BYTES) else raw
        return SizeBoundedInputStream(decompressed, MAX_DECOMPRESSED_PAYLOAD_BYTES)
    }

    /**
     * 打开并解密 KDBX v4 数据库。
     * 先采用官方标准密钥派生校验头部 HMAC（凭据正确性在解密前的最终裁决）；
     * 历史旧派生（SHA-256 cipherKey）与官方派生的 hmacKey64 完全一致，头部 HMAC 无法区分二者，
     * 因此 cipherKey 变体由数据段首个块的解密探针裁决（见 [KdbxCipherKeyResolver.resolve]），
     * 保存时自动迁移官方标准。
     *
     * D20 类型化异常三态（对齐官方 KeePass 2.61.1）：
     * - 头部 SHA-256 不符 → [KdbxCorruptFileException]（官方 `InvalidDataException(FileHeaderCorrupted)`，Read.cs:150）；
     * - **头部 HMAC 不符 → [KdbxInvalidCredentialsException]**（官方 `InvalidCompositeKeyException`，Read.cs:157；
     *   凭据错误是唯一能让头部 HMAC 不符的原因，本方法在 :150 抛出，是**唯一**的凭据异常出口）；
     * - 数据块/终止块 HMAC 不符、以及头部 HMAC 通过后的 cipherKey 探针失败 →
     *   [KdbxCorruptFileException]（官方 `InvalidDataException(FileCorrupted)`，HmacBlockStream.cs:233/264）。
     *   最后一条至关重要：篡改文件必须报「文件损坏或被篡改」，绝不能被上层显示为「主密码错误」
     *   并计入解锁失败节流——否则用户永远无法得知库已被篡改。
     *
     * [passwordChars] 允许为 null 或空数组（表示无主密码、仅密钥文件解锁，
     * 对齐官方 KeyUtil.CreateKey 对空密码不添加密码分量的语义，详见 [deriveKeys]）。
     *
     * ISSUE-P3-368：[totalBytes] 为文件总字节数（未知长度如 SAF Uri 流传 null，
     * 载荷段退分段不确定态）；[onProgress] 为 0..1 进度回调（null = 分段不确定段），
     * 回调异常被吞、不改变本方法的异常与清零语义（AC③，见 [KdbxProgressReporter]）。
     */
    fun load(
        inputStream: InputStream,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        binaryStore: BinaryStore? = null,
        totalBytes: Long? = null,
        onProgress: ((Float?) -> Unit)? = null
    ): KdbxDatabase {
        val progress = KdbxProgressReporter(onProgress)
        val counting = totalBytes?.takeIf { it > 0 }?.let {
            ProgressCountingInputStream(inputStream, it, progress)
        }
        val source = counting ?: inputStream
        progress.emit(KdbxProgress.START)
        // 1. 读取并解析外层 Header
        val (header, headerBytes) = KdbxHeader.deserialize(source)

        // 2. 读取并校验 Header SHA-256
        val storedHeaderSha = try {
            LittleEndianUtil.readBytes(source, 32)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("读取头部 SHA-256 意外中断", e)
        }
        val actualHeaderSha = HashUtil.sha256(headerBytes)
        if (!java.security.MessageDigest.isEqual(actualHeaderSha, storedHeaderSha)) {
            throw KdbxCorruptFileException("KDBX 头部 SHA-256 校验失败，文件已损坏或被篡改")
        }

        val storedHeaderHmac = try {
            LittleEndianUtil.readBytes(source, 32)
        } catch (e: Exception) {
            throw KdbxCorruptFileException("读取头部 HMAC 意外中断", e)
        }
        progress.emit(KdbxProgress.HEADER_PARSED)
        progress.emit(null)

        // 3. 官方标准派生
        val (cipherKey, hmacKey64) = deriveKeys(header, passwordChars, keyFileData, isLegacy = false)

        try {
            // 4. 读取并校验 Header HMAC-SHA256
            // 官方规范 / 对齐 KeePass 2.x、pykeepass：headerKey = SHA-512(LE64(0xFFFFFFFFFFFFFFFF) ‖ hmacKey64)
            val headerHmacKey = HashUtil.sha512(
                LittleEndianUtil.longTo8Bytes(KdbxConstants.Header.HMAC_KEY_LE64_SENTINEL),
                hmacKey64
            )
            val actualHeaderHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            Arrays.fill(headerHmacKey, 0.toByte())

            if (!java.security.MessageDigest.isEqual(actualHeaderHmac, storedHeaderHmac)) {
                // D20：头部 HMAC 不符是本管线**唯一**的凭据错误出口——hmacKey64 由复合密钥派生，
                // 凭据错误必然在此暴露；一旦越过本行，后续任何完整性失败都不得再报凭据错误。
                throw KdbxInvalidCredentialsException("主密码错误或文件头部认证失败（HMAC 校验未通过）")
            }

            // ISSUE-P3-368：已知总长 ⇒ 字节进度接管载荷段（解密→解压→SAX 单一度量）；
            // 未知总长 ⇒ 载荷段整体分段不确定（null），结束仍到 DONE
            if (counting != null) {
                counting.startReporting()
            } else {
                progress.emit(null)
            }
            val database = KdbxReadPipeline.loadPayload(
                source, header, passwordChars, keyFileData, cipherKey, hmacKey64, binaryStore
            )
            progress.emit(KdbxProgress.DONE)
            return database
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }


    /**
     * 保存并序列化 KDBX v4 数据库（全链路流式：XML 流式写出 → GZip → 加密流 → HMAC 块流）。
     * 恒用官方标准派生进行加密落盘，自动迁移旧派生库。
     *
     * [passwordChars] 允许为 null 或空数组（仅密钥文件库，对齐官方 KeePass 语义，详见 [deriveKeys]）。
     *
     * ISSUE-P3-368：[onProgress] 为 0..1 进度回调（null = 分段不确定段，KDF 派生段）；
     * 回调异常被吞、不改变本方法的异常与清零语义（AC③，见 [KdbxProgressReporter]）。
     * 落盘段由调用方（会话层）在写出完成后补发 DONE——本方法只负责序列化侧进度。
     */
    fun save(
        outputStream: OutputStream,
        database: KdbxDatabase,
        passwordChars: CharArray?,
        keyFileData: ByteArray? = null,
        onProgress: ((Float?) -> Unit)? = null
    ) {
        val progress = KdbxProgressReporter(onProgress)
        progress.emit(KdbxProgress.START)
        // 1. 附件去重并组装二进制池 (ProtectedBinarySet 语义)
        val (dedupRootGroup, dedupBinaries) = KdbxBinaryDeduplicator.deduplicate(database.rootGroup, database.binaries)
        val updatedDatabase = database.copy(
            rootGroup = dedupRootGroup,
            binaries = dedupBinaries
        )
        progress.emit(KdbxProgress.SAVE_PREPARED)

        // 2. 初始化全新内层 Header 与内层流密码
        val freshInnerKey = ByteArray(INNER_RANDOM_STREAM_KEY_SIZE)
        secureRandom.nextBytes(freshInnerKey)
        val innerHeader = InnerHeader(
            innerRandomStreamId = KdbxConstants.InnerRandomStream.CHACHA20,
            innerRandomStreamKey = freshInnerKey,
            binaries = dedupBinaries
        )
        val innerCipher = InnerRandomStreamCipher(
            innerHeader.innerRandomStreamId,
            innerHeader.innerRandomStreamKey
        )

        // 3. 生成全新随机 MasterSeed 与 EncryptionIV，更新 KDF Salt
        // P0-4 整改：EncryptionIV 长度必须由加密算法决定（KDBX4 规范要求 Header 字段 7 的
        // 长度等于所选算法的 IV 长度，官方 KeePass 2.61.1 各 Cipher 构造器硬校验）——
        // ChaCha20 (Cipher.CHACHA20) 为 12 字节 nonce（RFC 7539），
        // AES-256-CBC (Cipher.AES_256_CBC) / Twofish (Cipher.TWOFISH) 为 16 字节 IV。
        // 原实现恒写 16 字节，凡以 ChaCha20 保存的库均为官方客户端打不开的文件。
        val freshMasterSeed = ByteArray(32)
        val freshEncryptionIv = ByteArray(
            CipherFactory.getEngine(database.header.cipherUuid).ivLength
        )
        secureRandom.nextBytes(freshMasterSeed)
        secureRandom.nextBytes(freshEncryptionIv)

        val freshKdfParams = when (val p = database.header.kdfParameters) {
            is KdfParameters.Aes -> {
                val freshSeed = ByteArray(32)
                secureRandom.nextBytes(freshSeed)
                p.copy(seed = freshSeed)
            }
            is KdfParameters.Argon2 -> {
                val freshSalt = ByteArray(32)
                secureRandom.nextBytes(freshSalt)
                p.copy(salt = freshSalt)
            }
        }

        val updatedHeader = database.header.copy(
            masterSeed = freshMasterSeed,
            encryptionIv = freshEncryptionIv,
            kdfParameters = freshKdfParams,
            // ISSUE-P3-367：按待写内容动态计算最小版本（新建与保存的唯一决策点，
            // 对齐官方 GetMinKdbxVersion——任一 4.1 特征升 4.1，否则保持/回退 4.0）
            version = KdbxVersion41Features.resolveMinVersion(updatedDatabase)
        )

        // 4. 恒用官方派生标准计算主加密与 HMAC 密钥（KDF 单次同步 JNI 不可细分 ⇒ 分段不确定）
        // ISSUE-P1-595：写前接受域校验（与读侧同一判据，见 KdbxKdfAcceptance）——在写任何目标字节
        // 与进行 KDF 之前 fail-closed，阻止绕过 UI 的调用者写出本应用读侧会拒绝的产物
        KdbxKdfAcceptance.requireAcceptableOrThrow(updatedHeader.kdfParameters)
        progress.emit(null)
        val (cipherKey, hmacKey64) = deriveKeys(updatedHeader, passwordChars, keyFileData, isLegacy = false)

        try {
            // 5. 写入外层 Header + Header SHA-256 + Header HMAC
            val headerBytesStream = ByteArrayOutputStream()
            val headerBytes = updatedHeader.serialize(headerBytesStream)
            outputStream.write(headerBytes)
            outputStream.write(HashUtil.sha256(headerBytes))

            // 与读取侧一致：SHA-512(LE64(0xFFFFFFFFFFFFFFFF) ‖ hmacKey64)
            val headerHmacKey = HashUtil.sha512(
                LittleEndianUtil.longTo8Bytes(KdbxConstants.Header.HMAC_KEY_LE64_SENTINEL),
                hmacKey64
            )
            val headerHmac = HashUtil.hmacSha256(headerHmacKey, headerBytes)
            outputStream.write(headerHmac)
            Arrays.fill(headerHmacKey, 0.toByte())

            // 6. 流式加密写出负载：内层 Header ‖ XML 一同经 GZip 压缩后写入加密流，再由 HMAC 块流封装
            progress.emit(KdbxProgress.SAVE_PAYLOAD_START)
            savePayload(outputStream, updatedHeader, innerHeader, innerCipher, updatedDatabase, cipherKey, hmacKey64)
            outputStream.flush()
            progress.emit(KdbxProgress.SAVE_SERIALIZED)
        } finally {
            Arrays.fill(cipherKey, 0.toByte())
            Arrays.fill(hmacKey64, 0.toByte())
        }
    }

    private fun savePayload(
        outputStream: OutputStream,
        header: KdbxHeader,
        innerHeader: InnerHeader,
        innerCipher: InnerRandomStreamCipher,
        database: KdbxDatabase,
        cipherKey: ByteArray,
        hmacKey64: ByteArray
    ) {
        val blockOut = HmacBlockOutputStream(NonClosingOutputStream(outputStream), hmacKey64)
        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)
        val cipherOut = cipherEngine.createEncryptingStream(blockOut, cipherKey, header.encryptionIv)

        // 官方载荷顺序（对齐 KeePass 2.x Write.cs / KeePassDX DatabaseOutputKDBX）：
        // 内层头部写在 GZIP 流之内（与 XML 一同被压缩），读取侧先解压再读内层头部
        val gzipOut = if (header.compression == KdbxConstants.Compression.GZIP) {
            GZIPOutputStream(cipherOut, COMPRESSION_BUFFER_BYTES)
        } else {
            null
        }
        val bodyOut = gzipOut ?: cipherOut
        try {
            innerHeader.serialize(bodyOut)
            KdbxXmlSerializer(innerCipher).serialize(bodyOut, database)
        } finally {
            try {
                // 级联收尾：GZip finish → 加密流 doFinal → HMAC 块流写入终止块
                gzipOut?.close() ?: cipherOut.close()
            } finally {
                // ISSUE-P3-235（RC-02 收口）：内层随机流密钥（秘密）的用途到序列化结束为止，
                // 无论收尾是否抛异常都在此擦除；所有权与契约见 InnerHeader.clearSensitive。
                // 本方法是「该密钥实际保护过载荷」的唯一路径——若更早的步骤（KDF 派生 /
                // 外层头部写出）失败，密钥尚未加密任何载荷，其残留不构成新的秘密暴露。
                innerHeader.clearSensitive()
            }
        }
    }

    /**
     * 派生用于数据加密的 cipherKey (32B) 与用于认证的 hmacKey (64B)。
     *
     * ISSUE-P3-29：实现已拆至 [KdbxKeyDerivation.deriveKeys]；本门面保持同签名与可见性，
     * 既有调用方（含单测）与 KDoc 契约（[KdbxKeyFile] 复合密钥一致性）零改动。
     */
    internal fun deriveKeys(
        header: KdbxHeader,
        passwordChars: CharArray?,
        keyFileData: ByteArray?,
        isLegacy: Boolean = false
    ): Pair<ByteArray, ByteArray> =
        KdbxKeyDerivation.deriveKeys(header, passwordChars, keyFileData, isLegacy)
}
