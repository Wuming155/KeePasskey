package com.keepasskey.app.ui.screens.unlock

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 密钥文件访问绑定模块（ISSUE-P3-04）。
 *
 * 把 [KeyFileAccess] 契约绑定到 SAF 生产实现 [SafKeyFileAccess]，供
 * [UnlockViewModel] 构造注入；JVM 单测直接构造内存假实现，不经 DI。
 *
 * 位置说明：本模块随特性置于解锁包内（而非 `di` 依赖注入包），
 * 以遵守本次并行整改的文件范围约束；如需归位 `di` 包可在集成时平移，
 * 平移不影响绑定语义。
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class KeyFileAccessModule {

    @Binds
    @Singleton
    abstract fun bindKeyFileAccess(impl: SafKeyFileAccess): KeyFileAccess
}
