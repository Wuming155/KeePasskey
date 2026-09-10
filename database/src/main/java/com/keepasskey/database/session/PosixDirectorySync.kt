package com.keepasskey.database.session

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * [DirectorySync] 的生产实现：POSIX 目录通道 fsync + 其它平台安全降级。
 *
 * 语义与 ISSUE-P2-05 既有行为逐字保持一致，仅把「注入点」从写死调用改为可替换实现：
 * - POSIX（Linux / macOS）：`FileChannel.open(dir, READ).force(true)` 固化目录项，返回 [DirectorySyncOutcome.SYNCED]；
 * - Windows 或只读挂载等不开放目录通道的平台：捕获异常、记录告警并返回 [DirectorySyncOutcome.DEGRADED]，
 *   **绝不抛出、绝不阻断写盘主流程**（Windows 宿主目录通道不可用属已知平台事实，见 ISSUE-P2-05）。
 */
object PosixDirectorySync : DirectorySync {

    private val logger = Logger.getLogger(PosixDirectorySync::class.java.name)

    override fun sync(directory: File): DirectorySyncOutcome = try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
        DirectorySyncOutcome.SYNCED
    } catch (e: Exception) {
        logger.log(
            Level.WARNING,
            "父目录 fsync 不受当前平台/文件系统支持（降级为告警，不阻断写盘）: ${directory.absolutePath}",
            e
        )
        DirectorySyncOutcome.DEGRADED
    }
}
