package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF CreateDocument 目标文档的失败/取消清理（ISSUE-P2-20；归属判定见 ISSUE-P3-112）。
 *
 * 背景：`CreateDocument` 在用户点「保存」瞬间即于目标目录创建空文档，真实写入发生在
 * 其后的二次确认（明文类）或序列化（全部类）阶段——自动锁定熔断会话、用户取消确认或
 * 写盘异常都会让该空文档静默残留（实测 AVD：导出中断后产物 0 字节且无提示）。
 *
 * **调用契约（ISSUE-P3-112）**：`targetUri` 必须是**本流程 `CreateDocument` 的返回值**，
 * 不得是任意用户已有文件（含 `OpenDocument` 结果）。该契约由两道判定共同兜底：
 * 1. [DocumentsContract.isDocumentUri]——只有 DocumentsProvider 的文档 URI 才可能是
 *    `CreateDocument` 产物；`file://`、媒体库等 URI 一律**拒绝删除**（早退，不触碰解析器删除）；
 * 2. 调用点自身只把 `CreateDocument` 的返回值传进来（全仓 7 处调用点均为该形态）。
 *
 * 语义：只删除「本流程刚创建、尚未被本流程成功写入」的目标；删除为 best-effort，
 * 任何异常吞掉（用户目录里多一个 0 字节文件不应升级为崩溃）。
 * **fail-safe 方向**：判定本身抛异常（解析器不可用）时同样**不删除**——宁可留一个空文档，
 * 也不冒误删用户既有文件的风险。
 */
internal object SafDocumentCleanup {

    /** 删除本流程创建的目标文档（尽力而为；失败静默，仅保正确性不留垃圾）。 */
    fun deleteCreatedDocument(context: Context?, targetUri: Uri) {
        if (context == null) return
        runCatching {
            // ISSUE-P3-112：归属判定必须先于删除；非文档 URI 不可能是 CreateDocument 产物
            if (!DocumentsContract.isDocumentUri(context, targetUri)) return
            DocumentsContract.deleteDocument(context.contentResolver, targetUri)
        }
    }
}
