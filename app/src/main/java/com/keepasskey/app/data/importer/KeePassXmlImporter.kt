package com.keepasskey.app.data.importer

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P3-19 交付物 2：KeePass 2.x 明文 XML 解析器（`ImportSource.KEEPASS_XML`）。
 *
 * 输入形态：KeePass / KeePassXC 的「导出 → KeePass XML」明文文件
 * （`<KeePassFile><Meta/><Root><Group>…</Group></Root></KeePassFile>`），
 * 结构映射与库内既有的 `KeePassXmlExporter` / `KdbxXmlParser` 对称（常量取自 `KdbxConstants.Xml`）。
 *
 * fail-closed 契约（`EntryImporter` 接口要求）：
 * - 根元素不是 `<KeePassFile>`、XML 语法错误、DOCTYPE/外部实体、非 UTF-8 编码、
 *   含 NUL 的伪文本、条目数/嵌套深度/字段长度超限 —— 一律 [KdbxResult.Failure]，
 *   并清零已产出条目的敏感数组（见 [ImportParseGuard.failure]）；
 * - 单条条目缺字段只计入 `skipped` 并记警告，绝不以部分成功冒充整体成功。
 *
 * 线程：解析为 CPU 密集（XML 状态机 + 字符映射），强制 `Dispatchers.Default`；
 * 输入 [ByteArray] 为借用语义，由调用方（控制器）用毕清零。
 */
@Singleton
class KeePassXmlImporter @Inject constructor() : EntryImporter {

    override val source: ImportSource = ImportSource.KEEPASS_XML

    override val supportedExtensions: Set<String> = setOf(EXTENSION_XML)

    override suspend fun parse(bytes: ByteArray, fileName: String): KdbxResult<ImportBatch> =
        withContext(Dispatchers.Default) {
            val warnings = ImportWarningCollector()
            val handler = KeePassXmlImportHandler(warnings)
            try {
                ImportParseGuard.requireFileSize(bytes.size)
                val text = ImportTextDecoder.decodeStrictUtf8(bytes)
                requireKeePassFileRoot(text)
                HardenedXmlReader.parse(text, handler)
                KdbxResult.Success(handler.buildBatch(source))
            } catch (t: Throwable) {
                // 任意异常路径统一清零已产出条目的敏感数组，并归一为非敏感 Failure
                ImportParseGuard.failure(ImportParseGuard.unwrap(t), handler.producedEntries())
            }
        }

    /**
     * 根元素前置校验：先拦下「改了扩展名的 JSON / KDBX 二进制」等明显错配输入，报错更有指向性。
     * 真实根元素校验仍由 [KeePassXmlImportHandler] 的 `startElement` 兜底（解析器视角的权威判定）。
     */
    private fun requireKeePassFileRoot(text: String) {
        val head = text.take(ROOT_PROBE_CHARS).trimStart()
        if (!head.startsWith(OPEN_TAG)) throw ImportFormatException(NOT_XML_TEXT)
        if (!head.contains(ROOT_MARKER)) throw ImportFormatException(ROOT_NOT_KEEPASS_FILE)
    }

    private companion object {
        const val EXTENSION_XML = "xml"
        const val ROOT_PROBE_CHARS = 4096
        const val OPEN_TAG = "<"
        const val NOT_XML_TEXT = "导入内容不是 XML 文本"
        const val ROOT_NOT_KEEPASS_FILE = "XML 根元素不是 <KeePassFile>"

        /** 根元素标记，由格式常量派生（禁止复制字面量）。 */
        val ROOT_MARKER: String = OPEN_TAG + KdbxConstants.Xml.ROOT
    }
}
