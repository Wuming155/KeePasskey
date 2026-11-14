package com.keepasskey.app.data.logger

/**
 * 诊断日志私有目录留存的周期档位（单一真相源）。
 *
 * 偏好默认值、Store 读写边界、设置页选项、归档裁剪与 `SettingsUiState` 首帧默认值
 * 全部消费同一组常量，避免多处硬编码 3 / 1 / 30 漂移。
 */
object DiagnosticLogRetention {
    const val DAYS_DEFAULT = 3
    const val DAYS_MIN = 1
    const val DAYS_MAX = 30
    val OPTIONS: List<Int> = listOf(1, 3, 7, 30)
}
