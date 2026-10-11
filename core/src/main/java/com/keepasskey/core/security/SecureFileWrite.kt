package com.keepasskey.core.security

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * 「仅属主权限 + 唯一临时名 + 原子替换」**落盘写入原语**（`ISSUE-P3-582`，自 `sync` 的
 * `SyncCacheFiles` 下沉为跨模块共享的单一真相源）。
 *
 * ## 为何下沉到 `core`
 * `sync` 模块自 `ISSUE-P1-07` / `ISSUE-P3-202` 起自声明「所有落盘面显式 0600（目录 0700）、
 * 不依赖 umask」，但该口径此前只活在 sync 内部（原 `SyncCacheFiles` 为 `internal`，`app` 不可见）。
 * 于是 `app` 模块的四条落盘点（云端导入 / 云端副本另存 / 库文件副本 / 密钥文件收编副本）
 * 各自裸 `FileOutputStream` + 固定临时名，与自声明口径漂移（`ISSUE-P3-582`）。把原语下沉到
 * `core`（`app` / `sync` / `database` 全员可见，不破坏 `app → sync → core` 单向依赖）后，
 * 权限位与唯一临时名口径**全仓单一**：不再有第二份拷贝可漂移。`sync` 侧 `SyncCacheFiles`
 * 退化为对本原语的薄委托（`fileFor` / 通配清理 / 有界重试删除等缓存域逻辑仍留 sync）。
 *
 * ## 契约
 * - **权限**：优先 POSIX 精确置位（文件 0600 / 目录 0700）；非 POSIX 文件系统（Windows 开发机 /
 *   FAT 分区）降级为 `java.io` 语义尽力而为（见 [restrictToOwnerOnly]）。**绝不依赖 umask**。
 * - **唯一临时名**：[uniqueTmpFor] 产出 `<目标名>.<UUID><后缀>`，消除「两次同名写入交错写同一 tmp」
 *   与「失败清理删掉并发者正在写的 tmp」两类本地完整性瑕疵。
 * - **调用方责任**：失败 / 异常清理只 `delete()` **自己的** tmp（唯一名使其精确），绝不删他人。
 */
object SecureFileWrite {

    /** 通用临时件后缀 `.tmp`；缓存 / 库文件等域可传自域后缀（如 `.importing` / `.backup` / `.kfc`）。 */
    const val SUFFIX_TMP = ".tmp"

    private val FILE_OWNER_ONLY = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    )

    private val DIRECTORY_OWNER_ONLY = FILE_OWNER_ONLY + PosixFilePermission.OWNER_EXECUTE

    /**
     * 生成目标文件的**唯一**临时路径（与目标同目录，含随机 UUID 成分）。
     * 固定名 tmp 在并发写同一目标时会互相覆盖，造成 A 的 rename 交付 B 的内容（交叉污染）；
     * 唯一名作为并发防御纵深兜底。与目标同目录保证 rename 落在同一文件系统上（原子替换前提）。
     */
    fun uniqueTmpFor(targetFile: File, suffix: String = SUFFIX_TMP): File =
        File(targetFile.parentFile, "${targetFile.name}.${UUID.randomUUID()}$suffix")

    /**
     * 打开 [tmpFile] 的输出流，并在**写入第一字节之前**把该文件收敛为仅属主（0600）——
     * 密文 / 密钥材料自落盘第一刻起即不可被同 UID 之外的读取面看见，绝不依赖系统默认 umask。
     * 调用方负责 `flush()` / `fd.sync()` 与 `use {}` 关闭。
     */
    fun openOwnerOnlyOutput(tmpFile: File): FileOutputStream =
        FileOutputStream(tmpFile).also { restrictToOwnerOnly(tmpFile, isDirectory = false) }

    /** 写入 [bytes] 到 [tmpFile] 并 fsync 落盘 + 收敛为 0600（不含 rename 交付步骤，供多文件合并复用）。 */
    fun writeSynced(tmpFile: File, bytes: ByteArray) {
        FileOutputStream(tmpFile).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        restrictToOwnerOnly(tmpFile, isDirectory = false)
    }

    /**
     * 原子替换移动：POSIX rename 对已存在目标执行原子替换（无窗口、无半写状态）；
     * `renameTo` 失败的平台（如桌面 Windows 调试环境）回退 `Files.move`，优先 `ATOMIC_MOVE`，
     * 不支持时降级 `REPLACE_EXISTING`，**绝不做非原子 `copyTo`**。成功后把目标收敛为 0600。
     *
     * @return 恒为 `true`（交付成功）；`renameTo` 与两条 `Files.move` 路径均失败时按原语义
     *   抛 `IOException`，由调用方按自身失败分支处置（不在此吞掉，保持与 sync 侧一致的上抛口径）
     */
    fun moveAtomically(tmpFile: File, targetFile: File): Boolean {
        if (tmpFile.renameTo(targetFile)) {
            restrictToOwnerOnly(targetFile, isDirectory = false)
            return true
        }
        try {
            Files.move(
                tmpFile.toPath(), targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwnerOnly(targetFile, isDirectory = false)
        return true
    }

    /**
     * 收敛文件 / 目录权限为「仅属主可访问」（`ISSUE-P1-07` 验收标准 2）。
     *
     * 优先走 POSIX 精确置位（文件 0600 / 目录 0700）；非 POSIX 文件系统（Windows 开发机 /
     * FAT 分区）降级为 `java.io` 语义尽力而为（仅属主可读写，目录额外需属主可执行）。
     */
    fun restrictToOwnerOnly(file: File, isDirectory: Boolean) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                if (isDirectory) DIRECTORY_OWNER_ONLY else FILE_OWNER_ONLY
            )
        }.onFailure {
            // 降级路径：仅属主可读可写；目录额外需要属主可执行（进入权限）
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (isDirectory) file.setExecutable(true, true) else file.setExecutable(false, false)
        }
    }
}
