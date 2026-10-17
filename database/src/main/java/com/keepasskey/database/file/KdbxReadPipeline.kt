package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.crypto.exception.CryptoException
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.io.NonClosingInputStream
import com.keepasskey.database.xml.KdbxMetaData
import com.keepasskey.database.xml.KdbxXmlParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Arrays

/**
 * KDBX v4 读取管线（`ISSUE-P2-645`：自 [KdbxFile] 拆分，纯结构性，行为零变更）。
 *
 * 顺序与拆分前逐字一致：HMAC 块流逐块校验 → 解密 → 内层 Header → 可选 GZip 解压（带解析炸弹上限）
 * → 流式 XML 解析 → 组装 [KdbxDatabase]。密钥擦除点（cipherKey / hmacKey64 / 内层随机流密钥）
 * 仍各自留在原位置，不因文件拆分而改变生命周期。
 */
internal object KdbxReadPipeline {
    /**
     * 流式解密负载数据：HMAC 块流（逐块校验）→ 解密流 → 内层 Header → 可选 GZip 解压 → 流式 XML 解析。
     */
    fun loadPayload(
        inputStream: InputStream,
        header: KdbxHeader,
        passwordChars: CharArray?,
        keyFileData: ByteArray?,
        cipherKey: ByteArray,
        hmacKey64: ByteArray,
        binaryStore: BinaryStore?
    ): KdbxDatabase {
        val hmacBlockIn = HmacBlockInputStream(NonClosingInputStream(inputStream), hmacKey64)
        val cipherEngine = CipherFactory.getEngine(header.cipherUuid)

        // 旧派生裁决：取首个数据块做解密探针，官方派生不合法时回退旧派生
        val firstBlock = hmacBlockIn.readBlock()
        val isGzip = header.compression == KdbxConstants.Compression.GZIP
        val resolution = KdbxCipherKeyResolver.resolve(cipherEngine, header, firstBlock, cipherKey, isGzip) {
            // 旧派生重算：仅取 cipherKey 参与裁决，hmacKey64 属 transformedKey 直接派生物，用毕立即擦除
            KdbxKeyDerivation.deriveKeys(header, passwordChars, keyFileData, isLegacy = true).let { (legacyCipherKey, legacyHmacKey) ->
                try {
                    Pair(legacyCipherKey, legacyHmacKey)
                } finally {
                    Arrays.fill(legacyHmacKey, 0.toByte())
                }
            }
        }

        val payloadStream = if (firstBlock != null) {
            SequenceInputStream(ByteArrayInputStream(firstBlock), hmacBlockIn)
        } else {
            hmacBlockIn
        }
        val cipherIn = cipherEngine.createDecryptingStream(payloadStream, resolution.activeKey, header.encryptionIv)
        // P2-10 整改（TASK-24）：解密流建立后（SecretKeySpec 构造时已克隆密钥材料），
        // 被选中的旧派生密钥原数组立即擦除；未被选中的旧派生密钥已在 resolve 内部任何
        // 结果路径（含裁决失败抛异常）统一清零——legacyCipherKey 不再残留 GC 堆。
        resolution.legacyKeyToWipe?.let { Arrays.fill(it, 0.toByte()) }

        // 官方载荷顺序（对齐 KeePass 2.x Read.cs / KeePassDX DatabaseInputKDBX）：
        // 解密 → GZIP 解压 → 内层头部（在解压流内、XML 之前）→ XML
        // Wave 12 解析炸弹防线：对解压输出（及未压缩载荷）做累计字节数封顶
        val xmlInputStream = KdbxFile.guardPayloadSize(
            cipherIn,
            header.compression == KdbxConstants.Compression.GZIP
        )

        // ISSUE-P2-24：大附件在解析期即流式落盘（binaryStore 为 null 时行为与既往逐字一致）
        val innerHeader = InnerHeader.deserialize(xmlInputStream, binaryStore)

        val parseResult = decodeInnerPayload(xmlInputStream, hmacBlockIn, innerHeader)

        return buildDatabase(header, innerHeader, parseResult)
    }

    /**
     * 内层阶段：构造内层流密码 → 流式解析 XML → 校验 HMAC 终止块；并在**任何路径**上
     * 确定性擦除内层随机流密钥（`ISSUE-P3-235` / `RC-02` 收口；秘密的所有权与契约见
     * [InnerHeader.clearSensitive] 与 `docs/architecture/敏感缓冲所有权契约.md` §3 R3）。
     *
     * 擦除**必须**落在本方法的 `finally`（而非调用方）：密钥的用途到解析结束为止，此后仅
     * [buildDatabase] 消费 `binaries`；这样「解析失败 / HMAC 终止块校验失败 / 内层算法不受支持」
     * 三条异常出口同样不留残留。
     */
    private fun decodeInnerPayload(
        xmlInputStream: InputStream,
        hmacBlockIn: HmacBlockInputStream,
        innerHeader: InnerHeader
    ): KdbxXmlParser.ParseResult {
        try {
            // D7 协调项：内层随机流算法由文件自述（属未认证字段），不支持时 crypto 侧抛
            // CryptoException.CipherException；在本跨模块边界统一包装为类型化
            // [KdbxCorruptFileException]，使上层得到「不支持的内层随机流算法」这一可行动语义，
            // 而非泛化失败（crypto 侧行为不改动，由其归属代理负责）。
            // 注：执行到此处时头部 SHA-256 / 头部 HMAC / 首个数据块 HMAC 均已校验通过，
            // 故该算法标识只可能来自「格式不受支持的合法库」或「已通过认证后被篡改的库」。
            val innerCipher = try {
                InnerRandomStreamCipher(
                    innerHeader.innerRandomStreamId,
                    innerHeader.innerRandomStreamKey
                )
            } catch (e: CryptoException) {
                // 跨模块异常包装：crypto 侧的泛化失败在此收敛为类型化「文件损坏/格式不受支持」
                throw KdbxCorruptFileException(
                    "不支持的内层随机流算法: id=${innerHeader.innerRandomStreamId}（文件损坏或格式不受支持）",
                    e
                )
            }

            val parseResult = KdbxXmlParser(innerCipher).parse(xmlInputStream, innerHeader.binaries)

            // XML 解析可能在 GZip 尾部即停止拉取，显式确认 HMAC 终止块已被消费校验
            hmacBlockIn.verifyEndOfStream()
            return parseResult
        } finally {
            innerHeader.clearSensitive()
        }
    }

    private fun buildDatabase(
        header: KdbxHeader,
        innerHeader: InnerHeader,
        parseResult: KdbxXmlParser.ParseResult
    ): KdbxDatabase {
        val meta: KdbxMetaData = parseResult.meta
        return KdbxDatabase(
            header = header,
            databaseName = meta.databaseName,
            databaseNameChanged = meta.databaseNameChanged,
            databaseDescription = meta.databaseDescription,
            databaseDescriptionChanged = meta.databaseDescriptionChanged,
            defaultUserName = meta.defaultUserName,
            defaultUserNameChanged = meta.defaultUserNameChanged,
            maintenanceHistoryDays = meta.maintenanceHistoryDays,
            color = meta.color,
            masterKeyChanged = meta.masterKeyChanged,
            masterKeyChangeRec = meta.masterKeyChangeRec,
            masterKeyChangeForce = meta.masterKeyChangeForce,
            // KDBX 4.1 追加字段（官方 Read.Streamed.cs:250 / Write.cs:461-462）：
            // XML 层已读写对称，此处补齐装配跳，否则该字段经 save/load 会丢失
            masterKeyChangeForceOnce = meta.masterKeyChangeForceOnce,
            settingsChanged = meta.settingsChanged,
            rootGroup = parseResult.rootGroup,
            binaries = innerHeader.binaries,
            recycleBinUuid = meta.recycleBinUuid,
            recycleBinEnabled = meta.recycleBinEnabled,
            recycleBinChanged = meta.recycleBinChanged,
            entryTemplatesGroup = meta.entryTemplatesGroup,
            entryTemplatesGroupChanged = meta.entryTemplatesGroupChanged,
            customIcons = meta.customIcons,
            deletedObjects = meta.deletedObjects,
            memoryProtection = meta.memoryProtection,
            customData = meta.customData,
            // KDBX 4.1 追加字段：CustomData 各项的 LastModificationTime
            //（并行字段方案，customData 保持 Map<String,String> 以免波及 app/sync 消费方）
            customDataTimes = meta.customDataTimes,
            historyMaxItems = meta.historyMaxItems,
            historyMaxSize = meta.historyMaxSize,
            lastSelectedGroup = meta.lastSelectedGroup,
            lastTopVisibleGroup = meta.lastTopVisibleGroup,
            generator = meta.generator
        )
    }
}
