package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.sync.engine.SyncCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 同步缓存销毁器（ISSUE-P1-07 验收标准 1）。
 *
 * `cacheDir/sync` 下存放的是**完整 KDBX 密文快照**（`.cache` 工作副本与 `.basecache`
 * 三方合并基准）。`DatabaseSession.lock()` / `close()` 与 `SyncCredentialsStore.clear()`
 * 此前均不触碰该目录，导致锁定后密文仍长期驻留：设备失窃场景下攻击者可离线对快照
 * 无限期爆破主密码——「假设已被入侵」下的数据生命周期缺少终止点。
 *
 * 本类作为两条销毁时机的统一收口：
 * 1. **会话终止**：注册为 [SessionLockObserver]，由 `DatabaseSession` 在锁定/关闭时同步回调；
 * 2. **凭据销毁**：同步配置被清空（换服务器/退出同步）时由 `SyncCredentialsStore.clear()` 直接调用。
 *
 * F-23 边界：本类只清 `cacheDir/sync` 的 **KDBX 密文快照**。防回滚高水位状态已迁至
 * `filesDir/rollback`（`SyncRollbackGuard.STATE_DIR_NAME`），**不随锁定销毁**（它是安全状态，
 * 不是缓存）；即便目录内有升级前遗留的 `SUFFIX_STATE` 文件，[SyncCache.clearAll] 也会保留它们。
 *
 * 清理失败按「可用但不静默」处理：落调试日志并如实返回 false，绝不向上抛出——
 * 缓存清理失败不得阻断锁库或凭据清除主流程。
 */
@Singleton
class SyncCacheEvictor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debugLog: DebugLogBuffer
) : SessionLockObserver {

    /** 同步缓存目录（`context.cacheDir/sync`），与 [SyncCache.CACHE_DIR_NAME] 唯一对齐 */
    val cacheDir: File
        get() = File(context.cacheDir, SyncCache.CACHE_DIR_NAME)

    override fun onSessionLocked() {
        evictAll()
    }

    /**
     * 销毁全部同步缓存。
     *
     * F-23：防回滚状态**不在此目录**（生产为 `filesDir/rollback`，`SyncRollbackGuard.STATE_DIR_NAME`），
     * 且 [SyncCache.clearAll] 亦不会删除 `SUFFIX_STATE` 命名的文件。因此「残留计数」必须把
     * 这类文件排除在外，否则升级前落在本目录的历史状态文件会让每次锁库都误报
     * 「密文可能仍可恢复」——该文件仅含 SHA-256 摘要 + Keystore HMAC，不是密文快照。
     *
     * @return 缓存目录已不存在或（应清理的）内容已清空时返回 true；存在删除失败项返回 false
     */
    fun evictAll(): Boolean {
        val dir = cacheDir
        if (!dir.exists()) return true

        // 具体「哪些文件属于同步缓存」的知识保留在 sync 模块，避免 app 侧重复枚举后缀
        val cleared = runCatching { SyncCache(dir).clearAll() }.getOrElse { t ->
            debugLog.error(TAG, "同步缓存清理失败: ${t.javaClass.simpleName}")
            false
        }

        val remaining = dir.list()?.count { !SyncCache.isRollbackStateFileName(it) } ?: 0
        if (!cleared || remaining > 0) {
            debugLog.warn(TAG, "同步缓存残留 $remaining 项，锁定后密文可能仍可恢复")
        } else {
            debugLog.info(TAG, "同步缓存已随会话终止销毁")
        }
        return cleared && remaining == 0
    }

    private companion object {
        const val TAG = "SyncCacheEvictor"
    }
}
