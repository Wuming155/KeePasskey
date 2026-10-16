package com.keepasskey.app.di

import com.keepasskey.app.security.VaultFileDriftCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `ISSUE-P1-631`：设备侧（androidTest）用例读取**生产单例**漂移协调器的 Hilt 入口。
 *
 * 与 [AutofillDeviceGraph] 同因同据（声明在 main 源集的原因见该件 KDoc：`@EntryPoint`
 * 必须落在 `@HiltAndroidApp` 生成的应用组件范围内；暴露面只含既有生产 API）。
 * 用例借此断言 UI 实际消费的两个待决状态流（三选 / 合并裁决），而不是自建影子协调器。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriftDeviceGraph {
    fun vaultFileDriftCoordinator(): VaultFileDriftCoordinator
}
