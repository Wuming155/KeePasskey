package com.keepasskey.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * 快速解锁封印体系的 **keystore 密算**（封印密钥供给 / 授权 Cipher 加密封印载荷 / 解封密文）
 * 所用的调度器限定符（ISSUE-P1-429）。
 *
 * 这些操作是 keystore2 binder 调用，StrongBox 分块加密 1 MiB 载荷（含密钥文件因子的封印帧）
 * 可达秒级，**绝不上 Main**——否则输入超时 ANR（真机两条 ANR 实证）。
 * 之所以以限定符注入而非在协调器内硬编码 `Dispatchers.IO`：
 * JVM 单测可替换为测试调度器，使封印 / 解封完全落在虚拟时间轴上（断言确定性，无跨线程竞态）——
 * 与 `@PickerIoDispatcher` / `@VaultProjectionDispatcher` / `@EntryDisplayDispatcher` 同一做法。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SealCryptoDispatcher

/**
 * 封印密算调度器的生产绑定：keystore 密算属阻塞 binder 调用，固定 [Dispatchers.IO]。
 * **禁止**改为 `Dispatchers.Main`——那正是本限定符要消除的行为。
 */
@Module
@InstallIn(SingletonComponent::class)
object SealCryptoDispatcherModule {

    @Provides
    @SealCryptoDispatcher
    fun provideSealCryptoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
