package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import org.xml.sax.Attributes

/**
 * <AutoType> 子树。
 *
 * ISSUE-P3-25 拆分：原样搬出自 `KdbxXmlGroupReader.kt`。可见性由 `private`（文件级）
 * 放宽为 `internal`——唯一调用方 [EntryNode] 现位于 `KdbxXmlGroupReader.kt`，跨文件使用；
 * `internal` 为同模块可见，不跨模块泄漏（app / sync / core / crypto 均无引用）。
 */
internal class AutoTypeNode(
    private val onDone: (KdbxAutoType) -> Unit
) : SaxNode() {

    private var enabled = true
    private var dataTransferObfuscation = 0
    private var defaultSequence = ""
    private val associations = mutableListOf<KdbxAutoType.AutoTypeAssociation>()

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.ENABLED -> TextNode { enabled = it.lowercase() != "false" }
            KdbxConstants.Xml.DATA_TRANSFER_OBFUSCATION -> TextNode { dataTransferObfuscation = it.trim().toIntOrNull() ?: 0 }
            KdbxConstants.Xml.DEFAULT_SEQUENCE -> TextNode { defaultSequence = it }
            KdbxConstants.Xml.ASSOCIATION -> AssociationNode { associations.add(it) }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(
            KdbxAutoType(
                enabled = enabled,
                dataTransferObfuscation = dataTransferObfuscation,
                defaultSequence = defaultSequence,
                associations = associations.toList()
            )
        )
    }
}

private class AssociationNode(
    private val onDone: (KdbxAutoType.AutoTypeAssociation) -> Unit
) : SaxNode() {

    private var window = ""
    private var keystrokeSequence = ""

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.WINDOW -> TextNode { window = it }
            KdbxConstants.Xml.KEYSTROKE_SEQUENCE -> TextNode { keystrokeSequence = it }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        onDone(KdbxAutoType.AutoTypeAssociation(window, keystrokeSequence))
    }
}
