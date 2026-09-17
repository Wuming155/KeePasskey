package com.keepasskey.app.data.repository

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * 仓库**整库投影**（KDBX 领域模型 → UI 投影）所用的调度器限定符。
 *
 * ISSUE-P3-154：`getEntries()` / `getGroups()` 的投影（逐字段解密 + 时间格式化）此前在
 * **收集上下文**执行——列表页的收集上下文是 `viewModelScope`（Main），故该投影落在主线程上。
 *
 * 之所以以限定符注入而非在 [RealVaultRepository] 内硬编码 `Dispatchers.Default`：
 * 单测可替换为测试调度器，使投影完全落在虚拟时间轴上（断言确定性，无跨线程竞态）。
 * 与展示层既有的 `@EntryDisplayDispatcher` 同一做法。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VaultProjectionDispatcher

/**
 * 整库投影调度器的生产绑定：投影为 CPU 工作（解密 + 格式化），固定 [Dispatchers.Default]。
 *
 * **禁止**改为 `Dispatchers.Main`——那正是本条要消除的行为。
 * 本限定符**只覆盖投影流**（`getEntries()` / `getGroups()`）：仓库自身的
 * `repositoryScope`（库列表刷新与失效通知）仍独立持有 `Dispatchers.Default`，
 * 不得合并——否则测试注入的虚拟时间调度器会让该常驻作用域永不结束。
 */
@Module
@InstallIn(SingletonComponent::class)
object VaultProjectionModule {

    @Provides
    @VaultProjectionDispatcher
    fun provideVaultProjectionDispatcher(): CoroutineDispatcher = Dispatchers.Default
}