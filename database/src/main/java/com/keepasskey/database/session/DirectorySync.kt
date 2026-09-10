package com.keepasskey.database.session

import java.io.File

/**
 * 目录项 fsync 结果（ISSUE-P3-13）。
 *
 * 目录 fsync 属 POSIX 崩溃安全语义（rename/copy 成功后必须固化父目录目录项），
 * 但并非所有平台都开放目录通道：Windows 上 `FileChannel.open(dir, READ)` 抛
 * `AccessDeniedException`，无法真实执行该步骤。调用方据此区分「已真实固化」与
 * 「平台降级」，并保证降级一律不阻断写盘主流程。
 */
enum class DirectorySyncOutcome {
    /** 目录项已真实 fsync 落盘（POSIX 语义路径）。 */
    SYNCED,

    /** 当前平台/文件系统不支持目录 fsync（或执行失败），已降级为告警，调用方继续主流程。 */
    DEGRADED
}

/**
 * 目录同步窄接口（依赖倒置，ISSUE-P3-13）。
 *
 * 只表达一件事：对给定目录执行一次 fsync，固化其目录项变更。生产实现见 [PosixDirectorySync]。
 *
 * 之所以抽出该接口：`AtomicFileWriter` 的目录 fsync 在 Windows 宿主上必然降级，
 * 真实目录通道永远不被执行，导致「崩溃安全路径已被覆盖」缺少运行时证据。
 * 注入抽象后，单测可用假实现断言每一条落盘路径都触达了目录 fsync 钩子，
 * 无需依赖宿主平台能力。
 *
 * 实现契约：
 * 1. **禁止抛异常**——平台不支持 / 权限不足 / IO 失败一律返回 [DirectorySyncOutcome.DEGRADED]；
 * 2. 除告警日志外无副作用，不缓存、不长期持有目录句柄。
 */
fun interface DirectorySync {

    /**
     * 对 [directory] 执行目录项 fsync。
     *
     * @return [DirectorySyncOutcome.SYNCED] 表示已真实固化；[DirectorySyncOutcome.DEGRADED]
     *   表示当前平台/文件系统不支持，调用方必须继续主流程（降级不阻断）。
     */
    fun sync(directory: File): DirectorySyncOutcome

    companion object {
        /**
         * 生产默认实现（POSIX 目录通道，其它平台安全降级）。
         *
         * 作为 `AtomicFileWriter` 相关参数的默认值，使既有调用点与 DI 图完全不受影响。
         */
        val default: DirectorySync = PosixDirectorySync
    }
}
