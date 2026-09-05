package com.keepasskey.app.di

import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库层依赖注入模块。
 *
 * L3 整改：生产环境必须绑定持久化实现的 [com.keepasskey.app.data.repository.RealSettingsRepository]，
 * 确保生物识别 / FLAG_SECURE / 自动锁等安全设置跨冷启动持久化。
 * [FakeSettingsRepository] 仅保留给 JVM 单元测试直接构造使用，不得在生产 DI 中绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindVaultRepository(
        realVaultRepository: RealVaultRepository
    ): VaultRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        realSettingsRepository: RealSettingsRepository
    ): SettingsRepository
}
