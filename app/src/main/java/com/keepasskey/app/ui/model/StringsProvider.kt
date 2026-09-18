package com.keepasskey.app.ui.model

import androidx.annotation.StringRes

/**
 * TASK-21：ViewModel / 控制器层的字符串资源解析通道。
 * 生产环境由 `@ApplicationContext` 实现转发 `Context.getString`；
 * 单元测试环境（无 Android 资源）注入假实现按资源 ID 映射文案。
 * 用途：把非 Composable 层的用户可见文案统一收敛到 `strings.xml`（P3-23）。
 */
fun interface StringsProvider {
    fun get(@StringRes id: Int, vararg args: Any?): String
}

/**
 * 文案通道的**三级回退链**（`ISSUE-P3-188` §170 收敛）：已注入的通道优先 → 经 [context] 转发
 * `Context.getString` → 两者皆缺（纯 JVM 单测）时回退「恒返回空串」的静默实现。
 *
 * 收敛前 `EntryDetailViewModel` 与 `SettingsViewModel` 各写一份同样的三级回退；
 * 「静默空串」是**只在测试里成立**的行为，集中一处便于审计（生产 DI 恒注入真实现）。
 */
fun StringsProvider?.orFallback(context: android.content.Context?): StringsProvider = this
    ?: context?.let { ctx -> StringsProvider { id, args -> ctx.getString(id, *args) } }
    ?: StringsProvider { _, _ -> "" }
