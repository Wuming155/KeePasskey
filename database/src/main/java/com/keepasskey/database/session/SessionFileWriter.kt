package com.keepasskey.database.session

import java.io.File
import java.io.OutputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 会话原子写盘与滚动备份（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 承接拆分前 [DatabaseSession] 内的 `writeAtomicByBackupPreference` 与 `deleteBackupQuietly`：
 * 写盘走 [AtomicFileWriter] 的「临时文件 → fsync → 原子重命名」路径；是否生成 `.bak`
 * 由构造注入的偏好提供方实时决定（对应拆分前读取 `createBackupBeforeSave`）。
 */
internal class SessionFileWriter(private val createBackupProvider: () -> Boolean) {

    private val logger = Logger.getLogger(SessionFileWriter::class.java.name)

    /**
     * 按会话备份偏好执行原子写盘。
     *
     * 关闭偏好时既不再生成 `.bak`，也顺带清理历史遗留的 `.bak`——用户关闭「保存前备份」
     * 后旧密文快照不应继续驻留磁盘。写盘前一次性读取偏好，保证同一次写入的
     * 「是否备份」与「是否清理」语义一致。
     */
    fun writeAtomicByBackupPreference(targetFile: File, writer: (OutputStream) -> Unit) {
        val createBackup = createBackupProvider()
        AtomicFileWriter.writeAtomic(targetFile, createBackup, writer)
        if (!createBackup) {
            deleteBackupQuietly(targetFile)
        }
    }

    /**
     * 静默删除滚动备份。
     *
     * IO 异常/权限不足一律记录告警，绝不阻断主流程（备份清理失败不影响本次写盘结果）。
     * [targetFile] 为 null 表示当前会话无本地文件（如 SAF 流式通道），无需清理。
     */
    fun deleteBackupQuietly(targetFile: File?) {
        if (targetFile == null) {
            return
        }
        try {
            AtomicFileWriter.deleteBackup(targetFile)
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "清理滚动备份失败（仅告警，不阻断主流程）: ${targetFile.absolutePath}",
                e
            )
        }
    }

    /**
     * 从滚动备份（.bak）恢复目标文件（`ISSUE-P2-521`）。
     *
     * 语义：备份字节经「临时文件 → fsync → 原子重命名」写回主文件（复用 [AtomicFileWriter.writeAtomic]），
     * **不轮换备份**（createBackup=false：绝不把损坏的主文件覆盖成新 .bak）且**不删除备份**——
     * 恢复后 .bak 仍在主文件旁，可再次恢复。内容为上次成功保存的版本（.bak 的既有语义）。
     * 失败仅记语义化告警并返回 false（调用方据此呈现用户可见失败提示；绝不静默）。
     * 边界：原子替换的极少数降级路径（同目录 ATOMIC_MOVE 失败）按上层既定口径拒绝无保护覆盖 ⇒ 返回 false，
     * 主文件与备份均保持原状（fail-closed，不产生半截文件）。
     *
     * @return true = 已从备份恢复；false = 无备份 / 恢复失败（主文件与 .bak 均未受损）。
     */
    fun restoreFromRollingBackup(targetFile: File): Boolean {
        val bakFile = AtomicFileWriter.backupFileFor(targetFile)
        if (!bakFile.exists()) {
            return false
        }
        return try {
            val bytes = bakFile.readBytes()
            try {
                AtomicFileWriter.writeAtomic(targetFile, createBackup = false) { it.write(bytes) }
            } finally {
                bytes.fill(0)
            }
            true
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "从滚动备份恢复失败（主文件与备份均未改动）: ${targetFile.name}",
                e
            )
            false
        }
    }

    /**
     * 活动文件的滚动备份当前是否存在（ISSUE-P2-520 换密残余面读数）。
     *
     * 命名复用 [AtomicFileWriter.backupFileFor] 单一来源，不在本层复刻 `.bak` 规则；
     * [targetFile] 为 null（SAF 流式通道 / 无本地文件）恒 false。调用方负责调度器
     * （文件 stat 不在 Main 执行）。
     */
    fun rollingBackupExists(targetFile: File?): Boolean {
        if (targetFile == null) {
            return false
        }
        return AtomicFileWriter.backupFileFor(targetFile).exists()
    }
}
