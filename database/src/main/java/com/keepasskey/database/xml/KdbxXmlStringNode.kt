package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.stream.InnerRandomStreamCipher
import com.keepasskey.database.exception.KdbxCorruptFileException
import org.xml.sax.Attributes
import java.util.Base64

/**
 * <String> 字段子树：<Key> 名称 + <Value Protected="..."> 内容，闭合时解密受保护值。
 *
 * ISSUE-P3-25 拆分：原样搬出自 `KdbxXmlGroupReader.kt`。可见性由 `private`（文件级）
 * 放宽为 `internal`——唯一调用方 [EntryNode] 现位于 `KdbxXmlGroupReader.kt`，跨文件使用；
 * `internal` 为同模块可见，不跨模块泄漏（app / sync / core / crypto 均无引用）。
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
                isProtected = attrs.getValue(KdbxConstants.Xml.PROTECTED)?.lowercase() == "true"
                TextNode { rawValue = it }
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        val value = rawValue ?: return
        val protectedString = if (isProtected && innerStreamCipher != null) {
            // 受保护值在写入侧恒为合法 Base64（官方与本项目序列化器均单行编码），
            // 解码前先 trim() 去除 XML 缩进/换行空白。
            // 解码失败说明文件已损坏：严禁降级为 value.toByteArray()——其字节数与
            // Base64 解码结果不一致，会使内层流密码 keystream 错位，级联污染后续
            // 所有受保护字段的解密结果（全部变成乱码），必须立即中断解析。
            val decoded = try {
                Base64.getDecoder().decode(value.trim())
            } catch (e: IllegalArgumentException) {
                throw KdbxCorruptFileException("无法解码受保护字段 Base64 数据: key=$key", e)
            }
            val plainBytes = innerStreamCipher.processBytes(decoded)
            ProtectedString(isProtected = true, bytes = plainBytes)
        } else {
            ProtectedString(value, isProtected = isProtected)
        }
        onDone(key, protectedString)
    }
}
