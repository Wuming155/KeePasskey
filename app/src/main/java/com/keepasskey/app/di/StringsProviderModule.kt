package com.keepasskey.app.di

import android.content.Context
import com.keepasskey.app.ui.model.StringsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * TASK-21：[StringsProvider] 生产绑定——经 @ApplicationContext 转发 `Context.getString`。
 * 单元测试环境不走 Hilt，直接构造 SettingsViewModel 时注入假实现。
 */
@Module
@InstallIn(SingletonComponent::class)
object StringsProviderModule {

    @Provides
    @Singleton
    fun provideStringsProvider(@ApplicationContext context: Context): StringsProvider =
        StringsProvider { id, args -> context.getString(id, *args) }
}
