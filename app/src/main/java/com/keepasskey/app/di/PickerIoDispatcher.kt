package com.keepasskey.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * 密码库管理页**凭据面读取**（云同步配置快照 / 打开对话框的云账号预填）所用的调度器限定符。
 *
 * ISSUE-P2-424 AC③ / ISSUE-P3-425：这些读取会解封 Keystore 密文（阻塞操作），不得落 Main。
 * 之所以以限定符注入而非在 `DatabasePickerViewModel` 内硬编码 `Dispatchers.IO`：
 * 单测可替换为测试调度器，使读取完全落在虚拟时间轴上（断言确定性，无跨线程竞态）——
 * 与 `@VaultProjectionDispatcher` / `@EntryDisplayDispatcher` 同一做法。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PickerIoDispatcher

/**
 * 凭据面读取调度器的生产绑定：Keystore 解封与 prefs 读取属阻塞 IO，固定 [Dispatchers.IO]。
 * **禁止**改为 `Dispatchers.Main`——那正是本限定符要消除的行为。
 */
@Module
@InstallIn(SingletonComponent::class)
object PickerIoDispatcherModule {

    @Provides
    @PickerIoDispatcher
    fun providePickerIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
