package com.keepasskey.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.keepasskey.app.ui.screens.settings.SafDocumentCleanup

/**
 * 只接受本流程 ACTION_CREATE_DOCUMENT 返回的文档；一次领取，任意 URI 不可获写入/删除权。
 * 仅保存非秘密 URI；取消向导释放尚未领取的空文档，开始建库后归属移交 SafVaultCreation。
 */
internal object NewVaultDocumentClaims {
    private val pending = mutableSetOf<String>()

    @Synchronized
    fun record(context: Context, uri: Uri): Boolean {
        return try {
            if (uri.scheme != "content" || !DocumentsContract.isDocumentUri(context, uri)) false
            else pending.add(uri.toString())
        } catch (e: Exception) { // cancel-n/a: 阻塞式来源核实；不可验证则拒绝领取
            com.keepasskey.core.log.AppLog.w("NewVaultDocumentClaims", "新建文档来源不可核实", e)
            false
        }
    }

    @Synchronized
    fun consume(uri: Uri): Boolean = pending.remove(uri.toString())

    fun discard(context: Context, path: String) {
        if (path.isBlank()) return
        val uri = Uri.parse(path)
        if (!consume(uri)) return
        try {
            val empty = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize == 0L } == true
            if (empty) SafDocumentCleanup.deleteCreatedDocument(context, uri)
        } catch (e: Exception) { // cancel-n/a: 阻塞式文档清理，不可核实则保留
            com.keepasskey.core.log.AppLog.w("NewVaultDocumentClaims", "新建文档清理条件不可核实", e)
        }
    }
}
