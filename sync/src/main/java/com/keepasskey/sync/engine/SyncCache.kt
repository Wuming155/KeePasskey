package com.keepasskey.sync.engine

import com.keepasskey.sync.model.cleanEtag
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID

/**
 * 缓存快照元数据状态。
 */
data class SyncCacheState(
    val remotePath: String,
    val localVersion: String?,
    val baseVersion: String?,
    val etag: String?,
    val lastSyncMillis: Long
)

/**
 * 纯字节级三哈希本地缓存存储。
 *
 * 磁盘布局（以 SHA-256(remotePath) 规范键命名）：
 * - `<hash>.cache`：数据库二进制文件内容（安全写入：.tmp -> flush/sync -> rename）
 * - `<hash>.version`：本地版本号（内容 SHA-256 十六进制）
 * - `<hash>.baseversion`：基准版本号（最后确认与云端一致时的 SHA-256 十六进制）
 * - `<hash>.basecache`：基准内容快照（最后确认与云端一致时的完整字节）
 * - `<hash>.meta`：简易 key=value 行文本元数据（remotePath, etag, lastSyncMillis）
 *
 * ISSUE-P1-07 数据生命周期治理：
 * 1. 全部落盘文件（含临时文件）显式收敛为「仅属主可读写」（0600，目录 0700）——
 *    cacheDir 虽位于应用私有目录，但绝不依赖系统默认 umask；
 * 2. 提供 [clear]（单远端路径）与 [clearAll]（全量）两级销毁入口，
 *    由会话锁定 / 同步凭据销毁时机驱动，杜绝密文快照无限期驻留。
 */
open class SyncCache(private val cacheDir: File) {

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        restrictToOwnerOnly(cacheDir, isDirectory = true)
    }

    /**
     * 判断指定远端路径是否已在本地建立缓存。
     */
    fun isCached(remotePath: String): Boolean {
        val cacheFile = getFile(remotePath, SUFFIX_CACHE)
        return cacheFile.exists() && cacheFile.length() > 0
    }

    /**
     * 读取本地缓存的二进制内容。
     * open 仅供单元测试子类化注入「isCached 与读取之间状态漂移」的模拟场景。
     */
    open fun readCache(remotePath: String): ByteArray? {
        val file = getFile(remotePath, SUFFIX_CACHE)
        return if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            null
        }
    }

    /**
     * 判断本地缓存相对于基准版本是否存在未提交修改。
     * 即 localVersion != baseVersion。
     */
    fun hasLocalChanges(remotePath: String): Boolean {
        val versionFile = getFile(remotePath, SUFFIX_VERSION)
        if (!versionFile.exists()) return false

        val baseVersionFile = getFile(remotePath, SUFFIX_BASE_VERSION)
        if (!baseVersionFile.exists()) return true

        val localVer = versionFile.readText().trim()
        val baseVer = baseVersionFile.readText().trim()
        return localVer != baseVer
    }

    /**
     * 原子写入本地缓存文件。
     *
     * 遵循 engineering-rules.md 安全写盘铁律：
     * 写入 .tmp 临时文件 -> flush() -> fd.sync() -> 原子 rename 覆盖原文件。
     * 注意：rename 直接原子替换已存在目标（POSIX 语义），绝不可先 delete 目标——
     * 先删后改会在"目标已删、rename 未执行"的崩溃窗口留下缓存缺失，
     * 进而被误判为未缓存而触发全量下载，丢失本地未同步修改。
     *
     * @param updateVersion 是否同步刷新 `<hash>.version`
     * @return 写入内容的 SHA-256 十六进制小写摘要
     */
    fun writeCache(remotePath: String, data: ByteArray, updateVersion: Boolean = true): String {
        val cacheFile = getFile(remotePath, SUFFIX_CACHE)
        val tmpFile = tmpFileFor(cacheFile)

        FileOutputStream(tmpFile).use { fos ->
            fos.write(data)
            fos.flush()
            fos.fd.sync()
        }

        moveAtomically(tmpFile, cacheFile)

        val sha256 = sha256Hex(data)
        if (updateVersion) {
            val versionFile = getFile(remotePath, SUFFIX_VERSION)
            writeStringSafely(versionFile, sha256)
        }
        return sha256
    }

    /**
     * 读取基准内容快照（最后确认与云端一致时的完整字节）。
     * 三方合并需要 base 的完整内容而非仅哈希；本地缓存会被工作副本覆盖，
     * base 内容必须独立落盘，否则冲突会话中断后 base 会被本地修改版污染，
     * 后续合并将退化为"远端全胜"的静默数据丢失。
     */
    fun readBaseContent(remotePath: String): ByteArray? {
        val file = getFile(remotePath, SUFFIX_BASE_CACHE)
        return if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            null
        }
    }

    /**
     * 持久化基准内容快照。仅在字节确认与远端一致时由 SyncEngine 调用。
     */
    fun writeBaseContent(remotePath: String, data: ByteArray) {
        val baseFile = getFile(remotePath, SUFFIX_BASE_CACHE)
        val tmpFile = tmpFileFor(baseFile)
        FileOutputStream(tmpFile).use { fos ->
            fos.write(data)
            fos.flush()
            fos.fd.sync()
        }
        moveAtomically(tmpFile, baseFile)
    }

    /**
     * 更新基准版本 `<hash>.baseversion` 与元数据 `<hash>.meta`（TASK-37 整改：两文件合并原子写）。
     *
     * 此前两文件各自独立走「tmp -> fsync -> rename」，两写之间存在崩溃窗口：
     * baseversion 已更新而 meta（etag/lastSyncMillis）仍是旧值，下一轮同步会以
     * 过期 etag 发起 If-Match，制造本可避免的假冲突。
     * 整改后：先把两份内容分别写入唯一 tmp 并 fsync（慢路径），随后背靠背执行两次
     * 原子 rename（快路径，微秒级）——把不一致窗口从「两次完整写盘」压缩到
     * 「两个原子 rename 之间」，且崩溃残留 tmp 由 [deleteOrphanTmpFiles] 通配清理。
     * （跨多文件的完全原子性受 POSIX 限制不存在，此为工程上可达的最小窗口。）
     */
    fun updateBase(remotePath: String, baseVersion: String, etag: String? = null) {
        val baseFile = getFile(remotePath, SUFFIX_BASE_VERSION)
        val oldState = getState(remotePath)
        val cleanEtagStr = cleanEtag(etag ?: oldState?.etag)
        val metaFile = getFile(remotePath, SUFFIX_META)
        val metaContent = buildString {
            append(KEY_REMOTE_PATH).append('=').append(remotePath).append('\n')
            append(KEY_ETAG).append('=').append(cleanEtagStr).append('\n')
            append(KEY_LAST_SYNC_MILLIS).append('=').append(System.currentTimeMillis()).append('\n')
        }

        val baseTmp = tmpFileFor(baseFile)
        val metaTmp = tmpFileFor(metaFile)
        try {
            writeTmpSynced(baseTmp, baseVersion.trim().toByteArray(Charsets.UTF_8))
            writeTmpSynced(metaTmp, metaContent.toByteArray(Charsets.UTF_8))
            moveAtomically(baseTmp, baseFile)
            moveAtomically(metaTmp, metaFile)
        } finally {
            // 任一步失败时清理未交付的 tmp（rename 成功后对应 tmp 已不存在，delete 幂等）
            baseTmp.delete()
            metaTmp.delete()
        }
    }

    /**
     * 获取缓存状态快照。
     */
    fun getState(remotePath: String): SyncCacheState? {
        val cacheFile = getFile(remotePath, SUFFIX_CACHE)
        if (!cacheFile.exists()) return null

        val versionFile = getFile(remotePath, SUFFIX_VERSION)
        val baseFile = getFile(remotePath, SUFFIX_BASE_VERSION)
        val metaFile = getFile(remotePath, SUFFIX_META)

        val localVer = if (versionFile.exists()) versionFile.readText().trim() else null
        val baseVer = if (baseFile.exists()) baseFile.readText().trim() else null

        var metaPath = remotePath
        var metaEtag: String? = null
        var lastSyncMillis = 0L

        if (metaFile.exists()) {
            metaFile.forEachLine { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex > 0) {
                    val key = line.substring(0, separatorIndex).trim()
                    val value = line.substring(separatorIndex + 1).trim()
                    when (key) {
                        KEY_REMOTE_PATH -> metaPath = value
                        KEY_ETAG -> metaEtag = cleanEtag(value)
                        KEY_LAST_SYNC_MILLIS -> lastSyncMillis = value.toLongOrNull() ?: 0L
                    }
                }
            }
        }

        return SyncCacheState(
            remotePath = metaPath,
            localVersion = localVer,
            baseVersion = baseVer,
            etag = metaEtag,
            lastSyncMillis = lastSyncMillis
        )
    }

    /**
     * 清理指定远程路径的所有本地缓存文件。
     */
    fun clear(remotePath: String) {
        listOf(
            SUFFIX_CACHE,
            SUFFIX_VERSION,
            SUFFIX_BASE_VERSION,
            SUFFIX_BASE_CACHE,
            SUFFIX_META,
            // ISSUE-P2-18：防回滚高水位状态随缓存一并销毁
            SyncRollbackGuard.SUFFIX_STATE,
            "$SUFFIX_CACHE$SUFFIX_TMP"
        ).forEach { suffix ->
            val file = getFile(remotePath, suffix)
            if (file.exists()) {
                file.delete()
            }
        }
        deleteOrphanTmpFiles(remotePath)
    }

    /**
     * 销毁全部远端路径的缓存（ISSUE-P1-07）。
     *
     * 会话锁定与同步凭据销毁时调用：缓存内是**完整 KDBX 密文快照**，锁定后若不清理，
     * 设备失窃即可被离线无限期爆破主密码——「锁定」必须在数据生命周期上真正闭环。
     * 仅清空目录内容（目录本身保留，[SyncCache] 构造期保证其存在）。
     *
     * @return 是否全部删除成功（存在删除失败项时返回 false，调用方可据此告警）
     */
    fun clearAll(): Boolean {
        val children = cacheDir.listFiles() ?: return true
        var allDeleted = true
        for (child in children) {
            val removed = if (child.isDirectory) child.deleteRecursively() else child.delete()
            allDeleted = allDeleted && removed
        }
        return allDeleted
    }

    /**
     * 生成唯一临时文件路径。
     * 固定名 tmp 在并发写同一 remotePath 时会互相覆盖，造成 A 的 rename 交付 B 的
     * 内容（交叉污染）；对齐 Wave 9 WebDAV uploadAtomic 临时名唯一化的同类整改语义。
     * 当前 SyncCoordinator 以 mutex 串行化同步周期，唯一名作为并发防御纵深兜底。
     */
    private fun tmpFileFor(targetFile: File): File =
        File(cacheDir, "${targetFile.name}.${UUID.randomUUID()}$SUFFIX_TMP")

    /**
     * 通配清理本 remotePath 的全部残留 tmp 文件。
     * tmp 名含随机成分后，固定名清单不再完备；以「缓存键前缀 + tmp 后缀」通配兜底，
     * 防止进程崩溃残留的 tmp 文件累积泄漏磁盘。
     */
    private fun deleteOrphanTmpFiles(remotePath: String) {
        val key = sha256Hex(remotePath.toByteArray(Charsets.UTF_8))
        cacheDir.listFiles { file -> file.name.startsWith(key) && file.name.endsWith(SUFFIX_TMP) }
            ?.forEach { it.delete() }
    }

    /**
     * 原子替换移动：POSIX rename 对已存在目标执行原子替换（无窗口、无半写状态）；
     * renameTo 失败的平台（如桌面 Windows 调试环境）回退 Files.move，
     * 优先 ATOMIC_MOVE，不支持时降级 REPLACE_EXISTING，绝不做非原子 copyTo。
     */
    private fun moveAtomically(tmpFile: File, targetFile: File) {
        if (tmpFile.renameTo(targetFile)) {
            restrictToOwnerOnly(targetFile, isDirectory = false)
            return
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
    }

    /**
     * 收敛文件权限为「仅属主可访问」（ISSUE-P1-07 验收标准 2）。
     *
     * 缓存文件承载完整 KDBX 密文快照，即便位于应用私有目录也必须显式降权：
     * 默认 umask 通常给出 0644，同 UID（`sharedUserId`、调试备份、root 场景）之外
     * 的读取面应被彻底关闭。优先走 POSIX 精确置位（0600 / 0700）；
     * 非 POSIX 文件系统（如 Windows 开发机 / FAT 分区）降级为 `java.io` 语义尽力而为。
     */
    private fun restrictToOwnerOnly(file: File, isDirectory: Boolean) {
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

    private fun getFile(remotePath: String, suffix: String): File {
        val key = sha256Hex(remotePath.toByteArray(Charsets.UTF_8))
        return File(cacheDir, "$key$suffix")
    }

    private fun writeStringSafely(targetFile: File, content: String) {
        val tmpFile = tmpFileFor(targetFile)
        try {
            writeTmpSynced(tmpFile, content.toByteArray(Charsets.UTF_8))
            moveAtomically(tmpFile, targetFile)
        } finally {
            tmpFile.delete()
        }
    }

    /** 写入 tmp 文件并 fsync 落盘（不含 rename 交付步骤，供多文件合并原子写复用） */
    private fun writeTmpSynced(tmpFile: File, bytes: ByteArray) {
        FileOutputStream(tmpFile).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        // 密文自落盘第一刻起即为仅属主可见，绝不在窗口期内以默认 umask 权限暴露
        restrictToOwnerOnly(tmpFile, isDirectory = false)
    }

    companion object {
        private const val SUFFIX_CACHE = ".cache"
        private const val SUFFIX_VERSION = ".version"
        private const val SUFFIX_BASE_VERSION = ".baseversion"
        private const val SUFFIX_BASE_CACHE = ".basecache"
        private const val SUFFIX_META = ".meta"
        private const val SUFFIX_TMP = ".tmp"

        private const val KEY_REMOTE_PATH = "remotePath"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_SYNC_MILLIS = "lastSyncMillis"

        /**
         * 同步缓存目录名（`context.cacheDir` 下的相对路径）。
         * 由 app 侧 [SyncCache] 使用方与缓存清理器共用，避免两处各写字面量而漂移。
         */
        const val CACHE_DIR_NAME = "sync"

        private val FILE_OWNER_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        )

        private val DIRECTORY_OWNER_ONLY = FILE_OWNER_ONLY + PosixFilePermission.OWNER_EXECUTE

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).toHexString()
        }
    }
}
