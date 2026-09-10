package com.keepasskey.app.di

import com.keepasskey.app.data.childdb.ChildDatabaseStreamSource
import com.keepasskey.app.data.childdb.LocalChildDatabaseStreamSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 子库挂载（ISSUE-P3-20）依赖注入模块。
 *
 * 只声明**需要抽象绑定**的一项：`content://` / 本地路径的来源读取器。
 * 其余协作者均由 `@Inject` 构造器自证可注入，无需在此重复声明：
 *
 * - `ChildDatabaseMountStore`（挂载注册表，`@Singleton`，仅写自身偏好文件）
 * - `ChildDatabaseCredentialStore`（子库凭据独立通道，`@Singleton`，纯内存）
 * - `ChildDatabaseSessionManager`（编排 + 锁库联动，`@Singleton`）
 *
 * 依赖方向：本模块仅依赖既有 `database` / `core` 公开 API，**未新增任何跨模块反向依赖**，
 * 也未改动 `DatabaseSession` / `KdbxFile`（并行编辑中的共享文件保持原样）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChildDatabaseModule {

    /** 生产环境使用本机来源读取器；单测直接构造内存实现，不经 Hilt */
    @Binds
    @Singleton
    abstract fun bindChildDatabaseStreamSource(
        localChildDatabaseStreamSource: LocalChildDatabaseStreamSource
    ): ChildDatabaseStreamSource
}
