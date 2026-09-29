package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry

/**
 * 附件合并策略（ISSUE-P2-386）：编辑保存时 UI 附件 ↔ 既有 `KdbxAttachment` 的身份匹配。
 *
 * 抽为纯函数的理由：KDBX 同名附件是合法形态（KeePassXC 注释实证），按名称
 * `firstOrNull` 匹配会把两份同名附件折叠为一份。匹配语义必须能在纯 JVM 上
 * 对「refIndex 命中 / 回退名称 / 未命中」三态逐条断言。
 *
 * 语义：
 * - `ui.data != null` → 新附件，直接构造；
 * - `ui.data == null` 且 `refIndex` 落在既有列表下标内且未被消费 → 身份匹配（ISSUE-P3-295 同口径）；
 * - refIndex 缺失/越界 → 回退名称匹配**尚未被消费**的既有项（兼容旧投影）；
 * - 两者皆未命中 → 空附件占位（不静默丢弃 UI 意图，由后续写盘路径决定）。
 *
 * UI 中被移除的附件不再出现在列表里，即自然从条目上删除——**不**保留未被 UI 列出的
 * 既有余份（AC① 采「refIndex 身份匹配」主路径，非「余份保留」备选路径）。
 */
internal object AttachmentMergePolicy {

    fun merge(existing: KdbxEntry, entry: UiVaultEntry): List<KdbxAttachment> {
        val existingByIndex = existing.attachments
        val consumed = BooleanArray(existingByIndex.size)
        return entry.attachments.map { ui ->
            if (ui.data != null) {
                KdbxAttachment(name = ui.fileName, data = ui.data, isProtected = false)
            } else {
                val byRef = ui.refIndex.takeIf { it in existingByIndex.indices && !consumed[it] }
                if (byRef != null) {
                    consumed[byRef] = true
                    existingByIndex[byRef]
                } else {
                    val byName = existingByIndex.indices.firstOrNull {
                        !consumed[it] && existingByIndex[it].name == ui.fileName
                    }
                    if (byName != null) {
                        consumed[byName] = true
                        existingByIndex[byName]
                    } else {
                        KdbxAttachment(name = ui.fileName, data = byteArrayOf())
                    }
                }
            }
        }
    }
}
