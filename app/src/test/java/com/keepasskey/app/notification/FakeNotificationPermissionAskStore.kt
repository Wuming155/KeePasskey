package com.keepasskey.app.notification

/**
 * [NotificationPermissionAskStore] 的内存实现（JVM 单测用手写 Fake，不使用 MockK）。
 *
 * @param asked 初值，用于模拟「跨冷启动已持久化」的历史状态
 */
class FakeNotificationPermissionAskStore(
    private var asked: Boolean = false
) : NotificationPermissionAskStore {

    override fun wasAsked(): Boolean = asked

    override fun markAsked() {
        asked = true
    }
}
