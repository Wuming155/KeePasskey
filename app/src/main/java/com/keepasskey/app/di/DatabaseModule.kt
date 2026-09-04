package com.keepasskey.app.di

import com.keepasskey.database.session.DatabaseSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库模块依赖注入提供者
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabaseSession(): DatabaseSession {
        return DatabaseSession()
    }
}
