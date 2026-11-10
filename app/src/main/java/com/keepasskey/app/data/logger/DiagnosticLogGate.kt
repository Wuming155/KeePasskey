package com.keepasskey.app.data.logger

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 诊断日志闸门（ISSUE-P3-03 43f）。
 *
 * 背景：设置页「诊断日志」开关（`ExtendedSettings.debugLogEnabled`）此前只完成持久化，
 * [DebugLogBuffer] 恒记录全部事件——开关是纯粹的假开关。本接口把「是否记录」这一决策
 * 从缓冲实现中抽出，使开关具备真实消费点，同时保持纯 JVM 单测可注入假闸门。
 *
 * 语义边界（与 ISSUE-P2-10 的冲突裁决）：
 * - **普通诊断事件**（info/warn/error/debug）受本闸门约束，关闭后不进入环形缓冲；
 * - **导出审计**（[DebugLogBuffer.audit]）不受约束——「导出治理」验收基线要求审计留痕
 *   必须可核验，不能因用户关闭诊断日志而静默丢失，故走独立通道。
 *
 * 稳定版同样记录：日志在写入时即经 [DiagnosticLogRedactor] 脱敏，且缓冲只驻留内存，
 * 故不再以构建类型禁用记录（原 release 恒关会让用户无法导出任何故障信息）。开关默认开启，
 * 用户可在设置页随时关闭；v/d 级 logcat 调试输出仍由 `AppLog.debugEnabled` 与 R8 独立门控。
 */
fun interface DiagnosticLogGate {
    /** true = 允许记录诊断事件 */
    fun isEnabled(): Boolean

    companion object {
        /** 恒开闸门：用于纯 JVM 单测与不接入偏好源的场景（保持既有日志可观测语义）。 */
        fun alwaysOn(): DiagnosticLogGate = DiagnosticLogGate { true }

        /** 恒关闸门：显式关闭记录。 */
        fun alwaysOff(): DiagnosticLogGate = DiagnosticLogGate { false }
    }
}

/**
 * 日志闸门的 Hilt 绑定（ISSUE-P3-03）。
 *
 * 绑定落在 `data/logger` 包内而非集中式 `di` 包：闸门是日志组件的自有适配器，
 * 与 [DebugLogBuffer] 同生命周期、同变化理由（单一职责 / 包即边界）。
 *
 * 注：`verboseSyncLog`（详细同步日志）不需要独立闸门——它是**同步编排器私有**的
 * 日志详细度，由 `SyncCoordinator` 直接从 [ExtendedSettingsStore] 单键读取即可，
 * 少一层间接依赖。
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticLogModule {

    /** 唯一消费点就是用户的诊断日志偏好；不区分构建类型，稳定版与调试版行为一致。 */
    @Provides
    @Singleton
    fun provideDiagnosticLogGate(store: ExtendedSettingsStore): DiagnosticLogGate =
        DiagnosticLogGate { store.isDiagnosticLogEnabled() }

    /**
     * §411 取证桥：生产 DebugLogBuffer 开启 logcat 直出（真机取证 `adb logcat -s KpLog`）。
     * 稳定版同样开启——输出的是写入时已脱敏的行，且 release 的 R8 只剥离 v/d，i 级保留。
     * 由 @Provides 承载（构造器拒绝 Boolean 依赖——与 KeyFileVaultCopyStore 的 Hilt 教训同源）；
     * 纯 JVM 单测的便捷构造不走此提供方法，echo 恒 false。
     */
    @Provides
    @Singleton
    fun provideDebugLogBuffer(gate: DiagnosticLogGate): DebugLogBuffer =
        DebugLogBuffer(gate).also { it.logcatEchoEnabled = true }
}
