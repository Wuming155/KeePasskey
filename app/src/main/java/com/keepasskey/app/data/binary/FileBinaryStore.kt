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
 * 大附件磁盘缓存实现（ISSUE-P2-24）。
 *
 * 落盘目录 `cacheDir/attachments`，复用 [SyncCache] 的落盘基线：
 * 原子写盘（.tmp → flush/fsync → 原子 rename）与权限收敛（文件 0600 / 目录 0700）。
 * key 为不透明随机串（SyncCache 内部再以 SHA-256 规范键命名文件），
 * 因此 [store] 的返回值不泄漏任何附件内容信息。
 *
 * 生命周期：实现 [SessionLockObserver]，会话锁定 / 关闭时清空缓存目录——
 * 「锁定」在数据生命周期上闭环，设备失窃后无附件明文可挖。
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

    override fun clear() {
        cache.clearAll()
    }

    /**
     * 会话锁定 / 关闭回调（[SessionLockObserver] 契约：非阻塞、幂等、自容错）。
     * 清理失败只记录脱敏日志，绝不外抛（外抛会被会话锁定流程吞掉且无从告警）。
     */
    override fun onSessionLocked() {
        if (!cache.clearAll()) {
            AppLog.w(TAG, "附件缓存目录存在删除失败项，可能残留明文附件快照")
        }
    }

    private fun newKey(): String = UUID.randomUUID().toString()

    private companion object {
        /** `cacheDir` 下的附件缓存目录名。 */
        const val CACHE_DIR_NAME = "attachments"
        const val TAG = "FileBinaryStore"
    }
}
