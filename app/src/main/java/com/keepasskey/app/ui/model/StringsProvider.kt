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
