package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * SAF CreateDocument 目标文档的失败/取消清理（ISSUE-P2-20）。
 *
 * 背景：`CreateDocument` 在用户点「保存」瞬间即于目标目录创建空文档，真实写入发生在
 * 其后的二次确认（明文类）或序列化（全部类）阶段——自动锁定熔断会话、用户取消确认或
 * 写盘异常都会让该空文档静默残留（实测 AVD：导出中断后产物 0 字节且无提示）。
 *
 * 语义：只删除「本流程刚创建、尚未被本流程成功写入」的目标；删除为 best-effort，
 * 任何异常吞掉（用户目录里多一个 0 字节文件不应升级为崩溃）。
 */
internal object SafDocumentCleanup {

    /** 删除本流程创建的目标文档（尽力而为；失败静默，仅保正确性不留垃圾）。 */
    fun deleteCreatedDocument(context: Context?, targetUri: Uri) {
        if (context == null) return
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, targetUri)
        }
    }
}
