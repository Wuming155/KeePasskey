package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultCopyNaming
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.SecureFileWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

/** ISSUE-P3-582：云端副本另存的下载临时件后缀（唯一临时名为 `<目标名>.<UUID>.backup`） */
private const val SUFFIX_BACKUP = ".backup"

/**
 * `ISSUE-P2-529` AC①：「整库覆盖」前的**云端副本另存**。
 *
 * 「留痕」按 `ISSUE-P3-305` 同款惯例：本项与 `SyncCycleRunner` 的其余生命周期无共同变化理由，
 * 且该文件行数已贴 `tier2` 档沿，故以**同包扩展函数**承载（结构拆出，方法体逐字未改）——
 * 扩展可直接访问 `SyncCycleRunner` 的 `internal` 成员，不改变任何公开 API。
 *
 * 语义：把**当前同步目标（云端副本）**另存为本地独立库——只下载远端库字节并落为
 * `filesDir/<远端名> (副本 yyyyMMdd-HHmm).kdbx`（命名与落点见 `VaultCopyNaming`；副本仍是密文
 * `.kdbx`，以其自身凭据可解，且落在应用私有目录故按目录扫描的密码库列表即可见，AC②）。
 * 本方法**只下载、不改动云端任何字节**，供「整库覆盖并改绑」前的保留动作使用。
 *
 * `ISSUE-P3-582`：下载临时件与落地副本文件均经 core `SecureFileWrite` 收敛为仅属主 0600
 * （唯一临时名 `<目标名>.<UUID>.backup`），与 sync 模块落盘口径对齐、不依赖 umask。
 *
 * fail-closed：无活动库 / 无凭据 / 无 filesDir / 下载或落盘失败一律 `Failure`，
 * 调用方（`SyncCoordinator.backupCloudVaultCopy` → `VaultListSyncController`）据此**中止覆盖**。
 */
internal suspend fun SyncCycleRunner.backupRemoteVaultCopy(): KdbxResult<String> =
    withContext(Dispatchers.IO) {
        val currentDb = databaseSession.databaseFlow.value
            ?: return@withContext KdbxResult.Failure(
                IllegalStateException("库未解锁"),
                strings.get(R.string.sync_error_vault_not_unlocked)
            )
        val vaultFileName = databaseSession.currentFile?.name ?: "${currentDb.databaseName}.kdbx"
        try {
            providerResolver.useProvider(session.testSyncProvider) { provider ->
                val filesDir = context.filesDir
                    ?: return@useProvider KdbxResult.Failure(
                        IllegalStateException("No filesDir"),
                        strings.get(R.string.repo_files_dir_unavailable)
                    )
                val remotePath = session.testRemotePath ?: providerResolver.resolveRemotePath(vaultFileName)
                val remoteName = remotePath.substringAfterLast('/').ifBlank { vaultFileName }
                val target = VaultCopyNaming.uniqueCopyTarget(File(filesDir, remoteName), LocalDateTime.now())
                // ISSUE-P3-582：唯一临时名 + 打开流即收敛 0600（对齐 sync `SyncCacheFiles` 口径，
                // 不依赖 umask）；失败清理只删自己的 tmp，绝不误删并发写者
                val tmp = SecureFileWrite.uniqueTmpFor(target, SUFFIX_BACKUP)
                try {
                    val downloaded = SecureFileWrite.openOwnerOnlyOutput(tmp).use { sink ->
                        provider.download(remotePath, sink).isSuccess.also { ok ->
                            if (ok) {
                                sink.flush()
                                sink.fd.sync()
                            }
                        }
                    }
                    val delivered = if (!downloaded) {
                        false
                    } else {
                        try {
                            SecureFileWrite.moveAtomically(tmp, target)
                        } catch (e: IOException) {
                            false
                        }
                    }
                    if (!delivered) {
                        return@useProvider KdbxResult.Failure(
                            IllegalStateException("云端副本下载或落盘失败"),
                            strings.get(R.string.sync_error_vault_copy_backup_failed)
                        )
                    }
                    KdbxResult.Success(target.absolutePath)
                } finally {
                    tmp.delete()
                }
            } ?: KdbxResult.Failure(
                IllegalStateException("无同步凭据"),
                strings.get(R.string.sync_error_no_sync_credentials)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            KdbxResult.Failure(e, strings.get(R.string.sync_error_vault_copy_backup_failed))
        }
    }
