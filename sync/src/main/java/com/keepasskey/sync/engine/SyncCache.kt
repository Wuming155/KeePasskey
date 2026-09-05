package com.keepasskey.sync.engine

import com.keepasskey.sync.model.cleanEtag
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
 */
class SyncCache(private val cacheDir: File) {

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
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
     */
    fun readCache(remotePath: String): ByteArray? {
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
        val tmpFile = File(cacheDir, "${cacheFile.name}$SUFFIX_TMP")

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
        val tmpFile = File(cacheDir, "${baseFile.name}$SUFFIX_TMP")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(data)
            fos.flush()
            fos.fd.sync()
        }
        moveAtomically(tmpFile, baseFile)
    }

    /**
     * 更新基准版本 `<hash>.baseversion` 与元数据 `<hash>.meta`。
     */
    fun updateBase(remotePath: String, baseVersion: String, etag: String? = null) {
        val baseFile = getFile(remotePath, SUFFIX_BASE_VERSION)
        writeStringSafely(baseFile, baseVersion.trim())

        val oldState = getState(remotePath)
        val cleanEtagStr = cleanEtag(etag ?: oldState?.etag)
        val metaFile = getFile(remotePath, SUFFIX_META)
        val metaContent = buildString {
            append(KEY_REMOTE_PATH).append('=').append(remotePath).append('\n')
            append(KEY_ETAG).append('=').append(cleanEtagStr).append('\n')
            append(KEY_LAST_SYNC_MILLIS).append('=').append(System.currentTimeMillis()).append('\n')
        }
        writeStringSafely(metaFile, metaContent)
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
            "$SUFFIX_CACHE$SUFFIX_TMP"
        ).forEach { suffix ->
            val file = getFile(remotePath, suffix)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * 原子替换移动：POSIX rename 对已存在目标执行原子替换（无窗口、无半写状态）；
     * renameTo 失败的平台（如桌面 Windows 调试环境）回退 Files.move，
     * 优先 ATOMIC_MOVE，不支持时降级 REPLACE_EXISTING，绝不做非原子 copyTo。
     */
    private fun moveAtomically(tmpFile: File, targetFile: File) {
        if (tmpFile.renameTo(targetFile)) return
        try {
            Files.move(
                tmpFile.toPath(), targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun getFile(remotePath: String, suffix: String): File {
        val key = sha256Hex(remotePath.toByteArray(Charsets.UTF_8))
        return File(cacheDir, "$key$suffix")
    }

    private fun writeStringSafely(targetFile: File, content: String) {
        val tmpFile = File(cacheDir, "${targetFile.name}$SUFFIX_TMP")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        moveAtomically(tmpFile, targetFile)
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

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}
