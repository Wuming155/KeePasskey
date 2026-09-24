package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * TOTP 解析参数快照（**ISSUE-P3-273**）。
 *
 * 缺陷背景：用户在「双重认证 (TOTP) 设置」页配置的种子字段名 / 设置字段名 / 默认刷新周期 /
 * 默认验证码位数**从未参与解析**——解析侧写死官方 `otp` 字段与 `TOTP` 前缀，缺省参数写死
 * `30` / `6`，且 `updateTotpFieldMapping` 只改内存快照不落盘（重启回默认）。
 *
 * 本快照即「设置 → 解析侧」的**唯一传递载体**：由 [TotpPreferencesSource] 现读，
 * 逐次传入 `VaultEntryTotpMapping.parseTotpConfig` / `locateConfigSource`。
 *
 * **缺省值不另立常量**——一律经 [of] 从 [ExtendedSettings] 取，杜绝
 * 「UI 默认值 ⇄ 解析侧默认值」两处漂移（`ISSUE-P2-43` 的同型教训）。
 */
data class TotpPreferences(
    /** 条目 TOTP **种子**字段名（设置值优先；未命中回退官方 `otp` 与 `TOTP` 前缀） */
    val seedFieldName: String,
    /** 条目 TOTP **设置**字段名（同一优先序的第二候选） */
    val settingsFieldName: String,
    /** 刷新周期缺省值（仅当条目自身未声明 `period` 时生效，秒） */
    val defaultStepSeconds: Int,
    /** 验证码位数缺省值（仅当条目自身未声明 `digits` 时生效，仍受 `6..8` 钳制） */
    val defaultDigits: Int
) {
    companion object {
        /** 从进阶偏好快照取值（生产路径；缺省值随 [ExtendedSettings] 同源） */
        fun of(settings: ExtendedSettings): TotpPreferences = TotpPreferences(
            seedFieldName = settings.totpSeedFieldName,
            settingsFieldName = settings.totpSettingsFieldName,
            defaultStepSeconds = settings.defaultTotpStepSeconds,
            defaultDigits = settings.defaultTotpDigits
        )

        /**
         * 无偏好层（纯 JVM 单测 / 未注入通道）时的回落快照。
         * 与「用户从未打开过 TOTP 设置页」的生产语义一致。
         */
        val DEFAULT: TotpPreferences = of(ExtendedSettings())
    }
}

/**
 * TOTP 解析参数读取通道（**ISSUE-P3-273**）。
 *
 * 数据层（`VaultEntryMapper` / `VaultEntrySecretReader`）依赖本函数式接口而非直接依赖
 * `ExtendedSettingsStore`：单测注入固定快照，使「设置值真实参与解析」可被确定性断言。
 */
fun interface TotpPreferencesSource {
    fun read(): TotpPreferences
}

/**
 * 生产绑定：读 `ExtendedSettingsStore` 的**进程级内存权威快照**（`settings.value`）。
 *
 * 刻意不用 `store.load()`：后者每次都重新反序列化 SharedPreferences 全字段，而本通道位于
 * TOTP 热路径（列表页逐条目投影 + 每秒取码）。快照经 `publish()` 与全部 setter 同源更新，
 * 与落盘值一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object TotpPreferencesModule {

    @Provides
    @Singleton
    fun provideTotpPreferencesSource(store: ExtendedSettingsStore): TotpPreferencesSource =
        TotpPreferencesSource { TotpPreferences.of(store.settings.value) }
}
