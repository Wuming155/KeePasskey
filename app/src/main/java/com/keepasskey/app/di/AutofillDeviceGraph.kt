package com.keepasskey.app.di

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `ISSUE-P3-610`：设备侧（androidTest）用例读取**生产单例**的 Hilt 入口。
 *
 * ## 为何声明在 main 源集
 *
 * Hilt 的应用组件在**编译期**由 `@HiltAndroidApp` 生成；`@EntryPoint` 声明的入口若落在
 * androidTest 源集，则不在该组件的生成范围内——实测 `EntryPointAccessors.fromApplication`
 * 抛 `ClassCastException`（组件未实现该入口）。故入口必须落在 main。
 *
 * 暴露面**只含既有生产 API**（库列表 / 活动库选择 / 会话只读视图），不新增任何测试专用行为；
 * 用例侧只在 instrumented 代码中消费。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutofillDeviceGraph {
    fun vaultRepository(): VaultRepository
    fun databaseSession(): DatabaseSession

    /**
     * `ISSUE-P3-643`：全局脏表单登记表（生产 `@Singleton`）。
     *
     * 设备用例需要断言「编辑页 ViewModel 已登记 / 销毁后已注销」这一**跨组件联动**，
     * 而不只是「返回时弹了确认框」。取的是编辑页 ViewModel 注入的同一实例。
     */
    fun unsavedEditRegistry(): com.keepasskey.app.security.UnsavedEditRegistry

    /**
     * `ISSUE-P2-638`：恢复编排（生产 `@Singleton`）。
     *
     * 设备用例需要把 `attachmentBudgetBytes` 调到极小值，以便在真机上触发「附件超预算
     * ⇒ 无法逐字节确认差异」这条分支（造 >256 MiB 附件不可接受）。
     */
    fun vaultRecoveryCoordinator(): com.keepasskey.app.security.VaultRecoveryCoordinator

    fun baselineHolder(): com.keepasskey.app.security.VaultFileBaselineHolder
    fun driftCoordinator(): com.keepasskey.app.security.VaultFileDriftCoordinator
}
