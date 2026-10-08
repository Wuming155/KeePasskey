package com.keepasskey.app.sync

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「本地密码库**文件**自上次成功同步以来是否被重写过」的进程内检查点（`ISSUE-P2-536`）。
 *
 * ## 解决的问题（§372 遗留的那一半）
 *
 * 锁库按 `ISSUE-P1-07` 清空 `cacheDir/sync`，`<hash>.cache`（上次同步的工作副本）连带
 * `.version` / `.baseversion` 一并销毁 ⇒ 下一次冷启动的「本地内容变了吗」判定**失去全部证据**，
 * 只能返回占位 [LocalContentChangeState.BASELINE_MISSING]。§372 用「与已下载远端字节做内容级
 * 比较」补上了**两端一致**那一半；**差异全在远端一侧**（他端改了云端、本地一行未改）时，
 * 占位投影出的 `hasLocalContentChanged = true` 仍被当作「本地已修改」的证据送进合并上传
 * ⇒ 用户零编辑却看到「本地修改已上传至云端」并整库重传（`ISSUE-P2-536` 的实跑复现读数：
 * `outcome=MergedAndUploaded`、`uploads 1→2`、本地条目集合未变）。
 *
 * 本类补上判定缺失的那件证据：**本地库文件字节的 SHA-256**（在「已与远端收敛」的周期收尾记录）。
 * 冷启动时若**当前文件摘要与记录一致**，即可判定「本地自上次同步以来未被重写过」——
 * 该结论与「缓存存活时 `resolveLocalContentChanged` 比出 `UNCHANGED`」等价，
 * 使「锁库后的冷启动」与「进程被杀后的冷启动」收敛到同一条**远端接管**路径
 * （`handleRemoteSynced` 的远端接管尾段 + `UpdatedCachedFileOnLoad` 诚实文案）。
 *
 * ## 口径与边界（改前必读）
 *
 * - **只记「已收敛」的结论**（[SyncOutcome.UpToDate] / `UploadedLocal` / `MergedAndUploaded`）；
 *   `Error` / `Offline` / `ConflictNeedsUser` / `VaultBindingMismatch` **一律不记**——
 *   冲突待决期本地可能正持有未上传编辑，记录会让下一轮把「本地未动」判真并据此丢弃它。
 * - **进程内，不落盘**：进程被杀时缓存本身存活（锁回调未触发即不驱逐），该场景由
 *   `SyncContentChangeDetector` 的缓存解析分支承担同一判定，本机制无增量价值。
 *   「进程被杀 **且** 缓存被系统回收」这一残余组合退化为保守的合并上传（如实登记于批次文档）。
 * - **不随锁库销毁**：本值是**密文文件的摘要**（无明文、无凭据、不可逆推），
 *   与 `P1-07` 要销毁的密文快照不同类；且**销毁即令本机制失效**。失配一律保守回退，
 *   摘要天然自证（换库 / 换远端路径都不会误命中）。
 * - **SAF 通道**（`currentFile == null`）无本地文件可比 ⇒ 本机制旁路，行为与整改前一致。
 */
@Singleton
class SyncLocalFileCheckpoint @Inject constructor() {

    /** 记录归属的远端路径（换绑即视为无记录，防跨库误命中）。 */
    private var recordedRemotePath: String? = null

    /** 上次收敛收尾时的本地库文件摘要（SHA-256 十六进制小写）。 */
    private var recordedDigest: String? = null

    /**
     * 判定「本地库文件自上次成功同步以来未被重写」。
     *
     * 记录缺失、远端路径不符、文件不可读（无 File / 读取失败 / 非普通文件）一律返回
     * **false**（保守：宁可按「本地可能变过」处理，绝不据此丢弃本地内容）。
     */
    fun isUnchangedSinceSync(remotePath: String, localFile: File?): Boolean {
        val recorded = recordedDigest.takeIf { recordedRemotePath == remotePath } ?: return false
        return recorded == digestOf(localFile)
    }

    /** 记录本次成功同步收尾时的本地库文件摘要（文件不可读时置空记录，不留陈旧值）。 */
    fun record(remotePath: String, localFile: File?) {
        recordedRemotePath = remotePath
        recordedDigest = digestOf(localFile)
    }

    /** 清空记录（同步关系终止 / 换库治理面使用；判定本身已自证，非必需）。 */
    fun clear() {
        recordedRemotePath = null
        recordedDigest = null
    }

    /**
     * 逐块计算文件摘要——**不整份物化**（本地库为整库密文，可达数十 MiB，
     * 对齐 `SyncCache.receiveRemote` 的流式口径）。不可读即 null。
     */
    private fun digestOf(file: File?): String? {
        val target = file ?: return null
        if (!target.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            FileInputStream(target).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHexString()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}

/**
 * 「本轮同步结束时本地与远端已收敛」的判据（`ISSUE-P2-536`：检查点记录点的**唯一**判据）。
 *
 * 三个成功结论都保证「云端内容 == 本地内容」：`UpToDate`（两端一致 / 远端已接管）、
 * `UploadedLocal`（本地赢已上传）、`MergedAndUploaded`（合并产物已上传）。
 * 其余（`Error` / `Offline` / `ConflictNeedsUser` / `VaultBindingMismatch`）**不得**记录检查点。
 */
internal fun SyncOutcome.isInSyncWithRemote(): Boolean = when (this) {
    is SyncOutcome.UpToDate,
    is SyncOutcome.UploadedLocal,
    is SyncOutcome.MergedAndUploaded -> true

    is SyncOutcome.ConflictNeedsUser,
    is SyncOutcome.Offline,
    is SyncOutcome.Error,
    is SyncOutcome.VaultBindingMismatch -> false
}
