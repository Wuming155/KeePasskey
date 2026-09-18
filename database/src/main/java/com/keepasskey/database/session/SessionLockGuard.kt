package com.keepasskey.database.session

import com.keepasskey.core.session.SessionLockObserver

/**
 * 「会话锁定 / 关闭回调」的**成对登记**样板（`ISSUE-P3-188` §170 收敛）。
 *
 * 背景：`EntryDetailViewModel` / `EntryEditViewModel` / `GeneratorViewModel` /
 * `SettingsViewModel` / `AutofillPickerViewModel` 各自手写同一套三件套——
 * 「构造 [SessionLockObserver] + `init` 里 `addLockObserver` + `onCleared` 里 `removeLockObserver`」。
 * 三件套里**易错的是成对性**（只注册不注销会泄漏观测器，只注销不注册则锁定不生效），
 * 本类把成对性收敛到一处；**擦除动作本身仍由调用方传入**，本类不持有任何秘密。
 *
 * 观测器的行为契约（非阻塞、幂等、自容错）仍由 [SessionLockObserver] 承担，本类不改写它。
 *
 * @param session 允许为 null——沿用各 ViewModel「注入通道可缺省（纯 JVM 单测）」的既有语义
 * @param onLocked 锁定 / 关库时的回调（须在调用线程上快速返回）
 */
class SessionLockGuard(
    private val session: DatabaseSession?,
    onLocked: () -> Unit
) {

    private val observer = SessionLockObserver(onLocked)

    /**
     * 注册观测器（调用点在 ViewModel 的 `init`）。
     * [DatabaseSession.addLockObserver] 把观测器放进**集合**，故同一实例重复注册不会二次生效
     * （返回 false）；本类不做额外缓存，注册与注销严格成对即可。
     */
    fun register() {
        session?.addLockObserver(observer)
    }

    /** 注销观测器（调用点在 ViewModel 的 `onCleared`） */
    fun unregister() {
        session?.removeLockObserver(observer)
    }
}
