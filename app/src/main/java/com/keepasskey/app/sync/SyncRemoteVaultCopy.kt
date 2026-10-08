package com.keepasskey.app.sync

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultCopyNaming
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

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
        var providerForErase: SyncProvider? = null
        try {
            val provider = session.testSyncProvider ?: providerResolver.resolveProvider()
                ?: return@withContext KdbxResult.Failure(
                    IllegalStateException("无同步凭据"),
                    strings.get(R.string.sync_error_no_sync_credentials)
                )
            providerForErase = provider
            val filesDir = context.filesDir
                ?: return@withContext KdbxResult.Failure(
                    IllegalStateException("No filesDir"),
                    strings.get(R.string.repo_files_dir_unavailable)
                )
            val remotePath = session.testRemotePath ?: providerResolver.resolveRemotePath(vaultFileName)
            val remoteName = remotePath.substringAfterLast('/').ifBlank { vaultFileName }
            val target = VaultCopyNaming.uniqueCopyTarget(File(filesDir, remoteName), LocalDateTime.now())
            val tmp = File(filesDir, "${target.name}.backup")
            try {
                val downloaded = tmp.outputStream().use { sink -> provider.download(remotePath, sink).isSuccess }
                if (!downloaded || !tmp.renameTo(target)) {
                    return@withContext KdbxResult.Failure(
                        IllegalStateException("云端副本下载或落盘失败"),
                        strings.get(R.string.sync_error_vault_copy_backup_failed)
                    )
                }
                KdbxResult.Success(target.absolutePath)
            } finally {
                tmp.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            KdbxResult.Failure(e, strings.get(R.string.sync_error_vault_copy_backup_failed))
        } finally {
            (providerForErase as? S3SyncProvider)?.clearCredentials()
        }
    }
