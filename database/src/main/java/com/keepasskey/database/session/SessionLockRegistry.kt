package com.keepasskey.database.session

import com.keepasskey.core.session.SessionLockObserver

/**
 * 会话终止观察者集合（ISSUE-P1-07；§280 自 [DatabaseSession] 拆出，纯结构性）。
 *
 * 锁定/关闭是不可失败的原子动作：观察者异常一律隔离吞掉，绝不允许某个派生数据的
 * 清理失败反噬会话锁定本身（观察者须按 [SessionLockObserver] 契约自行记录失败）。
 */
internal class SessionLockRegistry {
    private val sessionLockObservers = LinkedHashSet<SessionLockObserver>()
    private val observerLock = Any()

    /** @return 观察者此前未注册时返回 true（重复注册为幂等无操作，返回 false） */
    fun add(observer: SessionLockObserver): Boolean = synchronized(observerLock) {
        sessionLockObservers.add(observer)
    }

    /** 注销会话终止观察者；未注册时返回 false */
    fun remove(observer: SessionLockObserver): Boolean = synchronized(observerLock) {
        sessionLockObservers.remove(observer)
    }

    /** 通知观察者会话已终止（换库替换时可传入 [except] 排除按键事务独立管理的附件存储）。 */
    fun notifySessionLocked(except: SessionLockObserver? = null) {
        val snapshot = synchronized(observerLock) { sessionLockObservers.toList() }
        for (observer in snapshot) {
            if (except != null && observer === except) continue
            try {
                observer.onSessionLocked()
            } catch (_: Throwable) {
                // 隔离：清理失败不得阻断锁定流程
            }
        }
    }
}
