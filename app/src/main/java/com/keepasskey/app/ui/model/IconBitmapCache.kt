package com.keepasskey.app.ui.model

/**
 * 自定义图标绘制载荷的有界 LRU 缓存（ISSUE-P3-02 / TASK-49）。
 *
 * 存在的两个理由：
 * 1. **去重解码**：列表投影时同一 `customIconId` 会被多个条目引用，重复解码即重复分配位图；
 * 2. **有界内存**：图标池随库文件同步增长，缓存必须有上限，避免稳态占用无上限膨胀
 *    （KDBX 生态图标为小尺寸 PNG，解码后单张约 64 KB 量级，上限 [DEFAULT_MAX_SIZE] 张
 *    约数 MB 级，与列表可见条目规模匹配）。
 *
 * 线程安全：内部按 accessOrder 的 [LinkedHashMap] + synchronized，
 * 允许解码协程与状态装配并发访问。
 */
class IconBitmapCache<T : Any>(val maxSize: Int = DEFAULT_MAX_SIZE) {

    init {
        require(maxSize > 0) { "图标缓存上限必须为正数，实际为 $maxSize" }
    }

    private val entries = object : LinkedHashMap<String, T>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>): Boolean =
            size > maxSize
    }

    private val lock = Any()

    /** 命中返回载荷，未命中返回 null（并视为最近使用） */
    operator fun get(iconId: String): T? = synchronized(lock) { entries[iconId] }

    /** 存入载荷并返回之；超出上限时按 LRU 淘汰最久未使用项 */
    fun put(iconId: String, payload: T): T = synchronized(lock) {
        entries[iconId] = payload
        payload
    }

    /**
     * 仅保留 [survivingIds] 内的条目：图标池变更（删除图标）后剔除下线载荷，
     * 避免缓存继续持有已从库 Meta 移除的图标。
     */
    fun retainOnly(survivingIds: Set<String>) = synchronized(lock) {
        entries.keys.retainAll(survivingIds)
    }

    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int
        get() = synchronized(lock) { entries.size }

    /** 当前缓存内的图标 id 快照（供断言与诊断，不含载荷） */
    fun cachedIds(): Set<String> = synchronized(lock) { entries.keys.toSet() }

    companion object {
        /** 稳态缓存上限：约数 MB 级解码位图，足以覆盖列表可见条目引用的图标 */
        const val DEFAULT_MAX_SIZE: Int = 64

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
