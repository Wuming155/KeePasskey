package com.keepasskey.app.data.binary

import android.content.Context
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.security.BinaryStore
import com.keepasskey.core.session.SessionLockObserver
import com.keepasskey.sync.engine.SyncCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 大附件磁盘缓存实现（ISSUE-P2-24；F-13 冷启动清理补齐）。
 *
 * 落盘目录 `cacheDir/attachments`，复用 [SyncCache] 的落盘基线：
 * 原子写盘（.tmp → flush/fsync → 原子 rename）与权限收敛（文件 0600 / 目录 0700）。
 * key 为不透明随机串（SyncCache 内部再以 SHA-256 规范键命名文件），
 * 因此 [store] 的返回值不泄漏任何附件内容信息。
 *
 * 生命周期（**两层，缺一不可**）：
 * 1. **运行时终止**：实现 [SessionLockObserver]，会话锁定 / 关闭时清空缓存目录；
 * 2. **进程冷启动**：`MainApplication.onCreate()` 在**任何会话打开之前**调用 [clear]。
 *    目录内是**附件解密后的明文**，进程被 kill / force-stop / OOM 回收时
 *    [onSessionLocked] 不会执行，仅靠第 1 层会使上一次运行的明文跨进程存活
 *    到下一次锁定——这是唯一「无需口令即可读取库内容」的已确认路径（F-13）。
 *
 * 两层的清理都走同一收敛路径（[purgeAttachmentCache]）：删除失败只记脱敏日志、绝不外抛。
 */
@Singleton
class FileBinaryStore @Inject constructor(
    @ApplicationContext context: Context
) : BinaryStore, SessionLockObserver {

    private val cache = SyncCache(File(context.cacheDir, CACHE_DIR_NAME))

    override fun store(bytes: ByteArray): String {
        val key = newKey()
        cache.writeCache(key, bytes)
        return key
    }

    override fun storeFromStream(input: InputStream, size: Long): String {
        val key = newKey()
        cache.writeCacheStreaming(key, input, size)
        return key
    }

    override fun load(key: String): ByteArray = cache.readCache(key) ?: ByteArray(0)

    override fun openStream(key: String): InputStream =
        cache.openCacheStream(key) ?: ByteArrayInputStream(ByteArray(0))

    override fun sizeOf(key: String): Long = cache.cacheSize(key)

    /**
     * 清空全部落盘数据（[BinaryStore] 契约：会话锁定 / 关闭时调用）。
     *
     * **时序前提（冷启动路径，F-13）**：进程冷启动阶段由 `MainApplication.onCreate()`
     * 在起始段调用本方法，此刻**尚无任何会话被打开**（解锁 / 自动填充 / 凭据提供者等入口
     * 都晚于 Application.onCreate），故缓存目录内不存在仍被会话或附件引用持有的条目；
     * 调用点因此必须固定在「任何会话打开之前」，否则会删掉在用附件的落盘字节。
     *
     * 失败收敛契约：删除失败（或清理抛异常）只记录脱敏日志，**绝不外抛**——
     * 锁定回调没有上层兜底，冷启动路径更不得因清理失败而阻断应用启动。
     */
    override fun clear() {
        purgeAttachmentCache(
            purge = { cache.clearAll() },
            onFailure = ::logPurgeFailure
        )
    }

    /**
     * 会话锁定 / 关闭回调（[SessionLockObserver] 契约：非阻塞、幂等、自容错）。
     * 与冷启动清理共用同一收敛路径（[clear]），清理失败只记脱敏日志，绝不外抛。
     */
    override fun onSessionLocked() = clear()

    /** 清理失败告警：只落异常类名（[AppLog] release 侧统一脱敏），不含任何附件内容信息。 */
    private fun logPurgeFailure(cause: Throwable?) {
        if (cause == null) {
            AppLog.w(TAG, "附件缓存目录存在删除失败项，可能残留明文附件快照")
        } else {
            AppLog.w(TAG, "附件缓存清理失败，可能残留明文附件快照", cause)
        }
    }

    private fun newKey(): String = UUID.randomUUID().toString()

    private companion object {
        /** `cacheDir` 下的附件缓存目录名。 */
        const val CACHE_DIR_NAME = "attachments"
        const val TAG = "FileBinaryStore"
    }
}

/**
 * 附件缓存清理的失败收敛策略（纯函数，零 Android 依赖，JVM 单测全覆盖）。
 *
 * 契约（对齐 [SessionLockObserver] 的「自容错」要求，冷启动与锁定两条路径共用）：
 * - 清理动作返回 false（存在删除失败项）→ 告警并返回 false；
 * - 清理动作抛异常（含 IO 异常）→ **收敛为告警**并返回 false，绝不外抛：
 *   锁定回调抛异常会被会话锁定流程吞掉且无从告警，冷启动路径抛出则直接阻断应用启动；
 * - 清理成功 → 不告警，返回 true。
 *
 * @param purge 实际清理动作，返回「是否全部删除成功」
 * @param onFailure 失败回调；参数为抛出的异常，仅「删除失败项」时为 null
 * @return 缓存目录内容是否已全部删除
 */
internal fun purgeAttachmentCache(
    purge: () -> Boolean,
    onFailure: (cause: Throwable?) -> Unit
): Boolean {
    val cleared = try {
        purge()
    } catch (t: Throwable) {
        // 覆盖任意清理实现：清理失败不得反噬调用方（冷启动 / 会话锁定）主流程
        onFailure(t)
        return false
    }
    if (!cleared) onFailure(null)
    return cleared
}
