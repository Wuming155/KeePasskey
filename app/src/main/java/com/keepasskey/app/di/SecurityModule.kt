package com.keepasskey.app.di

import com.keepasskey.app.security.AndroidKeystoreUnlockThrottleIntegrity
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.SharedPrefsUnlockThrottleStore
import com.keepasskey.app.security.ThrottleConfigSource
import com.keepasskey.app.security.UnlockPasskeyStore
import com.keepasskey.app.security.UnlockThrottleConfigProvider
import com.keepasskey.app.security.UnlockThrottleIntegrity
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
 *
 * ISSUE-P1-09：将解锁通行密钥登记记录存储 [UnlockPasskeyStore] 绑定到
 * [BiometricCredentialStorage]（含硬件 HMAC 防篡改封存）；
 * JVM 单测注入内存实现直接构造 [UnlockPasskeyManager]。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindUnlockThrottleStore(
        impl: SharedPrefsUnlockThrottleStore
    ): UnlockThrottleStore

    /** ISSUE-P3-54：节流记录完整性校验绑定到 AndroidKeyStore HMAC 实现（fail-closed） */
    @Binds
    @Singleton
    abstract fun bindUnlockThrottleIntegrity(
        impl: AndroidKeystoreUnlockThrottleIntegrity
    ): UnlockThrottleIntegrity

    /** ISSUE-P3-68：节流配置源绑定（设置流 → 进程级缓存快照，节流路径同步读取） */
    @Binds
    @Singleton
    abstract fun bindUnlockThrottleConfigSource(
        impl: UnlockThrottleConfigProvider
    ): ThrottleConfigSource

    @Binds
    @Singleton
    abstract fun bindUnlockPasskeyStore(
        impl: BiometricCredentialStorage
    ): UnlockPasskeyStore
}
