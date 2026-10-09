package com.keepasskey.sync.webdav

import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.network.runCatchingCancellable
import com.keepasskey.sync.model.cleanEtag
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
        runCatchingCancellable {
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
            var probeFailure: Throwable? = null
            var overwriteConflictCode: Int? = null
            var lastMoveCode = 0

            // ISSUE-P2-500：409/423 兜底会在 attempt 0 先 DELETE 目标；此后目标已不存在，
            // 重试（attempt 1）**必须不带 `If` 预条件**（在按 RFC 4918 评估 `If` 的服务器上
            // 带 `If` 的重试恒 412，兜底退化为「先删远端目标 → 必败」，比不兜底更差）。
            // 该位同时驱动传输级重试开关——ISSUE-P3-298 ③：无条件写禁止重试（见下方 execute）。
            var withPrecondition = true

            for (attempt in 0..1) {
                try {
                    val requestWithPrecondition = withPrecondition
                    // retryable 与「请求是否**实际**携带服务器预条件」逐字对齐（ISSUE-P3-298 ③）：
                    // 临时文件 PUT 恒无条件不在此列；MOVE 仅在 withPrecondition 且 expectedEtag 非空
                    // 时才发送 `If`——本条 409/423 兜底重试（withPrecondition=false）因此 retryable=false。
                    val carriesPrecondition = requestWithPrecondition && !expectedEtag.isNullOrBlank()
                    val resp = execute(carriesPrecondition) {
                        createMoveRequest(withPrecondition = requestWithPrecondition)
                    }
                    lastMoveCode = resp.code
                    if (resp.code == WebDavMoveOverwritePolicy.HTTP_PRECONDITION_FAILED) {
                        resp.close()
                        // ISSUE-P2-501：探测**失败**（Result.Failure）与「无 ETag 服务器」
                        // （Success + 空 etag，见 WebDavSyncProvider.getMetadata 的 `ifBlank` 回退）
                        // 是两回事——把失败折成空串会经 commitLocal 冲突分支（无回填）流入合并上传，
                        // 经 conflictUploadExpectedEtag 折 null ⇒ 无 `If` 的 MOVE(Overwrite:T)，
                        // 剥除合并窗口的乐观锁并静默覆盖他端写入。fail-closed：如实上抛探测失败，
                        // 上层归 RemoteUnreachable（本地缓存已保留），下轮 openRemote 重探得真实基线。
                        val metaResult = getMetadata(remotePath)
                        val currentMeta = metaResult.getOrNull()
                        if (currentMeta == null) {
                            probeFailure = metaResult.exceptionOrNull()
                                ?: SyncException.NetworkError("WebDAV 原子写入 412 后远端元数据重探失败")
                            break
                        }
                        conflictError = SyncException.ConflictError(
                            remoteEtag = currentMeta.etag,
                            localExpectedEtag = expectedEtag.orEmpty(),
                            message = WebDavMoveOverwritePolicy.preconditionFailureMessage()
                        )
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
                            // 目标已删，预条件恒失败且无意义——重试改走无 `If` 形态
                            withPrecondition = false
                            continue
                        }
                        break
                    }
                    resp.close()
                } catch (e: CancellationException) {
                    // ISSUE-P3-555：取消不参与「换形态重试」——`CancellationException` 是
                    // `IllegalStateException` 子类，会被下面的 `catch (e: Exception)` 吞掉并按
                    // 「第 2 次尝试失败」静默跳过，使结构化并发的取消契约在此失效。
                    throw e
                } catch (e: Exception) {
                    if (attempt == 1) throw e
                }
            }

            if (!moveSuccess) {
                delete(tmpPath)
                // ISSUE-P2-501：探测失败优先如实上抛（临时文件已在上方清理）
                probeFailure?.let { throw it }
                if (conflictError != null) throw conflictError
                throw WebDavMoveOverwritePolicy.protocolErrorFor(overwriteConflictCode, lastMoveCode)
            }

            val finalEtag = moveResponse?.header("ETag")?.cleanEtag()
            moveResponse?.close()
            finalEtag?.ifBlank { null } ?: getMetadata(remotePath).getOrThrow().etag
        }
    }
}
