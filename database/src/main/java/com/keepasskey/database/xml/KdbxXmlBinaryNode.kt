package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.file.InnerHeader
import org.xml.sax.Attributes

/**
 * <Binary> 附件子树：通过 <Value Ref="n"> 引用内层头二进制池。
 *
 * ISSUE-P3-25 拆分：原样搬出自 `KdbxXmlGroupReader.kt`。可见性由 `private`（文件级）
 * 放宽为 `internal`——唯一调用方 [EntryNode] 现位于 `KdbxXmlGroupReader.kt`，跨文件使用；
 * `internal` 为同模块可见，不跨模块泄漏（app / sync / core / crypto 均无引用）。
 *
 * ISSUE-P3-07 回归锁：`end()` 出边界防御性拷贝语义逐字保留，
 * 回归测试 `KdbxAttachmentAliasIsolationTest`（4 例）不得放宽或删除。
 */
internal class BinaryNode(
    private val binariesPool: List<InnerHeader.BinaryItem>,
    private val onDone: (KdbxAttachment) -> Unit
) : SaxNode() {

    private var key = ""
    private var refAttribute: String? = null
    private var rawValue: String? = null
    private var isProtected = false

    override fun startChild(name: String, attrs: Attributes): SaxNode {
        return when (name) {
            KdbxConstants.Xml.KEY -> TextNode { key = it }
            KdbxConstants.Xml.VALUE -> {
                refAttribute = attrs.getValue(KdbxConstants.Xml.REF)
                isProtected = attrs.getValue(KdbxConstants.Xml.PROTECTED)?.lowercase() == "true"
                TextNode { rawValue = it }
            }
            else -> IgnoredNode()
        }
    }

    override fun end() {
        if (refAttribute == null && rawValue == null) return
        val refStr = refAttribute?.takeIf { it.isNotEmpty() } ?: rawValue.orEmpty()
        val refIndex = refStr.trim().toIntOrNull() ?: 0
        // ISSUE-P3-07（P1-9 残余）：出边界交付给条目树的附件字节必须是独立副本。
        // 直接引用池内数组会使「同一池条目的多个引用者共享同一可变 ByteArray」，
        // 外部按 Closeable 契约 attachment.clear()/close() 即清零内层 Header 二进制池，
        // 连带损坏其他引用者与后续保存（去重指纹取自已清零数据）——副本化后池仍为唯一权威源。
        // 去重语义不受影响：保存侧按 flags + 字节内容指纹去重（KdbxBinaryDeduplicator），与实例身份无关。
        //
        // ISSUE-P2-24：池中落盘的大附件不再 copyOf（那会立刻整份物化、抹掉落盘收益），
        // 改为挂 [BinarySource] 引用——attachment.data 按需读回**独立副本**，
        // 每次调用互不共享引用，别名隔离契约（上述 P3-07）逐条保持。
        if (refIndex in binariesPool.indices) {
            val item = binariesPool[refIndex]
            if (item.isSpilled) {
                onDone(
                    KdbxAttachment(
                        name = key,
                        refIndex = refIndex,
                        isProtected = isProtected,
                        source = item
                    )
                )
            } else {
                onDone(
                    KdbxAttachment(
                        name = key,
                        refIndex = refIndex,
                        isProtected = isProtected,
                        data = item.data.copyOf()
                    )
                )
            }
        } else {
            onDone(
                KdbxAttachment(
                    name = key,
                    refIndex = refIndex,
                    isProtected = isProtected,
                    data = ByteArray(0)
                )
            )
        }
    }
}
