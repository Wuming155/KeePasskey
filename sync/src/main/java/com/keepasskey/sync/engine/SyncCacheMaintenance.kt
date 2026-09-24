package com.keepasskey.sync.engine

import com.keepasskey.sync.model.cleanEtag
import java.io.File

/**
 * 同步缓存的**元数据 / 基准快照 / 清理**面（ISSUE-P3-305 自 [SyncCache] 按职责拆出，逐行搬运）。
 *
 * 职责边界：三哈希语义中「版本与元数据（`<hash>.version` / `<hash>.baseversion` /
 * `<hash>.meta` / `<hash>.basecache`）的读写」、单路径与全量销毁 [clear] / [clearAll]、
 * 以及 `ISSUE-P2-291` 的旧键一次性迁移 [adoptLegacyKeysIfPresent]。
 * 字节内容缓存（[SyncCache.readCache] / [SyncCache.writeCache] 等 `open` 原语）仍由 [SyncCache]
 * 持有——那些成员是单元测试子类化的注入点，不可搬迁。
 *
 * 键派生：构造时注入 [keyOf]（即 [SyncCache] 的 `scopedKey`），使「库身份命名空间参与规范键」
 * 只有一份实现；[SyncCacheFiles.deleteOrphanTmpFiles] 的调用参数沿用**未加命名空间的 remotePath**
 * （与拆分前逐字一致，本轮不改变其口径）。
 */
internal class SyncCacheMaintenance(
    private val files: SyncCacheFiles,
    private val cacheDir: File,
    private val keyOf: (String) -> String
) {

    /**
     * 判断本地缓存相对于基准版本是否存在未提交修改。
     * 即 localVersion != baseVersion。
     */
    fun hasLocalChanges(remotePath: String): Boolean {
        val versionFile = getFile(remotePath, SyncCache.SUFFIX_VERSION)
        if (!versionFile.exists()) return false

        val baseVersionFile = getFile(remotePath, SUFFIX_BASE_VERSION)
        if (!baseVersionFile.exists()) return true

        val localVer = versionFile.readText().trim()
        val baseVer = baseVersionFile.readText().trim()
        return localVer != baseVer
    }

    /**
     * 持久化基准内容快照。仅在字节确认与远端一致时由 SyncEngine 调用。
     *
     * ISSUE-P3-202：同 [SyncCache.writeCache]——tmp 写入改走 [SyncCacheFiles.writeTmpSynced]，
     * 落盘第一字节起即仅属主可见（原实现裸 `FileOutputStream` 依赖进程 umask）。
     */
    fun writeBaseContent(remotePath: String, data: ByteArray) {
        val baseFile = getFile(remotePath, SyncCache.SUFFIX_BASE_CACHE)
        val tmpFile = files.tmpFileFor(baseFile)
        files.writeTmpSynced(tmpFile, data)
        files.moveAtomically(tmpFile, baseFile)
    }

    /**
     * 更新基准版本 `<hash>.baseversion` 与元数据 `<hash>.meta`（TASK-37 整改：两文件合并原子写）。
     *
     * 此前两文件各自独立走「tmp -> fsync -> rename」，两写之间存在崩溃窗口：
     * baseversion 已更新而 meta（etag/lastSyncMillis）仍是旧值，下一轮同步会以
     * 过期 etag 发起 If-Match，制造本可避免的假冲突。
     * 整改后：先把两份内容分别写入唯一 tmp 并 fsync（慢路径），随后背靠背执行两次
     * 原子 rename（快路径，微秒级）——把不一致窗口从「两次完整写盘」压缩到
     * 「两个原子 rename 之间」，且崩溃残留 tmp 由 [SyncCacheFiles.deleteOrphanTmpFiles] 通配清理。
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

        val baseTmp = files.tmpFileFor(baseFile)
        val metaTmp = files.tmpFileFor(metaFile)
        try {
            files.writeTmpSynced(baseTmp, baseVersion.trim().toByteArray(Charsets.UTF_8))
            files.writeTmpSynced(metaTmp, metaContent.toByteArray(Charsets.UTF_8))
            files.moveAtomically(baseTmp, baseFile)
            files.moveAtomically(metaTmp, metaFile)
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
        val cacheFile = getFile(remotePath, SyncCache.SUFFIX_CACHE)
        if (!cacheFile.exists()) return null

        val versionFile = getFile(remotePath, SyncCache.SUFFIX_VERSION)
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
     *
     * **不变量（F-23）**：本方法**永不删除** [SyncRollbackGuard.SUFFIX_STATE] 命名的防回滚状态文件。
     * 该状态是跨会话安全状态（Assume Breach 下唯一的重放防线），语义上不属于「可丢弃缓存」；
     * 生产路径它已迁至 `filesDir/<SyncRollbackGuard.STATE_DIR_NAME>`，此处再保证一次——
     * 即便调用方把状态目录误配到缓存目录，缓存清理也不得摧毁重放防护
     * （整改前它被列入下方删除清单，锁库即清零 → 云侧重放旧库得逞）。
     * 边界：仅「已交付的状态文件」受保护；未交付的 `<...>.rollback.*.tmp` 残留仍按 tmp 规则清理
     * （它不含任何已提交状态，删除不影响重放判定）。
     */
    fun clear(remotePath: String) {
        listOf(
            SyncCache.SUFFIX_CACHE,
            SyncCache.SUFFIX_VERSION,
            SUFFIX_BASE_VERSION,
            SyncCache.SUFFIX_BASE_CACHE,
            SUFFIX_META,
            "${SyncCache.SUFFIX_CACHE}${SyncCacheFiles.SUFFIX_TMP}"
        ).forEach { suffix ->
            val file = getFile(remotePath, suffix)
            if (file.exists()) {
                // ISSUE-P3-108：与 clearAll 共用同一「有界重试 + 幂等」删除原语
                //（即 [SyncCache.deleteCacheChild] 所委托的 [SyncCacheFiles.deleteChild]），
                // 消除「瞬时句柄未释放即视为删除失败」的路径差异。
                files.deleteChild(file)
            }
        }
        files.deleteOrphanTmpFiles(remotePath)
    }

    /**
     * 销毁全部远端路径的缓存（ISSUE-P1-07）。
     *
     * 会话锁定与同步凭据销毁时调用：缓存内是**完整 KDBX 密文快照**，锁定后若不清理，
     * 设备失窃即可被离线无限期爆破主密码——「锁定」必须在数据生命周期上真正闭环。
     * 仅清空目录内容（目录本身保留，[SyncCache] 构造期保证其存在）。
     *
     * **例外（F-23）**：命中 [SyncCache.isRollbackStateFileName] 的子项一律**保留**——防回滚状态是
     * 跨会话安全状态（内容仅 SHA-256 摘要 + Keystore HMAC，无密文、无明文），
     * 缓存销毁不得连带摧毁重放防护。生产布局下它不在此目录，此分支仅兜底误配 / 历史残留。
     *
     * @return 是否全部（应删除的）子项删除成功（存在删除失败项时返回 false，调用方可据此告警）
     */
    fun clearAll(): Boolean {
        val children = cacheDir.listFiles() ?: return true
        var allDeleted = true
        for (child in children) {
            if (SyncCache.isRollbackStateFileName(child.name)) continue
            allDeleted = files.deleteChild(child) && allDeleted
        }
        return allDeleted
    }

    /**
     * `ISSUE-P2-291`：库身份键控升级的**一次性迁移**——把旧无命名空间键
     * （`SHA-256(remotePath)`）下的缓存 / 版本 / 基线 / 元数据文件改名纳入本实例的
     * 库身份键（仅当库身份键尚无对应文件，rename 失败按「无旧键」由上层重新建立基线）。
     * 在绑定首次创建（首个把 `remotePath` 与库身份绑定的同步周期）时调用：
     * 单库老用户的缓存 / 基线 / ETag 零感知延续；多库场景旧键归「最后同步者」，
     * 另一库的旧键残留由确认闸拦截后重建，绝不串用。
     */
    fun adoptLegacyKeysIfPresent(remotePath: String) {
        // 等价于拆分前的 `if (vaultScope.isEmpty()) return`：空命名空间下规范键恒等于裸 remotePath
        //（见 [SyncCache.scopedKey]），故「键未变」与「无命名空间」互为充要条件（已逐字核对）。
        if (keyOf(remotePath) == remotePath) return
        for (suffix in listOf(
            SyncCache.SUFFIX_CACHE,
            SyncCache.SUFFIX_VERSION,
            SUFFIX_BASE_VERSION,
            SyncCache.SUFFIX_BASE_CACHE,
            SUFFIX_META
        )) {
            val legacyFile = files.fileFor(remotePath, suffix)
            val scopedFile = getFile(remotePath, suffix)
            if (!scopedFile.exists() && legacyFile.exists()) {
                legacyFile.renameTo(scopedFile)
            }
        }
    }

    /** 定位缓存目录内的规范键文件（键派生由构造注入的 [keyOf] 提供，唯一实现在 [SyncCache]）。 */
    private fun getFile(remotePath: String, suffix: String): File = files.fileFor(keyOf(remotePath), suffix)

    companion object {
        private const val SUFFIX_BASE_VERSION = ".baseversion"
        private const val SUFFIX_META = ".meta"

        private const val KEY_REMOTE_PATH = "remotePath"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_SYNC_MILLIS = "lastSyncMillis"
    }
}
