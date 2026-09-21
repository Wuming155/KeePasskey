package com.keepasskey.app.di

import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.app.data.childdb.ChildDatabaseStreamSource
import com.keepasskey.app.data.childdb.LocalChildDatabaseStreamSource
import com.keepasskey.core.security.BinaryStore
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

    /**
     * ISSUE-P2-243（2026-09-21）：子库装载链路要的落盘存储即**应用级** [FileBinaryStore] 单例。
     *
     * 该单例原仅以具体类被注入（`DatabaseModule.provideDatabaseSession`），DI 图中**并不存在**
     * `BinaryStore` 接口绑定；本别名绑定把同一 `@Singleton` 实例暴露在抽象类型下，
     * 使 `ChildDatabaseSessionManager` 依赖倒置到接口、宿主单测可注入内存替身。
     * **不产生第二个实例**（同 key 的 `@Singleton` 实例复用），也**不新增任何清理路径**：
     * 该单例早已自行注册为会话锁定观察者，并在 `MainApplication` 冷启动时清理。
     */
    @Binds
    @Singleton
    abstract fun bindBinaryStore(fileBinaryStore: FileBinaryStore): BinaryStore
}
