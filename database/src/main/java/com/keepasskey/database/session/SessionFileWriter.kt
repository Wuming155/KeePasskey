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
}
