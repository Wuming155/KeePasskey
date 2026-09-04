package com.keepasskey.app.di

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.data.repository.RealVaultRepository
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.data.repository.VaultRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库层依赖注入模块
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
        fakeSettingsRepository: FakeSettingsRepository
    ): SettingsRepository
}
