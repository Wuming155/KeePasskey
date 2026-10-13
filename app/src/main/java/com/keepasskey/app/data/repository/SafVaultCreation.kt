package com.keepasskey.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.SafDocumentCleanup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.files.NewVaultNames
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * SAF 新建：只写本次 CreateDocument 产物；先持久授权，验证空文档后写入、同步、读回复核。
 * 成功前不替换原会话，登记使用系统返回的实际名称/URI。清理仅限仍为空的本次文档。
 * 写入中断的部分文档保留供用户处理；SAF 无跨提供方原子提交，见已知工程限界 §24。
 * 普通后续保存仍为 rwt 非原子写且不支持同步。
 */
internal object SafVaultCreation {

    /**
     * 在 [targetUri] 指向的 SAF 文档建库。
     *
     * @param keyFileData 已解析好的复合密钥第二因子字节（生成型副本由调用方负责用毕擦除）
     * @param register 登记回调（复用仓库既有的 `importExternalDatabase`：写目录 + 置活动 + 刷新列表）
     */
    suspend fun create(
        context: Context,
        strings: StringsProvider,
        databaseSession: DatabaseSession,
        name: String,
        masterPassword: CharArray,
        keyFileData: ByteArray?,
        preset: CreateVaultPreset,
        targetUri: Uri,
        /** `ISSUE-P3-447` AC②：建库完成写入及读回复核、移交会话后留基线 */
        baselineHolder: com.keepasskey.app.security.VaultFileBaselineHolder? = null,
        register: suspend (name: String, path: String) -> KdbxResult<Unit>
    ): KdbxResult<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        // String URI 不是所有权证明；只有向导本次 CreateDocument 返回值可领取一次写入权。
        if (!NewVaultDocumentClaims.consume(targetUri)) return@withContext KdbxResult.Failure(
            IllegalArgumentException("非本流程创建的文档"), code = KdbxError.CREATE_COLLISION
        )
        var committed = false
        try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    targetUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return@withContext KdbxResult.Failure(t, strings.get(R.string.repo_saf_persist_consent_failed))
            }
            val actualName = documentName(context, targetUri)
            val created = databaseSession.createStream(
                pathIdentifier = targetUri.toString(),
                name = actualName.removeSuffix(NewVaultNames.EXTENSION),
                passwordChars = masterPassword,
                useArgon2 = preset.useArgon2,
                keyFileData = keyFileData,
                cipherUuid = preset.cipherUuid,
                writeNew = { bytes -> writeOwnedDocument(context, targetUri, bytes) },
                saveWriter = saveWriter(context, targetUri, actualName),
                trackedSaveWriter = trackedSaveWriter(context, targetUri, actualName)
            )
            if (created !is KdbxResult.Success) return@withContext created
            committed = true
            baselineHolder?.capture(
                com.keepasskey.app.security.VaultFileMetadataProbe.baselineFor(context, targetUri.toString())
            )
            register(actualName, targetUri.toString())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            KdbxResult.Failure(t, strings.get(R.string.repo_saf_write_failed))
        } finally {
            // 只有尚未成功移交会话、仍为空的本次文档可删。写入部分数据时宁可留件，避免误删竞争写入。
            if (!committed) deleteEmptyOwnedDocument(context, targetUri)
        }
    }

    /** 同一文件描述符验证空文档并写入，不以 w/rwt 截断任意已存在 URI。 */
    private fun writeOwnedDocument(context: Context, targetUri: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { descriptor ->
            if (descriptor.statSize < 0L) throw UnsupportedOperationException("提供方无法验证空文档")
            if (descriptor.statSize != 0L) throw java.nio.file.FileAlreadyExistsException("SAF 文档非空")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        } ?: throw IOException("无法写入新建文档")
        // 必须读回同样密文；不再关闭旧会话然后尝试重新解析，以免失败丢失旧会话。
        val readBack = context.contentResolver.openInputStream(targetUri)?.use { input ->
            val read = ByteArray(bytes.size)
            try {
                var offset = 0
                while (offset < read.size) {
                    val count = input.read(read, offset, read.size - offset)
                    if (count <= 0) throw IOException("新建文档读回不完整")
                    offset += count
                }
                read.contentEquals(bytes) && input.read() == -1
            } finally {
                read.fill(0)
            }
        } ?: false
        if (!readBack) throw IOException("新建文档读回不一致")
    }

    private fun documentName(context: Context, uri: Uri): String {
        val column = android.provider.OpenableColumns.DISPLAY_NAME
        return context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) throw IOException("新建文档名称不可用")
            cursor.getString(0).takeIf { it.isNotBlank() } ?: throw IOException("新建文档名称为空")
        } ?: throw IOException("新建文档名称不可用")
    }

    private fun deleteEmptyOwnedDocument(context: Context, uri: Uri) {
        try {
            val empty = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize == 0L } == true
            if (empty) SafDocumentCleanup.deleteCreatedDocument(context, uri)
        } catch (e: Exception) { // cancel-n/a: 阻塞式清理；无法核实归属时不删除
            com.keepasskey.core.log.AppLog.w("SafVaultCreation", "无法核实新建文档清理条件", e)
        }
    }

    /**
     * `content://` 库的写回通道（解锁与新建两条路径共用，ISSUE-P2-229 收敛为单点）。
     *
     * `"rwt"` 截断式写是 SAF 文档的能力上限（无原子 rename / 无 `.bak`），已登记于限界 §24。
     */
    fun saveWriter(context: Context, uri: Uri, displayName: String): suspend (ByteArray) -> Unit =
        { bytes ->
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "rwt")?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: throw IOException("无法写入目标数据库文件: $displayName")
            }
        }
    /** 流式换密的提交证据在 IO 内关闭流后登记，取消交付窗口不会抹掉已完成写入。 */
    fun trackedSaveWriter(context: Context, uri: Uri, displayName: String):
        suspend (ByteArray, com.keepasskey.database.session.SessionWriteReceipt) -> Unit = { bytes, receipt ->
        withContext(Dispatchers.IO) {
            receipt.writing() // rwt 打开即可能截断，不能等到 write 才声明风险。
            context.contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
            } ?: throw IOException("无法写入目标数据库文件: $displayName")
            receipt.committed()
        }
    }

}
