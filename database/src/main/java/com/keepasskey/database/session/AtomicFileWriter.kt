package com.keepasskey.database.session

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 工业级原子化安全写盘引擎。
 * 遵循数据完整性铁律：
 * 1. 写入临时文件 (.kdbx.tmp)
 * 2. 强同步落盘 (flush + FileDescriptor.sync)
 * 3. 滚动备份稳定版本 (.kdbx.bak)
 * 4. 原子重命名替换原文件
 *
 * 降级路径安全铁律：任何情况下严禁在替换成功之前无保护地删除原文件——
 * 一旦 delete 成功而后续 rename 失败，原文件将彻底丢失且无法恢复。
 */
object AtomicFileWriter {

    private val logger = Logger.getLogger(AtomicFileWriter::class.java.name)

    fun writeAtomic(targetFile: File, writer: (OutputStream) -> Unit) {
        val parentDir = targetFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tmpFile = File(parentDir, "${targetFile.name}.tmp")
        val bakFile = File(parentDir, "${targetFile.name}.bak")

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

            // 步骤 3: 备份当前稳定版本
            val backupAvailable = if (targetFile.exists()) {
                try {
                    Files.copy(
                        targetFile.toPath(),
                        bakFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
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
            } else {
                // 原文件不存在（首次写入），无需备份
                true
            }

            // 步骤 4: 原子重命名替换原文件
            try {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
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
     * 原子 move 失败后的安全降级替换。
     *
     * 安全铁律：严禁在 rename 之前无保护地 [File.delete] 原文件——
     * 若 delete 成功而 rename 失败，原文件将彻底丢失。降级顺序：
     * 1. 直接标准 rename：POSIX rename(2) 语义下可原子替换已存在目标，全程无需删除原文件；
     * 2. rename 失败（个别平台/文件系统拒绝替换已存在目标）时，仅在原文件已有 .bak
     *    备份兜底（或目标本不存在）时才以 copy 覆盖——即便 copy 中途失败，原文件内容
     *    仍可自备份恢复，绝不因本分支单点丢失；无备份兜底时宁可失败也绝不冒险覆盖。
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
        if (tmpFile.renameTo(targetFile)) {
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
    }
}
