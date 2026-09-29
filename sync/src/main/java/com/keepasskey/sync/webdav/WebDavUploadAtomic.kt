package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.model.cleanEtag
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response

/**
 * WebDAV 事务上传（PUT 临时名 → MOVE）编排（ISSUE-P3-238 拆分自 [WebDavSyncProvider]，零行为变更）。
 *
 * 依赖注入：[execute] 为 Provider 的瞬时重试封装；[getMetadata] / [delete] / [upload] 为同实例委托。
 */
internal object WebDavUploadAtomic {

    suspend fun run(
        serverUrl: String,
        authHeader: String,
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?,
        remoteExists: Boolean?,
        upload: suspend (String, ByteArray, String?) -> Result<String>,
        getMetadata: suspend (String) -> Result<com.keepasskey.sync.model.RemoteFileMetadata>,
        delete: suspend (String) -> Result<Unit>,
        execute: suspend (Boolean, () -> Request) -> Response
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tmpPath = WebDavSyncProvider.atomicTmpPath(remotePath, UUID.randomUUID().toString())
            val tmpUploadResult = upload(tmpPath, data, null)
            if (tmpUploadResult.isFailure) {
                throw tmpUploadResult.exceptionOrNull() ?: SyncException.NetworkError("上传临时文件失败")
            }

            val sourceUrl = WebDavUrlCodec.buildUrl(serverUrl, tmpPath)
            val destUrl = WebDavUrlCodec.buildUrl(serverUrl, remotePath)

            val overwriteFlag = if (!expectedEtag.isNullOrBlank()) {
                "T"
            } else {
                val exists = remoteExists ?: getMetadata(remotePath).isSuccess
                if (exists) "T" else "F"
            }

            fun createMoveRequest(withPrecondition: Boolean = true): Request {
                val moveBuilder = Request.Builder()
                    .url(sourceUrl)
                    .method("MOVE", null)
                    .header("Authorization", authHeader)
                    .header("Destination", destUrl)
                if (withPrecondition && !expectedEtag.isNullOrBlank()) {
                    moveBuilder.header("Overwrite", overwriteFlag)
                    moveBuilder.header("If", "<$destUrl> ([${WebDavUrlCodec.formatHeaderEtag(expectedEtag)}])")
                } else {
                    moveBuilder.header("Overwrite", overwriteFlag)
                }
                return moveBuilder.build()
            }

            var moveResponse: Response? = null
            var moveSuccess = false
            var conflictError: SyncException.ConflictError? = null
            var overwriteConflictCode: Int? = null
            var lastMoveCode = 0

            for (attempt in 0..1) {
                try {
                    val resp = execute(!expectedEtag.isNullOrBlank()) { createMoveRequest() }
                    lastMoveCode = resp.code
                    if (resp.code == WebDavMoveOverwritePolicy.HTTP_PRECONDITION_FAILED) {
                        val currentMeta = getMetadata(remotePath).getOrNull()
                        conflictError = SyncException.ConflictError(
                            remoteEtag = currentMeta?.etag.orEmpty(),
                            localExpectedEtag = expectedEtag.orEmpty(),
                            message = WebDavMoveOverwritePolicy.preconditionFailureMessage()
                        )
                        resp.close()
                        break
                    }
                    if (resp.isSuccessful || resp.code == 201 || resp.code == 204) {
                        moveResponse = resp
                        moveSuccess = true
                        break
                    }
                    if (WebDavMoveOverwritePolicy.isOverwriteConflict(resp.code)) {
                        val conflictCode = resp.code
                        overwriteConflictCode = conflictCode
                        resp.close()
                        if (attempt == 0) {
                            WebDavMoveOverwritePolicy.logOverwriteConflict(conflictCode)
                            delete(remotePath)
                            continue
                        }
                        break
                    }
                    resp.close()
                } catch (e: Exception) {
                    if (attempt == 1) throw e
                }
            }

            if (!moveSuccess) {
                delete(tmpPath)
                if (conflictError != null) throw conflictError
                throw WebDavMoveOverwritePolicy.protocolErrorFor(overwriteConflictCode, lastMoveCode)
            }

            val finalEtag = moveResponse?.header("ETag")?.cleanEtag()
            moveResponse?.close()
            finalEtag?.ifBlank { null } ?: getMetadata(remotePath).getOrThrow().etag
        }
    }
}
