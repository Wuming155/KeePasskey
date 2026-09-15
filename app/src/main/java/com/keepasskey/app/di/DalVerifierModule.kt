package com.keepasskey.app.di

import com.keepasskey.app.passkey.DalEndpointResolver
import com.keepasskey.app.passkey.MillisClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Digital Asset Links 校验器的策略注入模块（ISSUE-P3-125②）。
 *
 * 该模块是这两个「策略」在生产图中的**唯一**来源，且只提供**唯一实现**：
 * - [DalEndpointResolver] → 官方 well-known 路径；
 * - [MillisClock] → 系统时钟。
 *
 * 由此，「把 DAL 拉取重定向到任意端点」在运行期**没有入口**——该能力只存在于
 * 单测的构造参数里（单测直接 `DigitalAssetLinksVerifier(fakeResolver, fakeClock)`，
 * 不经 Hilt）。对照旧实现：`@Singleton` 上的 `@Volatile internal var endpointOverride`
 * 可被同模块任意生产代码改写。
 */
@Module
@InstallIn(SingletonComponent::class)
object DalVerifierModule {

    @Provides
    @Singleton
    fun provideDalEndpointResolver(): DalEndpointResolver = DalEndpointResolver.Official

    @Provides
    @Singleton
    fun provideMillisClock(): MillisClock = MillisClock.SystemClock
}
