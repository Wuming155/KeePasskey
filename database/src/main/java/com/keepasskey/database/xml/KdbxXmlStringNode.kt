package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.xml.sax.Attributes
import java.util.Arrays

/**
 * <String> 字段子树：<Key> 名称 + <Value Protected="..."> 内容，闭合时解密受保护值。
 *
 * ISSUE-P3-25 拆分：原样搬出自 `KdbxXmlGroupReader.kt`。可见性由 `private`（文件级）
 * 放宽为 `internal`——唯一调用方 [EntryNode] 现位于 `KdbxXmlGroupReader.kt`，跨文件使用；
 * `internal` 为同模块可见，不跨模块泄漏（app / sync / core / crypto 均无引用）。
 *
 * 官方对照（KeePass 2.61.1）：
 * - `KdbxFile.Read.Streamed.cs:1066-1068`：`Protected` 属性只在**精确等于** `"True"` 时
 *   才视为受保护（`xr.Value == ValTrue`，大小写敏感）；否则该值按**明文**承载，
 *   **且不消耗内层随机流的任何字节**。
 * - `KdbxFile.Read.Streamed.cs:776`：空元素读作空串（对应缺陷 D3：`<Value/>` 不得丢字段）。
 * - `KdbxFile.Read.Streamed.cs:792`：Base64 解码走 `.NET Convert.FromBase64String`，容忍内部空白。
 */
internal class StringNode(
    private val innerStreamCipher: InnerRandomStreamCipher?,
    private val onDone: (String, ProtectedString) -> Unit
) : SaxNode() {

    private var key = ""
    private var rawValue: String? = null
    private var isProtected = false

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> {
                isProtected = isProtectedAttribute(attrs)
                // 缺陷 D3：空 <Value/> 不触发 characters()，但 TextNode 闭合时仍回调空串，
                // 故 rawValue 变为 ""（而非保持 null）——字段因此不会被整条丢弃。
                TextNode { rawValue = it }
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        // 缺陷 D3：仅在 <Value> 子元素**完全缺失**时 rawValue 为 null，此时按空值交付；
        // 与官方「缺失/空元素同样读作空串」一致，绝不让字段消失。
        val value = rawValue.orEmpty()
        val protectedString = if (isProtected && innerStreamCipher != null) {
            val decoded = try {
                // 缺陷 D19：容忍内部换行/空白的 Base64（官方 .NET 解码器语义）。
                // 解码失败说明文件已损坏：严禁降级为 value.toByteArray()——其字节数与
                // Base64 解码结果不一致，会使内层流密码 keystream 错位，级联污染后续
                // 所有受保护字段的解密结果（全部变成乱码），必须立即中断解析。
                KdbxXmlValueUtil.decodeBase64LenientWhitespace(value)
            } catch (e: IllegalArgumentException) {
                throw KdbxCorruptFileException("无法解码受保护字段 Base64 数据: key=$key", e)
            }
            val plainBytes = if (decoded.isEmpty()) {
                // 官方 `ReadProtectedBinary`/受保护字符串路径对空载荷不消耗密钥流
                // （KdbxFile.Write.cs:860-862 仅当长度 > 0 才写出）；
                // 空 Base64 传入流密码引擎在部分实现上会拒绝，故显式短路。
                ByteArray(0)
            } else {
                innerStreamCipher.processBytes(decoded)
            }
            try {
                // 缺陷 D24：主构造的 bytes 为**借用**语义（ProtectedString.kt:69 明确
                // 「归调用方所有，不由本类清零」），而 isProtected=true 且非空时主构造的
                // init 已完成密封（非明文驻留）——故此处立即清零明文中间量，不留待 GC。
                ProtectedString(isProtected = true, bytes = plainBytes)
            } finally {
                Arrays.fill(plainBytes, 0.toByte())
            }
        } else {
            ProtectedString(value, isProtected = isProtected)
        }
        onDone(key, protectedString)
    }
}

/**
 * `Protected` 属性唯一受认可取值（官方 `KdbxFile.cs:ValTrue = "True"`）。
 *
 * 缺陷 D4：**不得**做 `lowercase()` 归一化——官方为大小写敏感的精确比较
 * （`KdbxFile.Read.Streamed.cs:1066-1068` / 写侧 `KdbxFile.Write.cs:858` 只写 `"True"`），
 * 非规范写入者的 `Protected="true"` 被官方当作**明文**且**不推进内层 keystream**；
 * 若我方误当密文并推进 keystream，其后的所有受保护值将永久错位，保存即固化损坏。
 */
internal const val PROTECTED_ATTR_TRUE = "True"

/** 按官方精确比较判定 `Protected` 属性是否生效（缺陷 D4）。 */
internal fun isProtectedAttribute(attrs: Attributes): Boolean =
    attrs.getValue(KdbxConstants.Xml.PROTECTED) == PROTECTED_ATTR_TRUE
