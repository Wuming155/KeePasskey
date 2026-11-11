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
 * WebDAV 事务上传（PUT 临时名 → MOVE）编排（ISSUE-P3-238 拆分自 [WebDavSyncProvider]）。
 *
 * 依赖注入：[execute] 为 Provider 的瞬时重试封装；[getMetadata] / [delete] / [upload] 为同实例委托。
 * [delete] 只用于清理**本次事务自己的临时文件**——正式远端目标默认在任何失败路径下都不被删除
 * （ENG-20261028-20：删除目标 + 无预条件重试的兜底已移除）。
 *
 * ## 兼容写入（默认关闭）
 *
 * 坚果云等服务器对「MOVE 覆盖已有目标」恒返回 409/423，即便带 `Overwrite: T`。用户可在设置页
 * 显式开启 `allowDeleteBeforeMove`：在 409/423 且重探 ETag 确认远端未漂移后，
 * DELETE 正式目标 → 以 `Overwrite: F` 重试 MOVE。与 KP2A 的 `delete-then-move` 同骨架，差异：
 * - **不做 MOVE 前的无条件删除**：先试服务端原子覆盖，只有被拒且确认未漂移才删；
 * - **ETag 准入**：预期基线或重探 ETag 任一为空即不删（无可失效的锁，fail-closed）；
 * - **恢复 MOVE 用 `Overwrite: F`**：目标此时应不存在，若他端在 DELETE 与 MOVE 之间重建了
 *   目标，服务端须拒绝而非静默覆盖；旧 ETag 的 `If` 条件在目标删除后已失效，故不复用；
 * - **失败保留临时文件**：兼容 MOVE 失败时不清理临时文件，留作下轮恢复锚点。
 *
 * 仍需用户接受：云端文件在 DELETE 与 MOVE 之间短暂消失，进程死亡 / 取消可能让正式路径缺失。
 */
internal object WebDavUploadAtomic {

    suspend fun run(
        serverUrl: String,
        authHeader: String,
        remotePath: String,
        data: ByteArray,
        expectedEtag: String?,
        remoteExists: Boolean?,
        allowDeleteBeforeMove: Boolean = false,
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

            fun createMoveRequest(): Request {
                val moveBuilder = Request.Builder()
                    .url(sourceUrl)
                    .method("MOVE", null)
                    .header("Authorization", authHeader)
                    .header("Destination", destUrl)
                    .header("Overwrite", overwriteFlag)
                if (!expectedEtag.isNullOrBlank()) {
                    moveBuilder.header("If", "<$destUrl> ([${WebDavUrlCodec.formatHeaderEtag(expectedEtag)}])")
                }
                return moveBuilder.build()
            }

            /** 兼容路径专用：目标已被删除，用 `Overwrite: F` 拒绝覆盖他端窗口内重建的文件。 */
            fun createRecoveryMoveRequest(): Request = Request.Builder()
                .url(sourceUrl)
                .method("MOVE", null)
                .header("Authorization", authHeader)
                .header("Destination", destUrl)
                .header("Overwrite", "F")
                .build()

            var moveResponse: Response? = null
            var moveSuccess = false
            var conflictError: SyncException.ConflictError? = null
            var probeFailure: Throwable? = null
            var overwriteRefusedCode: Int? = null
            var lastMoveCode = 0
            var remoteDeleted = false
            var keepTemporaryFile = false

            // 携带预条件的 MOVE 才允许传输级重试（ISSUE-P3-298 ③）：临时文件 PUT 恒无条件不在此列，
            // MOVE 仅在 expectedEtag 非空时发送 `If`——服务端逐次重验基线，基线被推进即 412。
            val carriesPrecondition = !expectedEtag.isNullOrBlank()

            for (attempt in 0..1) {
                try {
                    val resp = execute(carriesPrecondition) { createMoveRequest() }
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
                        // ENG-20261028-20：服务器拒绝覆盖已有目标。**不再**走「DELETE 正式目标 +
                        // 无预条件重试」兜底——DELETE 成功而重试 MOVE 失败、取消或进程死亡会让正式
                        // 远端路径缺失，两步之间他端重建的目标又会被无 `If` 的 MOVE 静默覆盖。
                        // 例外仅在用户显式开启兼容写入且 ETag 可核验时（见下）。
                        val refusedCode = resp.code
                        overwriteRefusedCode = refusedCode
                        resp.close()
                        val metaResult = getMetadata(remotePath)
                        val currentMeta = metaResult.getOrNull()
                        when (
                            val guard = WebDavMoveOverwritePolicy.refusalGuard(currentMeta, expectedEtag)
                        ) {
                            is OverwriteRefusalGuard.Drifted -> {
                                conflictError = SyncException.ConflictError(
                                    remoteEtag = guard.currentRemoteEtag,
                                    localExpectedEtag = expectedEtag.orEmpty(),
                                    message = WebDavMoveOverwritePolicy.overwriteDriftMessage(refusedCode)
                                )
                            }
                            OverwriteRefusalGuard.ProbeFailed -> {
                                probeFailure = metaResult.exceptionOrNull()
                                    ?: SyncException.NetworkError("WebDAV 原子写入覆盖被拒后远端元数据重探失败")
                            }
                            OverwriteRefusalGuard.RemoteUnchanged -> {
                                if (WebDavMoveOverwritePolicy.canDeleteBeforeMove(
                                        enabled = allowDeleteBeforeMove,
                                        expectedEtag = expectedEtag,
                                        currentRemoteEtag = currentMeta?.etag
                                    )
                                ) {
                                    try {
                                        val deleteResult = delete(remotePath)
                                        if (deleteResult.isFailure) {
                                            probeFailure = deleteResult.exceptionOrNull()
                                                ?: SyncException.NetworkError("WebDAV 兼容写入删除正式目标失败")
                                        } else {
                                            remoteDeleted = true
                                            // 恢复 MOVE 不带 `If`（旧 ETag 已随删除失效）、不重试，
                                            // `Overwrite: F` 使他端在窗口内重建目标时服务端必须拒绝。
                                            val recovery = execute(false) { createRecoveryMoveRequest() }
                                            lastMoveCode = recovery.code
                                            if (recovery.isSuccessful || recovery.code == 201 || recovery.code == 204) {
                                                moveResponse = recovery
                                                moveSuccess = true
                                            } else {
                                                probeFailure = SyncException.ProtocolError(
                                                    recovery.code,
                                                    "WebDAV 兼容写入 MOVE 失败 (HTTP ${recovery.code})，" +
                                                        "云端旧文件已删除，临时文件保留待重试"
                                                )
                                                recovery.close()
                                                keepTemporaryFile = true
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // 不得落入外层 for 的「再试一次原始 MOVE」：那会重复删除目标。
                                        probeFailure = e
                                        keepTemporaryFile = true
                                    }
                                }
                            }
                        }
                        break
                    }
                    resp.close()
                } catch (e: CancellationException) {
                    // ISSUE-P3-555：取消不参与「失败重试」——`CancellationException` 是
                    // `IllegalStateException` 子类，会被下面的 `catch (e: Exception)` 吞掉并按
                    // 「第 2 次尝试失败」静默跳过，使结构化并发的取消契约在此失效。
                    throw e
                } catch (e: Exception) {
                    if (attempt == 1) throw e
                }
            }

            if (!moveSuccess) {
                // 兼容写入失败时临时文件是恢复锚点，不得清理；其余失败路径只清理本次临时文件。
                if (!keepTemporaryFile) delete(tmpPath)
                val error = WebDavMoveOverwritePolicy.protocolErrorFor(
                    overwriteRefusedCode, lastMoveCode, remoteDeleted
                )
                // 覆盖被拒是可导出的决定性事件（AppLog.w 带异常重载经诊断桥入缓冲）。
                overwriteRefusedCode?.let {
                    WebDavMoveOverwritePolicy.logOverwriteRefusal(it, remoteDeleted, error)
                }
                probeFailure?.let { throw it }
                conflictError?.let { throw it }
                throw error
            }

            val finalEtag = moveResponse?.header("ETag")?.cleanEtag()
            moveResponse?.close()
            finalEtag?.ifBlank { null } ?: getMetadata(remotePath).getOrThrow().etag
        }
    }
}
