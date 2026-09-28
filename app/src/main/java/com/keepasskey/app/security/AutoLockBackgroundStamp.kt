package com.keepasskey.app.security

import android.content.Context

/**
 * 后台化时间戳单源存储（ISSUE-P3-366 AC①）。
 *
 * 背景：[AutoLockManager] 此前把「退至后台时刻」只放在进程内存变量里，进程被杀重建后
 * 回前台补偿判定读到 0 ⇒ 「已离开多久」这一事实随进程消亡，超时判定丢失。本类型把时间戳
 * 经 [Storage] 持久化，会话恢复路径（onStart → [AutoLockSessionGuard.lockOnBackgroundResume]）
 * 据此判定是否已超自动锁时限。
 *
 * 口径：时间戳是**瞬态运行态**而非用户设置，故用独立存储单键（不入 UserSettings /
 * SettingsRepository 设置流，AC④ 不触碰超时回显显示链）。判定内核仍在 [AutoLockSessionGuard]
 * （纯 Kotlin 可 JVM 单测）；本类型只负责「写入 / 读回 / 清账」，[Storage] 抽象使
 * JVM 单测可注入内存假实现模拟「进程重建后读回旧时间戳」。
 */
class AutoLockBackgroundStamp(private val storage: Storage) {

    /** 退至后台：持久化起始时刻（单源写入） */
    fun markBackground(timestamp: Long) {
        storage.write(timestamp)
    }

    /**
     * 回前台判定用时间戳：进程存活时为本进程写入值；进程重建后读回上一进程持久化的旧值；
     * 从未退过后台（无记录）为 0——[AutoLockSessionGuard.lockOnBackgroundResume] 对 0 直接放行。
     */
    fun resolveForResume(): Long = storage.read()

    /** 无条件清账（解锁成功 / 后台定时器到点锁定等路径） */
    fun clear() {
        storage.clear()
    }

    /**
     * 仅当仍是 [expected] 这一段后台时间戳时清账。
     * 回前台判定可让出线程（读设置、锁库补存）——判定窗口内再次退后台会写入新时间戳，
     * 无条件清账会把新后台段一并抹掉，导致其超时事实丢失。
     */
    fun clearIfCurrent(expected: Long) {
        if (storage.read() == expected) storage.clear()
    }

    /** 持久化后端（单键）：生产为独立 SharedPreferences 文件，JVM 单测注入内存实现 */
    interface Storage {
        fun read(): Long
        fun write(timestamp: Long)
        fun clear()
    }
}

/**
 * [AutoLockBackgroundStamp.Storage] 的生产实现：独立 SharedPreferences 文件。
 *
 * 独立成键、独立文件（时间戳是瞬态运行态，不混入「keepasskey_settings」用户设置单源）；
 * null 上下文场景不存在（仅 [AutoLockManager] 生产构造点调用）。
 */
internal class AutoLockStampPreferences(context: Context) : AutoLockBackgroundStamp.Storage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): Long = prefs.getLong(KEY_BACKGROUND_TIMESTAMP, 0L)

    override fun write(timestamp: Long) {
        prefs.edit().putLong(KEY_BACKGROUND_TIMESTAMP, timestamp).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BACKGROUND_TIMESTAMP).apply()
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_auto_lock_state"
        const val KEY_BACKGROUND_TIMESTAMP = "background_timestamp"
    }
}
