package com.keepasskey.app.di

import com.keepasskey.app.security.SharedPrefsUnlockThrottleStore
import com.keepasskey.app.security.UnlockThrottleStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 安全域依赖注入模块。
 *
 * ISSUE-P1-04：将主密码解锁失败节流存储 [UnlockThrottleStore] 绑定到持久化实现
 * [SharedPrefsUnlockThrottleStore]，确保失败计数与锁定截止跨冷启动持久化
 * （杜绝「杀进程即重置计数」的绕过路径）。内存实现仅供 JVM 单测直接构造使用。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindUnlockThrottleStore(
        impl: SharedPrefsUnlockThrottleStore
    ): UnlockThrottleStore
}
