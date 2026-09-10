package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 进阶显示偏好快照读取通道（ISSUE-P3-17）。
 *
 * `ExtendedSettingsStore` 只有同步 `load()`（无 Flow 通道，且该文件不在本工作流所有权内），
 * UI 侧因此依赖本函数式接口而非直接依赖持久化仓库：
 * 生产实现每次调用现读一次快照；单测注入固定快照，使「偏好 → UI 状态」的接线可断言。
 */
fun interface ExtendedSettingsSource {
    fun load(): ExtendedSettings
}

/**
 * 生产绑定：转发到 [ExtendedSettingsStore.load]（每次调用取当前快照，不缓存）。
 *
 * 本模块随本文件声明，避免改动 `app/di` 目录（并行工作组文件范围约束），
 * 与 `EntryDisplayModule` 同一做法。
 */
@Module
@InstallIn(SingletonComponent::class)
object ExtendedSettingsSourceModule {

    @Provides
    @Singleton
    fun provideExtendedSettingsSource(store: ExtendedSettingsStore): ExtendedSettingsSource =
        ExtendedSettingsSource { store.load() }
}
