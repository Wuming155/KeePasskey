package com.keepasskey.database.session

import java.io.File

/**
 * [DirectorySync] 共享测试替身（ISSUE-P3-13 引入，ISSUE-P3-26 起被两个测试类共用）。
 *
 * 之所以抽出到独立文件而非各自私有定义：`AtomicFileWriter` 的目录 fsync 在 Windows 宿主上
 * 必然降级、真实目录通道永不执行，故「每条目录项变更路径都触达目录同步钩子」这一断言
 * 只能靠注入替身完成；两个测试类（[AtomicFileWriterTest] 与
 * [AtomicFileWriterBackupDeletionTest]）共用同一份替身可保证计数语义完全一致，
 * 且避免同一假实现被复制粘贴（同时让两个测试文件各自保持在约 400 行阈值内）。
 */
internal class RecordingDirectorySync(
    private val outcome: DirectorySyncOutcome = DirectorySyncOutcome.SYNCED
) : DirectorySync {

    /** 每次目录 fsync 的目标目录（按调用顺序） */
    val syncedDirectories = mutableListOf<File>()

    /** 每次目录 fsync 的返回结果（按调用顺序） */
    val outcomes = mutableListOf<DirectorySyncOutcome>()

    override fun sync(directory: File): DirectorySyncOutcome {
        syncedDirectories += directory
        outcomes += outcome
        return outcome
    }
}

/** 委派真实默认实现的探针：用于在真实宿主上核验目录 fsync 是否降级（POSIX 恒 SYNCED / Windows 恒 DEGRADED）。 */
internal class ProbingDirectorySync(
    private val delegate: DirectorySync = DirectorySync.default
) : DirectorySync {
    val outcomes = mutableListOf<DirectorySyncOutcome>()

    override fun sync(directory: File): DirectorySyncOutcome =
        delegate.sync(directory).also { outcomes += it }
}
