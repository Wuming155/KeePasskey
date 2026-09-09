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
     * @return 缓存目录已不存在或内容已清空时返回 true；存在删除失败项返回 false
     */
    fun evictAll(): Boolean {
        val dir = cacheDir
        if (!dir.exists()) return true

        // 具体「哪些文件属于同步缓存」的知识保留在 sync 模块，避免 app 侧重复枚举后缀
        val cleared = runCatching { SyncCache(dir).clearAll() }.getOrElse { t ->
            debugLog.error(TAG, "同步缓存清理失败: ${t.javaClass.simpleName}")
            false
        }

        val remaining = dir.list()?.size ?: 0
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
