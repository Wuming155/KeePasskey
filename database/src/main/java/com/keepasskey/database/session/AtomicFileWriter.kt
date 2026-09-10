package com.keepasskey.database.session

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 工业级原子化安全写盘引擎。
 * 遵循数据完整性铁律：
 * 1. 写入临时文件 (.kdbx.tmp)
 * 2. 强同步落盘 (flush + FileDescriptor.sync)
 * 3. 滚动备份稳定版本 (.kdbx.bak，可按会话偏好关闭)
 * 4. 原子重命名替换原文件
 * 5. 目录项 fsync（ISSUE-P2-05：rename/copy 成功后固化父目录目录项，崩溃不回退）
 *
 * 降级路径安全铁律：任何情况下严禁在替换成功之前无保护地删除原文件——
 * 一旦 delete 成功而后续 rename 失败，原文件将彻底丢失且无法恢复。
 */
object AtomicFileWriter {

    private val logger = Logger.getLogger(AtomicFileWriter::class.java.name)

    /** 临时文件后缀（与目标同目录，保证 rename 处于同一文件系统内） */
    private const val TEMP_SUFFIX = ".tmp"

    /** 滚动备份后缀 */
    private const val BACKUP_SUFFIX = ".bak"

    /**
     * 原子写入目标文件。
     *
     * @param createBackup 是否在替换前保留一份滚动备份 .bak（默认 true，保持既有调用行为）。
     *   ISSUE-P2-11 (ZT-16)：该开关由会话层偏好下发，关闭时不再生成备份；同时因缺少备份兜底，
     *   降级分支对已存在的原文件会拒绝无保护覆盖（宁可失败也不损坏数据）。
     * @param writer 向临时文件写入内容的回调
     */
    fun writeAtomic(
        targetFile: File,
        createBackup: Boolean = true,
        writer: (OutputStream) -> Unit
    ) {
        val parentDir = targetFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tmpFile = File(parentDir, targetFile.name + TEMP_SUFFIX)
        val bakFile = backupFileFor(targetFile)

        // 确保临时文件不存在
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        try {
            // 步骤 1 & 2: 写入临时文件并强同步落盘
            FileOutputStream(tmpFile).use { fos ->
                writer(fos)
                fos.flush()
                fos.fd.sync()
            }

            // 步骤 3: 备份当前稳定版本（按偏好可关闭）
            val backupAvailable = backupStableVersion(targetFile, bakFile, parentDir, createBackup)

            // 步骤 4: 原子重命名替换原文件
            try {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                // 步骤 5: rename 后 fsync 父目录，确保新目录项在崩溃/断电前已落盘
                syncDirectory(parentDir)
            } catch (e: Exception) {
                fallbackReplace(tmpFile, targetFile, backupAvailable, e)
            }
        } catch (t: Throwable) {
            // 异常退出时清理临时垃圾，绝不损坏原文件
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
            throw t
        }
    }

    /**
     * 步骤 3：滚动备份当前稳定版本，并对父目录执行 fsync 固化备份目录项。
     *
     * @return 备份是否可用（作为降级覆盖的安全兜底）。原文件不存在（首次写入）视为无需备份，
     *   返回 true；偏好关闭时不生成备份，返回 false（降级分支据此拒绝无保护覆盖）。
     */
    private fun backupStableVersion(
        targetFile: File,
        bakFile: File,
        parentDir: File,
        createBackup: Boolean
    ): Boolean {
        if (!targetFile.exists()) {
            // 原文件不存在（首次写入），无需备份
            return true
        }
        if (!createBackup) {
            // ISSUE-P2-11 (ZT-16)：会话关闭了保存前备份，本次不生成 .bak，亦无备份兜底
            return false
        }
        return try {
            Files.copy(
                targetFile.toPath(),
                bakFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            // ISSUE-P2-05：备份目录项变更同样需要 fsync 固化
            syncDirectory(parentDir)
            true
        } catch (e: Exception) {
            // 备份尝试若因权限受阻，记录但不阻断主流程（原子 move 主路径不依赖备份）。
            // 日志仅含文件路径与异常原因，不含任何敏感内容。
            logger.log(
                Level.WARNING,
                "原子写盘备份原文件失败（不阻断主流程）: ${targetFile.absolutePath}",
                e
            )
            false
        }
    }

    /**
     * 原子 move 失败后的安全降级替换。
     *
     * 安全铁律：严禁在 rename 之前无保护地 [File.delete] 原文件——
     * 若 delete 成功而 rename 失败，原文件将彻底丢失。降级顺序：
     * 1. 直接标准 rename：POSIX rename(2) 语义下可原子替换已存在目标，全程无需删除原文件；
     * 2. rename 失败（个别平台/文件系统拒绝替换已存在目标）时，仅在原文件已有 .bak
     *    备份兜底（或目标本不存在）时才以 copy 覆盖——即便 copy 中途失败，原文件内容
     *    仍可自备份恢复，绝不因本分支单点丢失；无备份兜底时宁可失败也绝不冒险覆盖。
     *
     * ISSUE-P2-05：本方法为崩溃安全敏感路径——renameTo / Files.copy 成功后必须 fsync
     * 父目录，否则目录项变更在断电时可能尚未落盘。
     *
     * @param backupAvailable [writeAtomic] 步骤 3 的 .bak 备份是否成功（原文件不存在视为 true）
     * @param cause 触发降级的原子 move 异常
     */
    internal fun fallbackReplace(
        tmpFile: File,
        targetFile: File,
        backupAvailable: Boolean,
        cause: Exception
    ) {
        val parentDir = targetFile.parentFile ?: tmpFile.parentFile ?: File(".")
        if (tmpFile.renameTo(targetFile)) {
            // ISSUE-P2-05：rename 成功后 fsync 父目录，固化被替换的目录项
            syncDirectory(parentDir)
            return
        }
        if (targetFile.exists() && !backupAvailable) {
            throw IOException(
                "无法安全替换 ${targetFile.name}：标准 rename 失败且原文件缺少可用备份，拒绝无保护覆盖",
                cause
            )
        }
        try {
            Files.copy(
                tmpFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (copyEx: Exception) {
            logger.log(Level.WARNING, "原子写盘降级 copy 覆盖失败: ${targetFile.absolutePath}", copyEx)
            throw IOException("无法将临时文件 ${tmpFile.name} 安全替换至 ${targetFile.name}", copyEx)
        }
        // Files.copy 不删除源文件，覆盖成功后清理临时文件
        if (tmpFile.exists() && !tmpFile.delete()) {
            logger.log(Level.WARNING, "原子写盘降级替换后清理临时文件失败: ${tmpFile.absolutePath}")
        }
        // ISSUE-P2-05：copy 覆盖后 fsync 父目录，固化目录项变更
        syncDirectory(parentDir)
    }

    /**
     * 返回目标文件对应的滚动备份路径（目标名 + .bak）。
     *
     * 供会话层在关闭备份偏好或凭据轮换后定位并清理历史遗留备份，
     * 避免备份路径拼接逻辑在多处漂移。
     */
    internal fun backupFileFor(targetFile: File): File =
        File(targetFile.parentFile ?: File("."), targetFile.name + BACKUP_SUFFIX)

    /**
     * 删除目标文件对应的滚动备份（不存在视为成功）。
     *
     * ISSUE-P2-11 (ZT-16)：关闭「保存前备份」偏好时清理历史遗留 .bak；
     * 凭据轮换成功后旧密文快照（可被旧口令解开）必须失效。
     * 删除失败仅记录告警并返回 false，绝不在此抛出以阻断主流程。
     */
    internal fun deleteBackup(targetFile: File): Boolean {
        val bakFile = backupFileFor(targetFile)
        if (!bakFile.exists()) {
            return true
        }
        return if (bakFile.delete()) {
            true
        } else {
            logger.log(Level.WARNING, "删除滚动备份失败（仅告警，不阻断主流程）: ${bakFile.absolutePath}")
            false
        }
    }

    /**
     * ISSUE-P2-05：对目录本身执行 fsync，固化 rename/copy 造成的目录项变更。
     *
     * POSIX crash-safety 要求 rename 后 fsync 父目录：否则断电后新目录项可能未持久化，
     * 造成文件丢失或回退到旧版本。平台/文件系统不支持目录通道（如 Windows）或只读挂载时，
     * 捕获异常降级为告警日志，绝不阻断写盘主流程。
     */
    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "父目录 fsync 不受当前平台/文件系统支持（降级为告警，不阻断写盘）: ${directory.absolutePath}",
                e
            )
        }
    }
}
