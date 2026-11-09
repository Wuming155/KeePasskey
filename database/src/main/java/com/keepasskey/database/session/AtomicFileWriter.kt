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
import kotlinx.coroutines.CancellationException

/**
 * 工业级原子化安全写盘引擎。
 * 遵循数据完整性铁律：
 * 1. 写入临时文件 (.kdbx.tmp)
 * 2. 强同步落盘 (flush + FileDescriptor.sync)
 * 3. 暂存当前稳定版本为 (.kdbx.bak.tmp) 并 fsync 数据块（可按会话偏好关闭）
 * 4. 原子重命名替换原文件（失败时拒绝非原子覆盖，见下）
 * 5. 目录项 fsync（ISSUE-P2-05：替换成功后固化父目录目录项，崩溃不回退）
 * 6. 主文件替换成功后才把暂存备份原子提交为 .kdbx.bak
 *
 * 替换失败口径（ENG-20261028-17）：原子 move 与标准 rename 都不可用时**拒绝**以非原子
 * copy 覆盖主文件——copy 中断或磁盘满会留下不完整主文件，事后「可自备份恢复」不能抵消
 * 用户已在使用的坏文件。拒绝时抛出可处理的 [IOException]（上层映射为保存失败），
 * 旧主文件与既有 `.bak` 均保持原样，临时文件与暂存备份由 [writeAtomicInternal] 统一清理。
 *
 * 降级路径安全铁律：任何情况下严禁在替换成功之前无保护地删除原文件——
 * 一旦 delete 成功而后续 rename 失败，原文件将彻底丢失且无法恢复。
 *
 * ISSUE-P3-13：目录 fsync 经 [DirectorySync] 抽象注入，本类不再写死平台调用。
 * 四条目录项变更路径均触达该钩子：① 暂存备份提交为 .bak 后、② 主路径原子 move 后、
 * ③ 降级标准 rename 成功后、④ 删除滚动备份 `.bak` 后（ISSUE-P3-26：unlink 同属目录项变更，
 * 不 fsync 则断电后「已删除的备份」可能随目录项回滚而复活，旧口令可解的密文快照重新出现）；
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
     *   ISSUE-P2-11 (ZT-16)：该开关由会话层偏好下发，关闭时不再生成备份。备份只是恢复材料，
     *   不参与替换决策：任何情况下都不会为「缺少备份」而改用非原子 copy（ENG-20261028-17）。
     * @param directorySync 目录项 fsync 抽象（ISSUE-P3-13）。生产默认 [DirectorySync.default]；
     *   单测注入假实现以断言各条目录项变更路径均触达目录 fsync 钩子（Windows 宿主无法真实执行目录通道）。
     * @param writer 向临时文件写入内容的回调
     */
    fun writeAtomic(
        targetFile: File,
        createBackup: Boolean = true,
        directorySync: DirectorySync,
        writer: (OutputStream) -> Unit
    ) = writeAtomicInternal(targetFile, createBackup, directorySync, null, writer)

    /** 凭据轮换专用证据：替换完成即回报，不等 writer 返回或切回调用调度器。 */
    internal fun writeAtomicTracked(
        targetFile: File,
        createBackup: Boolean,
        receipt: SessionWriteReceipt,
        writer: (OutputStream) -> Unit
    ) = writeAtomicInternal(targetFile, createBackup, DirectorySync.default, receipt, writer)

    /** 凭据轮换专用证据（可注入目录同步实现，供测试断言与故障注入）。 */
    internal fun writeAtomicTracked(
        targetFile: File,
        createBackup: Boolean,
        receipt: SessionWriteReceipt,
        directorySync: DirectorySync,
        writer: (OutputStream) -> Unit
    ) = writeAtomicInternal(targetFile, createBackup, directorySync, receipt, writer)

    private fun writeAtomicInternal(
        targetFile: File,
        createBackup: Boolean,
        directorySync: DirectorySync,
        receipt: SessionWriteReceipt?,
        writer: (OutputStream) -> Unit
    ) {
        receipt?.writing()
        val parentDir = targetFile.parentFile ?: File(".")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tmpFile = File(parentDir, targetFile.name + TEMP_SUFFIX)
        val bakFile = backupFileFor(targetFile)
        val stagedBackupFile = File(parentDir, bakFile.name + TEMP_SUFFIX)

        // 确保临时文件不存在
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        var committed = false
        try {
            // 步骤 1 & 2: 写入临时文件并强同步落盘
            writeAndSyncTmpFile(tmpFile, writer)

            // 步骤 3: 暂存当前稳定版本为 .bak.tmp（尚未提交，替换失败时丢弃）
            val stagedBackup = stageStableVersion(targetFile, stagedBackupFile, createBackup)

            // 步骤 4: 原子重命名替换原文件；替换失败时旧主文件与旧 .bak 均原样保留
            try {
                var moveSucceeded = false
                try {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    moveSucceeded = true
                } catch (e: Exception) {
                    fallbackReplace(tmpFile, targetFile, e, directorySync, receipt)
                    committed = true
                }
                if (moveSucceeded) {
                    committed = true
                    receipt?.committed()
                    // 步骤 5: 原子 move 成功后 fsync 父目录（钩子②），确保新目录项在崩溃/断电前已落盘
                    syncDirectory(parentDir, directorySync)
                }
            } catch (t: Throwable) {
                // 替换未成立：丢弃暂存备份，既有 .bak 保持替换前内容
                if (stagedBackup != null) {
                    deleteQuietly(stagedBackup)
                }
                throw t
            }

            // 步骤 6: 主文件已替换成功，才把暂存备份提交为正式 .bak（钩子①）
            commitStableVersion(stagedBackup, bakFile, parentDir, directorySync)
        } catch (t: Throwable) {
            if (!committed) {
                receipt?.notCommitted()
            }
            // 异常退出时清理临时垃圾，绝不损坏原文件
            deleteQuietly(tmpFile)
            throw t
        }
    }

    /**
     * 步骤 1 & 2：写入临时文件并强同步落盘（`flush` + `FileDescriptor.sync`）。
     *
     * 临时文件自身的目录项由后续原子替换或降级 rename 成功后的父目录 fsync 一并固化，
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
     * 步骤 3：把当前稳定版本暂存为 `.bak.tmp`（尚未提交为正式 `.bak`）。
     *
     * 备份是用户可见的恢复材料（[SessionFileWriter.restoreFromRollingBackup] 会把 `.bak`
     * 字节装回主文件），因此不得用非原子 copy 直接覆盖正式 `.bak`：磁盘满或中断会在
     * `.bak` 留下前缀残件，恢复时反而把残件当成稳定版本装进主文件。这里先把稳定版本
     * 复制到 `.bak.tmp` 并 fsync 数据块，正式 `.bak` 只在主文件替换成功后才由
     * [commitStableVersion] 原子替换——替换失败时旧主文件与旧 `.bak` 都不被改动。
     *
     * @return 已就绪的暂存文件；无需备份（首次写入 / 偏好关闭）或暂存失败时返回 null，
     *   两种情况都不阻断主流程（主文件的原子替换不依赖备份）。
     */
    private fun stageStableVersion(
        targetFile: File,
        stagedBackupFile: File,
        createBackup: Boolean
    ): File? {
        if (!targetFile.exists()) {
            // 原文件不存在（首次写入），无稳定版本可备份
            return null
        }
        if (!createBackup) {
            // ISSUE-P2-11 (ZT-16)：会话关闭了保存前备份，本次不生成 .bak
            return null
        }
        return try {
            Files.copy(
                targetFile.toPath(),
                stagedBackupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            // ISSUE-P3-311 项 4：.bak **数据块** fsync——此前只固化目录项（钩子①），
            // 数据块本身未同步：delayed writeback 语义下断电可能留下空洞 / 零页 .bak，
            // 「可自备份恢复」的承诺落空。force(true) 一并固化数据与元数据。
            FileChannel.open(stagedBackupFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
            stagedBackupFile
        } catch (c: CancellationException) {
            deleteQuietly(stagedBackupFile)
            throw c
        } catch (t: Throwable) {
            // 备份尝试若因权限受阻，记录但不阻断主流程（原子 move 主路径不依赖备份）。
            // 日志仅含文件路径与异常原因，不含任何敏感内容。
            logger.log(
                Level.WARNING,
                "原子写盘备份原文件失败（不阻断主流程）: ${targetFile.absolutePath}",
                t
            )
            deleteQuietly(stagedBackupFile)
            null
        }
    }

    /**
     * 步骤 6：主文件已原子替换成功后，把暂存备份提交为正式 `.bak`，并固化目录项（钩子①）。
     *
     * 提交失败（权限、目标被占用等）保留原 `.bak`、清理暂存件并仅记告警：主文件此时已是
     * 新版本，备份缺失不是本次保存的失败条件，但绝不能让暂存残件留在目录里。
     */
    private fun commitStableVersion(
        stagedBackupFile: File?,
        bakFile: File,
        parentDir: File,
        directorySync: DirectorySync
    ) {
        if (stagedBackupFile == null) {
            return
        }
        try {
            try {
                Files.move(
                    stagedBackupFile.toPath(),
                    bakFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (moveEx: Exception) {
                // 个别平台/文件系统不支持原子替换时退回标准 rename；仍失败则保留旧 .bak
                if (!stagedBackupFile.renameTo(bakFile)) {
                    throw moveEx
                }
            }
            // ISSUE-P2-05：备份目录项变更同样需要 fsync 固化（钩子①）
            syncDirectory(parentDir, directorySync)
        } catch (c: CancellationException) {
            deleteQuietly(stagedBackupFile)
            throw c
        } catch (t: Throwable) {
            logger.log(
                Level.WARNING,
                "原子写盘提交滚动备份失败（主文件已保存，不阻断）: ${bakFile.absolutePath}",
                t
            )
            deleteQuietly(stagedBackupFile)
        }
    }

    /**
     * 原子 move 失败后的安全降级替换。
     *
     * 安全铁律：严禁在 rename 之前无保护地 [File.delete] 原文件——
     * 若 delete 成功而 rename 失败，原文件将彻底丢失。降级只允许一种替换手段：
     * 直接标准 rename（POSIX rename(2) 语义下可原子替换已存在目标，全程无需删除原文件）。
     *
     * ENG-20261028-17：rename 也失败时**拒绝**非原子 copy 覆盖并抛出可处理失败——
     * copy 期间断电、磁盘满或取消会留下不完整主文件，而「有 .bak 可恢复」只是事后的
     * 人工补救，不能抵消本次失败已经交付给用户一个坏文件。拒绝不产生任何目录项变更，
     * 旧主文件与既有 `.bak` 保持原样，临时文件由调用方清理。
     *
     * ISSUE-P2-05：本方法为崩溃安全敏感路径——renameTo 成功后必须 fsync 父目录，
     * 否则目录项变更在断电时可能尚未落盘；对应钩子③。
     *
     * @param cause 触发降级的原子 move 异常
     * @param directorySync 目录项 fsync 抽象（ISSUE-P3-13），默认生产实现以保持既有调用点源码兼容
     */
    internal fun fallbackReplace(
        tmpFile: File,
        targetFile: File,
        cause: Exception,
        directorySync: DirectorySync = DirectorySync.default,
        receipt: SessionWriteReceipt? = null
    ) {
        val parentDir = targetFile.parentFile ?: tmpFile.parentFile ?: File(".")
        if (tmpFile.renameTo(targetFile)) {
            receipt?.committed()
            // ISSUE-P2-05：rename 成功后 fsync 父目录，固化被替换的目录项（钩子③）
            syncDirectory(parentDir, directorySync)
            return
        }
        throw IOException(
            "无法原子替换 ${targetFile.name}：原子 move 与标准 rename 均失败，拒绝非原子覆盖（原文件与备份保持原样）",
            cause
        )
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
     * 同时清理上次异常/进程死亡遗留的暂存备份 `.bak.tmp`（同样可能是可被旧口令解开的
     * 密文快照），避免它在偏好关闭后继续驻留。
     *
     * ISSUE-P3-26：unlink 与 rename 同属「目录项变更」，删除成功后必须经
     * [directorySync] 固化父目录目录项（钩子④）——否则断电/崩溃后该删除可能尚未落盘，
     * 已清理的旧密文快照会重新出现。两个文件都不存在时没有任何目录项变更，
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
        val parentDir = bakFile.parentFile ?: File(".")
        var removed = false
        var failed = false
        for (file in listOf(bakFile, File(parentDir, bakFile.name + TEMP_SUFFIX))) {
            if (!file.exists()) {
                continue
            }
            if (file.delete()) {
                removed = true
            } else {
                failed = true
                logger.log(Level.WARNING, "删除滚动备份失败（仅告警，不阻断主流程）: ${file.absolutePath}")
            }
        }
        if (removed) {
            // ISSUE-P3-26：删除成功后 fsync 父目录，固化 unlink 造成的目录项变更（钩子④）。
            // 降级（Windows 无目录通道）由实现返回 DEGRADED，本层不读取结果、更不据此失败。
            syncDirectory(parentDir, directorySync)
        }
        return !failed
    }

    /** 清理临时件；失败仅告警（如只读目录），不覆盖原始异常。 */
    private fun deleteQuietly(file: File) {
        if (file.exists() && !file.delete()) {
            logger.log(Level.WARNING, "原子写盘临时文件清理失败: ${file.absolutePath}")
        }
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
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logger.log(
                Level.WARNING,
                "目录项 fsync 调用异常（降级为告警，不阻断写盘）: ${directory.absolutePath}",
                t
            )
        }
    }
}
