package com.keepasskey.app.ui.model

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * PNG 字节 → 渲染载荷的解码通道。
 * 生产实现走 Android 既有 `BitmapFactory`（不引入第三方依赖）；
 * 单测注入轻量替身，使投影与缓存语义可在 JVM 直测。
 */
fun interface IconBitmapDecoder<T : Any> {
    fun decode(pngBytes: ByteArray): T?
}

/**
 * 生产解码器：把 KDBX 图标池 PNG 解码为 Compose 位图。
 *
 * 两遍解码 + 2 的幂次降采样：先用 `inJustDecodeBounds` 读取原始尺寸，
 * 再把最长边收敛到 [MAX_ICON_EDGE_PX] 以内，防止异常大图整张载入导致 OOM。
 */
object AndroidBitmapIconDecoder : IconBitmapDecoder<ImageBitmap> {

    /** 图标最长边上限（对齐 KeePassXC 默认图标边长，图标仅作小尺寸展示） */
    private const val MAX_ICON_EDGE_PX = 128

    override fun decode(pngBytes: ByteArray): ImageBitmap? {
        if (pngBytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, options)?.asImageBitmap()
    }

    /** 降采样倍率（2 的幂次）：把最长边收敛到 [MAX_ICON_EDGE_PX] 以内 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var longestEdge = maxOf(width, height)
        var sampleSize = 1
        while (longestEdge / 2 >= MAX_ICON_EDGE_PX) {
            longestEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }
}

/**
 * 自定义图标投影装配器（ISSUE-P3-02 / TASK-49）。
 *
 * 职责：把「条目绑定的 customIconId」装配为可直接绘制的 [EntryIcon]：
 * 1. 判定形态（未绑定 / 命中 / 缺失）委托纯函数 [EntryIconProjection]；
 * 2. 命中池内图标时经 [decoder] 解码，且同一 iconId **只解码一次**（[IconBitmapCache] 复用）；
 * 3. 解码失败不抛出、不谎报：载荷为 null，渲染侧按缺图占位，并记录失败 id 避免反复重试。
 *
 * 线程约定：本类为挂起式装配，调用方须在后台调度器执行（见各 ViewModel 的 flowOn(Default)）；
 * 内部缓存自身线程安全。
 */
class EntryIconPresenter<T : Any>(
    private val loadIconBytes: suspend () -> Map<String, ByteArray>,
    private val decoder: IconBitmapDecoder<T>,
    maxCachedIcons: Int = IconBitmapCache.DEFAULT_MAX_SIZE
) {

    private val cache = IconBitmapCache<T>(maxCachedIcons)

    /** 已判定解码失败的图标 id（避免每次状态装配都重试同一张坏图） */
    private val undecodableIds = mutableSetOf<String>()

    /**
     * 批量投影（列表页）：条目 id → 图标。
     * 条目均未绑定自定义图标时零开销直返，不读取图标池。
     */
    suspend fun present(entries: List<UiVaultEntry>): Map<String, EntryIcon<T>> {
        if (entries.isEmpty()) return emptyMap()
        val referenced = entries.mapNotNull { it.customIconId }.toSet()
        val pool = loadPool(referenced)
        return entries.associate { entry ->
            entry.id to resolve(entry.customIconId, entry.iconName, pool)
        }
    }

    /** 单条投影（详情页） */
    suspend fun present(customIconId: String?, iconName: String): EntryIcon<T> {
        val pool = loadPool(setOfNotNull(customIconId))
        return resolve(customIconId, iconName, pool)
    }

    /** 仅在确有引用时读取图标池；同时剔除缓存中已下线的图标载荷 */
    private suspend fun loadPool(referencedIds: Set<String>): Map<String, ByteArray> {
        if (referencedIds.isEmpty()) return emptyMap()
        val pool = loadIconBytes()
        cache.retainOnly(pool.keys)
        synchronized(undecodableIds) { undecodableIds.retainAll(pool.keys) }
        return pool
    }

    private fun resolve(
        customIconId: String?,
        iconName: String,
        pool: Map<String, ByteArray>
    ): EntryIcon<T> {
        if (customIconId == null) return EntryIcon.Default(iconName)
        val bytes = pool[customIconId] ?: return EntryIcon.Missing
        return EntryIcon.Custom(customIconId, decodeOnce(customIconId, bytes))
    }

    /** 同一 iconId 复用已解码载荷；解码失败返回 null 并登记，不抛出 */
    private fun decodeOnce(iconId: String, bytes: ByteArray): T? {
        cache[iconId]?.let { return it }
        if (synchronized(undecodableIds) { iconId in undecodableIds }) return null
        val payload = runCatching { decoder.decode(bytes) }.getOrNull()
        if (payload == null) {
            synchronized(undecodableIds) { undecodableIds.add(iconId) }
            return null
        }
        return cache.put(iconId, payload)
    }

    companion object {
        /** 生产装配：库内图标池字节 + Android BitmapFactory 解码 */
        fun production(
            loadIconBytes: suspend () -> Map<String, ByteArray>
        ): EntryIconPresenter<ImageBitmap> =
            EntryIconPresenter(loadIconBytes, AndroidBitmapIconDecoder)
    }
}
