package com.keepasskey.app.di

import com.keepasskey.app.notification.NotificationPermissionAskStore
import com.keepasskey.app.notification.SharedPrefsNotificationPermissionAskStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 通知域依赖注入模块（ISSUE-P3-18）。
 *
 * 把「是否已询问过通知权限」的持久化记录 [NotificationPermissionAskStore] 绑定到
 * SharedPreferences 实现：该标志必须跨冷启动存活，否则用户拒绝一次后每次启动都会再弹一次
 * （ISSUE-P3-18 验收标准 2 明令禁止「反复弹窗」）。
 *
 * 通知通道建立（[com.keepasskey.app.notification.NotificationChannels.ensureCreated]）与
 * 已解锁常驻通知控制器（[com.keepasskey.app.notification.UnlockedNotificationController]）
 * 均为无参/上下文构造的 @Singleton，由 Hilt 直接构造，无需额外 @Provides。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {

    @Binds
    @Singleton
    abstract fun bindNotificationPermissionAskStore(
        impl: SharedPrefsNotificationPermissionAskStore
    ): NotificationPermissionAskStore
}
