package com.keepasskey.sync.engine

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
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
 * 磁盘布局（以 SHA-256(规范键) 命名；`ISSUE-P2-291` 起规范键含库身份命名空间，见构造参数）：
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
 *
 * F-23 例外（必须遵守）：[SyncRollbackGuard] 的防回滚状态**不属本类管辖**——它已迁至
 * `filesDir/<SyncRollbackGuard.STATE_DIR_NAME>`，且 [clear] / [clearAll] 永不删除
 * [SyncRollbackGuard.SUFFIX_STATE] 命名的文件。本目录只放「锁库即可丢弃」的密文快照。
 *
 * ISSUE-P3-305：元数据 / 基准快照 / 清理面（`getState` / `updateBase` / `writeBaseContent` /
 * `hasLocalChanges` / `clear` / `clearAll` / `adoptLegacyKeysIfPresent`）下沉同包
 * [SyncCacheMaintenance]，本类保留字节内容缓存的 `open` 原语（它们是单测子类化的注入点）
 * 与公开 API 的转发入口；规范键派生仍以 [scopedKey] 为唯一实现。
 */
open class SyncCache(
    private val cacheDir: File,
    /**
     * `ISSUE-P2-291`：库身份命名空间。非空时规范键 = `SHA-256("vaultScope\nremotePath")`，
     * 使同一 `remotePath` 被不同密码库共用时，缓存 / 版本 / 基线 / ETag 元数据各自独立
     * （「换库即视为新配置」的物理承载）；空串保持旧键 `SHA-256(remotePath)`——
     * 附件二进制池（`FileBinaryStore`）与既有直构造调用方零影响。
     */
    private val vaultScope: String = ""
) {

    /** 文件层原语（唯一临时名 / fsync / 原子替换 / 权限收敛 / 有界重试删除） */
    private val files = SyncCacheFiles(cacheDir)

    /** 元数据 / 基准快照 / 清理面（ISSUE-P3-305 下沉；键派生共用 [scopedKey] 单一实现） */
    private val maintenance = SyncCacheMaintenance(files, cacheDir, ::scopedKey)

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        files.restrictToOwnerOnly(cacheDir, isDirectory = true)
    }

    /**
     * 判断指定远端路径是否已在本地建立缓存。
     *
     * `ISSUE-P3-301`：与 [readCache] / [writeCache] / [readBaseContent] 等 IO 原语同为 `open`
     * ——本方法是一次 `exists()` + `length()` 的 **stat 级系统调用**，不读内容、不 `fsync`
     * （`ISSUE-P2-277` §274 据此把它与四类重活分列），但它同样是文件系统 IO，
     * 其执行线程由 `SyncAssemblyOffMainThreadTest` 以探针形式锁定（判据需与四类同源可证）。
     */
    open fun isCached(remotePath: String): Boolean {
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
     *
     * ISSUE-P3-305：实现下沉 [SyncCacheMaintenance.hasLocalChanges]（逐行搬运）。
     */
    fun hasLocalChanges(remotePath: String): Boolean = maintenance.hasLocalChanges(remotePath)

    /**
     * 原子写入本地缓存文件。
     *
     * 遵循 engineering-rules.md 安全写盘铁律：
     * 写入 .tmp 临时文件 -> flush() -> fd.sync() -> 原子 rename 覆盖原文件。
     * 注意：rename 直接原子替换已存在目标（POSIX 语义），绝不可先 delete 目标——
     * 先删后改会在"目标已删、rename 未执行"的崩溃窗口留下缓存缺失，
     * 进而被误判为未缓存而触发全量下载，丢失本地未同步修改。
     *
     * ISSUE-P3-202：tmp 写入改走 [SyncCacheFiles.writeTmpSynced]——密文自**落盘第一字节起**
     * 即收敛为仅属主（0600），兑现本类 KDoc 自声明的不变量口径；原实现裸 `FileOutputStream`
     * 只在 rename 后收敛目标，写入窗口期依赖进程 umask（与自声明口径漂移，见该条目第三轮真机取证）。
     *
     * @param updateVersion 是否同步刷新 `<hash>.version`
     * @param precomputedDigest 调用方已算出的 `data` 摘要（须为 SHA-256 十六进制小写）；
     *   为空时按 `data` 现算。`ISSUE-P3-167`：接受远端内容时同一份字节的摘要会被多处使用
     *   （回滚裁决 / 缓存写入 / 高水位记录），由调用方一次算出并贯穿，避免对整库重复计算。
     * @return 写入内容的 SHA-256 十六进制小写摘要
     *
     * `open`（`ISSUE-P2-277` AC③）：仅供单元测试子类化，用于断言本方法的执行线程
     * （主线程零 IO 守卫）。生产无任何子类，语义与可见性实质不变——口径同 [readCache]。
     */
    open fun writeCache(
        remotePath: String,
        data: ByteArray,
        updateVersion: Boolean = true,
        precomputedDigest: String? = null
    ): String {
        val cacheFile = getFile(remotePath, SUFFIX_CACHE)
        val tmpFile = files.tmpFileFor(cacheFile)

        files.writeTmpSynced(tmpFile, data)
        files.moveAtomically(tmpFile, cacheFile)

        val sha256 = precomputedDigest ?: sha256Hex(data)
        if (updateVersion) {
            val versionFile = getFile(remotePath, SUFFIX_VERSION)
            files.writeStringSafely(versionFile, sha256)
        }
        return sha256
    }

    /**
     * 流式接收一份远端下载体（ISSUE-P3-206）。
     *
     * 在唯一 tmp 上打开输出流交给 [receive]（provider 流式契约：网络流 → 固定缓冲 → sink，
     * 「声明尺寸预检 + 累计封顶」由实现内 `SyncDownloadLimits.copyBounded` 强制），
     * 全程边读边写并同步累计 SHA-256 与字节量，完成后 fsync——**下载期不在堆上整份物化**，
     * 堆占用为常量（原 `Result<ByteArray>` 契约使接受路径峰值 ~2×S、chunked 可达 ~3×S）。
     *
     * 交付与裁决解耦：本方法**不交付**——调用方完成防回滚裁决（摘要即 [ReceivedRemote.digest]）
     * 后以 [ReceivedRemote.commit] 原子 rename 交付（并写 `.version`），或以
     * [ReceivedRemote.abort] 清除半成品；[receive] 抛出任何异常（超限 / 网络失败）时本方法
     * 自动清除 tmp 后原样上抛——**绝不遗留半成品文件**（AC①）。
     * tmp 在打开输出流的同一时刻即收敛为仅属主（ISSUE-P3-202 口径：第一字节前降权）。
     *
     * @return 接收回执（摘要 + 字节量 + 交付 / 弃置句柄）
     */
    suspend fun receiveRemote(
        remotePath: String,
        receive: suspend (OutputStream) -> Unit
    ): ReceivedRemote {
        val cacheFile = getFile(remotePath, SUFFIX_CACHE)
        val tmpFile = files.tmpFileFor(cacheFile)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(tmpFile).use { fos ->
                files.restrictToOwnerOnly(tmpFile, isDirectory = false)
                val counting = DigestOutputStream(fos, digest)
                receive(counting)
                fos.flush()
                fos.fd.sync()
            }
        } catch (t: Throwable) {
            // 超限 / 网络失败 / 任何异常：清除半成品后原样上抛（调用方无须也拿不到句柄）
            tmpFile.delete()
            throw t
        }
        return ReceivedRemote(
            digest = digest.digest().toHexString(),
            byteCount = tmpFile.length(),
            tmpFile = tmpFile,
            targetFile = cacheFile,
            versionFile = getFile(remotePath, SUFFIX_VERSION)
        )
    }

    /**
     * 流式接收回执（ISSUE-P3-206）。摘要与字节量在接收期同步累计（无需读回重算），
     * 交付 / 弃置二选一且各自幂等。
     */
    inner class ReceivedRemote internal constructor(
        /** 全量内容的 SHA-256 十六进制小写摘要（可直接供回滚裁决 / `.version` / 基线前移） */
        val digest: String,
        /** 实际接收的字节数 */
        val byteCount: Long,
        private val tmpFile: File,
        private val targetFile: File,
        private val versionFile: File
    ) {
        private var settled = false

        /**
         * 物化字节（唯一 ~1×S 堆物化点：结果契约 / 三方合并 / 基准快照需要 ByteArray 时调用）。
         * 不改变 tmp 状态（调用方随后 commit / abort）。
         */
        fun readBytes(): ByteArray = tmpFile.readBytes()

        /**
         * 防回滚裁决通过后原子交付：tmp → rename 覆盖 `<hash>.cache` + 写 `<hash>.version`
         * （摘要即版本内容，与 [writeCache] 的落盘序列一致）。幂等：重复调用为 no-op。
         */
        fun commit() {
            if (settled) return
            settled = true
            files.moveAtomically(tmpFile, targetFile)
            files.writeStringSafely(versionFile, digest)
        }

        /** 弃置半成品（重放拒绝 / 冲突路径用后即弃）。幂等：交付后或重复调用均为 no-op。 */
        fun abort() {
            if (settled) return
            settled = true
            tmpFile.delete()
        }
    }

    /**
     * 以**流式**方式写入缓存内容（ISSUE-P2-24：大附件落盘不整份物化）。
     *
     * 落盘路径与权限收敛同 [writeCache]（.tmp → flush/fsync → 原子 rename，0600），
     * 区别只在内容来源是 [input] + [size]，全程以固定缓冲搬运。
     *
     * @return 写入内容的 SHA-256 十六进制小写摘要
     * @throws EOFException 输入流实际字节不足 [size]
     */
    open fun writeCacheStreaming(remotePath: String, input: InputStream, size: Long): String {
        val cacheFile = getFile(remotePath, SUFFIX_CACHE)
        val tmpFile = files.tmpFileFor(cacheFile)
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(tmpFile).use { fos ->
            // ISSUE-P3-202：先收敛再写——密文自落盘第一字节起即仅属主可见（原实现写完全量后才收敛）
            files.restrictToOwnerOnly(tmpFile, isDirectory = false)
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var remaining = size
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) throw EOFException("输入流数据不足：期望 $size 字节，尚缺 $remaining")
                fos.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                remaining -= read
            }
            fos.flush()
            fos.fd.sync()
        }
        files.restrictToOwnerOnly(tmpFile, isDirectory = false)
        files.moveAtomically(tmpFile, cacheFile)

        val sha256 = digest.digest().toHexString()
        files.writeStringSafely(getFile(remotePath, SUFFIX_VERSION), sha256)
        return sha256
    }

    /** 以流式打开缓存内容（不存在返回 null，ISSUE-P2-24）。 */
    open fun openCacheStream(remotePath: String): InputStream? {
        val file = getFile(remotePath, SUFFIX_CACHE)
        return if (file.exists() && file.isFile) FileInputStream(file) else null
    }

    /** 缓存内容字节数（不存在返回 0，ISSUE-P2-24）。 */
    open fun cacheSize(remotePath: String): Long {
        val file = getFile(remotePath, SUFFIX_CACHE)
        return if (file.exists() && file.isFile) file.length() else 0L
    }

    /**
     * 读取基准内容快照（最后确认与云端一致时的完整字节）。
     * 三方合并需要 base 的完整内容而非仅哈希；本地缓存会被工作副本覆盖，
     * base 内容必须独立落盘，否则冲突会话中断后 base 会被本地修改版污染，
     * 后续合并将退化为"远端全胜"的静默数据丢失。
     *
     * `open`（`ISSUE-P2-277` AC③）：仅供单元测试子类化，用于断言本方法的执行线程
     * （主线程零 IO 守卫）。生产无任何子类，语义与可见性实质不变——口径同 [readCache]。
     */
    open fun readBaseContent(remotePath: String): ByteArray? {
        val file = getFile(remotePath, SUFFIX_BASE_CACHE)
        return if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            null
        }
    }

    /**
     * 持久化基准内容快照。仅在字节确认与远端一致时由 SyncEngine 调用。
     *
     * ISSUE-P3-305：实现下沉 [SyncCacheMaintenance.writeBaseContent]（逐行搬运，
     * 含 ISSUE-P3-202 的 tmp 首字节降权口径）。
     */
    fun writeBaseContent(remotePath: String, data: ByteArray) =
        maintenance.writeBaseContent(remotePath, data)

    /**
     * 更新基准版本 `<hash>.baseversion` 与元数据 `<hash>.meta`（TASK-37 整改：两文件合并原子写）。
     *
     * ISSUE-P3-305：实现下沉 [SyncCacheMaintenance.updateBase]（逐行搬运，含崩溃窗口分析）。
     */
    fun updateBase(remotePath: String, baseVersion: String, etag: String? = null) =
        maintenance.updateBase(remotePath, baseVersion, etag)

    /**
     * 获取缓存状态快照。
     *
     * ISSUE-P3-305：实现下沉 [SyncCacheMaintenance.getState]（逐行搬运）。
     */
    fun getState(remotePath: String): SyncCacheState? = maintenance.getState(remotePath)

    /**
     * 清理指定远程路径的所有本地缓存文件。
     *
     * ISSUE-P3-305：实现下沉 [SyncCacheMaintenance.clear]（逐行搬运，F-23 例外不变量随实现一并迁移）。
     */
    fun clear(remotePath: String) = maintenance.clear(remotePath)

    /**
     * 销毁全部远端路径的缓存（ISSUE-P1-07）。
     *
     * ISSUE-P3-305：实现下沉 [SyncCacheMaintenance.clearAll]（逐行搬运）；F-23 例外
     * （防回滚状态文件保留）与「删除失败即返回 false」契约不变。
     */
    fun clearAll(): Boolean = maintenance.clearAll()

    /**
     * 删除单个缓存子项（ISSUE-P2-82）：委托文件层原语 [SyncCacheFiles.deleteChild]。
     *
     * 幂等契约与瞬时删除失败的有界重试（ISSUE-P3-108）见被委托函数 KDoc；
     * 此处保留 internal 入口，供回归用例直接驱动「目录清单 + 逐个删除」的清理面。
     */
    internal fun deleteCacheChild(child: File): Boolean = files.deleteChild(child)

    /** 定位缓存目录内的规范键文件（键名与后缀拼接口径见 [SyncCacheFiles.fileFor]） */
    private fun getFile(remotePath: String, suffix: String): File = files.fileFor(scopedKey(remotePath), suffix)

    /** `ISSUE-P2-291`：库身份命名空间参与后的规范键输入（空命名空间退化为裸 `remotePath`）。 */
    private fun scopedKey(remotePath: String): String =
        if (vaultScope.isEmpty()) remotePath else "$vaultScope\n$remotePath"

    /**
     * `ISSUE-P2-291`：库身份键控升级的**一次性迁移**（见 [SyncCacheMaintenance.adoptLegacyKeysIfPresent]，
     * ISSUE-P3-305 起实现在该类，本入口只作转发）。
     */
    fun adoptLegacyKeysIfPresent(remotePath: String) = maintenance.adoptLegacyKeysIfPresent(remotePath)

    companion object {
        internal const val SUFFIX_CACHE = ".cache"
        internal const val SUFFIX_VERSION = ".version"
        internal const val SUFFIX_BASE_CACHE = ".basecache"

        /** 流式搬运缓冲（64 KiB，兼顾吞吐与内存占用）。 */
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        /**
         * 同步缓存目录名（`context.cacheDir` 下的相对路径）。
         * 由 app 侧 [SyncCache] 使用方与缓存清理器共用，避免两处各写字面量而漂移。
         */
        const val CACHE_DIR_NAME = "sync"

        /**
         * 判断文件名是否属**跨会话防回滚安全状态**（[SyncRollbackGuard.SUFFIX_STATE]）。
         *
         * 由 [SyncCache.clearAll] 与 app 侧缓存销毁器共用：缓存清理的后缀知识保留在 `sync` 模块，
         * 调用方无需各自枚举，也避免两处字面量漂移（F-23 整改的同源约束）。
         */
        fun isRollbackStateFileName(name: String): Boolean = name.endsWith(SyncRollbackGuard.SUFFIX_STATE)

        fun sha256Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data).toHexString()
        }
    }
}
