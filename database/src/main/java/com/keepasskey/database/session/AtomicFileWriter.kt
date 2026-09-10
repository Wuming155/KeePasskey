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
 * 3. 滚动备份稳定版本 (.kdbx.bak，可按会话偏好关闭)
 * 4. 原子重命名替换原文件
 * 5. 目录项 fsync（ISSUE-P2-05：rename/copy 成功后固化父目录目录项，崩溃不回退）
 *
 * 降级路径安全铁律：任何情况下严禁在替换成功之前无保护地删除原文件——
 * 一旦 delete 成功而后续 rename 失败，原文件将彻底丢失且无法恢复。
 *
 * ISSUE-P3-13：目录 fsync 经 [DirectorySync] 抽象注入，本类不再写死平台调用。
 * 五条目录项变更路径均触达该钩子：① .bak 滚动备份 copy 后、② 主路径原子 move 后、
 * ③ 降级标准 rename 成功后、④ 降级 copy 覆盖后、
 * ⑤ 删除滚动备份 `.bak` 后（ISSUE-P3-26：unlink 同属目录项变更，不 fsync 则断电后
 * 「已删除的备份」可能随目录项回滚而复活，旧口令可解的密文快照重新出现）；
 * Windows 等不支持目录通道的平台由实现返回降级结果，仅记告警，**绝不阻断写盘**。
 */
object AtomicFileWriter {

    private val logger = Logger.getLogger(AtomicFileWriter::class.java.name)

    /** 临时文件后缀（与目标同目录，保证 rename 处于同一文件系统内） */
    private const val TEMP_SUFFIX = ".tmp"

    /** 滚动备份后缀 */
    private const val BACKUP_SUFFIX = ".bak"

    /**
     * 原子写入目标文件（生产默认目录同步实现）。
     *
     * 保留该重载是为了源码兼容：既有调用点（`DatabaseSession`）以位置参数传入 [writer]，
     * 而注入重载把 [DirectorySync] 放在 [writer] 之前，无法用单一函数同时兼容两种调用形态。
     */
    fun writeAtomic(
        targetFile: File,
        createBackup: Boolean = true,
        writer: (OutputStream) -> Unit
    ) {
        writeAtomic(targetFile, createBackup, DirectorySync.default, writer)
    }

    /**
     * 原子写入目标文件（可注入目录同步实现）。
     *
     * @param createBackup 是否在替换前保留一份滚动备份 .bak（默认 true，保持既有调用行为）。
     *   ISSUE-P2-11 (ZT-16)：该开关由会话层偏好下发，关闭时不再生成备份；同时因缺少备份兜底，
     *   降级分支对已存在的原文件会拒绝无保护覆盖（宁可失败也不损坏数据）。
     * @param directorySync 目录项 fsync 抽象（ISSUE-P3-13）。生产默认 [DirectorySync.default]；
     *   单测注入假实现以断言四条落盘路径均触达目录 fsync 钩子（Windows 宿主无法真实执行目录通道）。
     * @param writer 向临时文件写入内容的回调
     */
    fun writeAtomic(
        targetFile: File,
        createBackup: Boolean = true,
        directorySync: DirectorySync,
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
            writeAndSyncTmpFile(tmpFile, writer)

            // 步骤 3: 备份当前稳定版本（内部含目录 fsync 钩子①，按偏好可关闭）
            val backupAvailable =
                backupStableVersion(targetFile, bakFile, parentDir, createBackup, directorySync)

            // 步骤 4: 原子重命名替换原文件
            try {
                Files.move(
                    tmpFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                // 步骤 5: rename 后 fsync 父目录（钩子②），确保新目录项在崩溃/断电前已落盘
                syncDirectory(parentDir, directorySync)
            } catch (e: Exception) {
                fallbackReplace(tmpFile, targetFile, backupAvailable, e, directorySync)
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
     * 步骤 1 & 2：写入临时文件并强同步落盘（`flush` + `FileDescriptor.sync`）。
     *
     * 临时文件自身的目录项由后续 rename / copy 成功后的父目录 fsync 一并固化，
     * 此处只需保证文件内容已落盘。
     */
    private fun writeAndSyncTmpFile(tmpFile: File, writer: (OutputStream) -> Unit) {
        FileOutputStream(tmpFile).use { fos ->
            writer(fos)
            fos.flush()
            fos.fd.sync()
        }
    }

    /**
     * 步骤 3：滚动备份当前稳定版本，并对父目录执行 fsync 固化备份目录项（钩子①）。
     *
     * @return 备份是否可用（作为降级覆盖的安全兜底）。原文件不存在（首次写入）视为无需备份，
     *   返回 true；偏好关闭时不生成备份，返回 false（降级分支据此拒绝无保护覆盖）。
     */
    private fun backupStableVersion(
        targetFile: File,
        bakFile: File,
        parentDir: File,
        createBackup: Boolean,
        directorySync: DirectorySync
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
            // ISSUE-P2-05：备份目录项变更同样需要 fsync 固化（钩子①）
            syncDirectory(parentDir, directorySync)
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
     * 父目录，否则目录项变更在断电时可能尚未落盘；对应钩子③（标准 rename 成功后）
     * 与钩子④（copy 覆盖后）。
     *
     * @param backupAvailable [writeAtomic] 步骤 3 的 .bak 备份是否成功（原文件不存在视为 true）
     * @param cause 触发降级的原子 move 异常
     * @param directorySync 目录项 fsync 抽象（ISSUE-P3-13），默认生产实现以保持既有调用点源码兼容
     */
    internal fun fallbackReplace(
        tmpFile: File,
        targetFile: File,
        backupAvailable: Boolean,
        cause: Exception,
        directorySync: DirectorySync = DirectorySync.default
    ) {
        val parentDir = targetFile.parentFile ?: tmpFile.parentFile ?: File(".")
        if (tmpFile.renameTo(targetFile)) {
            // ISSUE-P2-05：rename 成功后 fsync 父目录，固化被替换的目录项（钩子③）
            syncDirectory(parentDir, directorySync)
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
        // ISSUE-P2-05：copy 覆盖后 fsync 父目录，固化目录项变更（钩子④）
        syncDirectory(parentDir, directorySync)
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
     *
     * ISSUE-P3-26：unlink 与 rename/copy 同属「目录项变更」，删除成功后必须经
     * [directorySync] 固化父目录目录项（钩子⑤）——否则断电/崩溃后该删除可能尚未落盘，
     * 已清理的旧密文快照会重新出现。备份本就不存在时没有任何目录项变更，
     * 不得触达钩子（诚实性：不伪报已同步）；删除失败同样无目录项变更，亦不触达。
     *
     * @param directorySync 目录项 fsync 抽象（ISSUE-P3-26）。生产默认 [DirectorySync.default]，
     *   保留默认值使既有调用点（`DatabaseSession.deleteBackupQuietly`）源码兼容；
     *   单测注入假实现以断言「删除路径确实触达目录同步钩子」。
     * @return 备份是否已确认不存在（已删除或本就不存在均为 true）；删除失败返回 false 且仅告警。
     */
    internal fun deleteBackup(
        targetFile: File,
        directorySync: DirectorySync = DirectorySync.default
    ): Boolean {
        val bakFile = backupFileFor(targetFile)
        if (!bakFile.exists()) {
            return true
        }
        if (!bakFile.delete()) {
            logger.log(Level.WARNING, "删除滚动备份失败（仅告警，不阻断主流程）: ${bakFile.absolutePath}")
            return false
        }
        // ISSUE-P3-26：删除成功后 fsync 父目录，固化 unlink 造成的目录项变更（钩子⑤）。
        // 降级（Windows 无目录通道）由实现返回 DEGRADED，本层不读取结果、更不据此失败。
        syncDirectory(bakFile.parentFile ?: File("."), directorySync)
        return true
    }

    /**
     * ISSUE-P2-05 / ISSUE-P3-13：经注入的 [DirectorySync] 固化目录项变更。
     *
     * 降级语义由实现自身决定并以告警日志记录（见 [PosixDirectorySync]）：平台/文件系统不支持时
     * 返回 `DEGRADED`，本层不读取结果、更不据此失败——保持 Windows 上「降级不阻断」语义不变。
     * 此处仅保留最后一道防御：实现若违反「禁止抛异常」契约，也只记告警，不影响已成功的写盘结果。
     */
    private fun syncDirectory(directory: File, directorySync: DirectorySync) {
        try {
            directorySync.sync(directory)
        } catch (e: Exception) {
            logger.log(
                Level.WARNING,
                "目录项 fsync 调用异常（降级为告警，不阻断写盘）: ${directory.absolutePath}",
                e
            )
        }
    }
}
