package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.BuildConfig

/**
 * 应用版本读数的**单一真相源**（ISSUE-P2-498）。
 *
 * 缺陷形态：设置主页「关于」行与「关于」页此前展示的是自初始化提交起、**从未接线**的静态虚构字面量
 * （`v1.0.0-Preview (2026 Edition)` / `Build 2026.09.04`），与真实来源
 * （`app/build.gradle.kts` 的 `versionName` / `versionCode`）脱节——「看似读真实版本、实为静态假读数」。
 *
 * 现统一取自 AGP 依 `defaultConfig` 生成的 [BuildConfig]；**不得**再写回字面量，
 * 该纪律由 `AppVersionWiringTest` 从投影输出与主源码两侧锁定。
 */
internal object AppVersionInfo {

    /** 设置主页「关于」行副标题与「关于」页版本行，形如 `v0.1.0`。 */
    val versionLabel: String = "v${BuildConfig.VERSION_NAME}"

    /** 「关于」页构建号行，形如 `Build 0.1.0 (1)`（版本名 + 版本代号）。 */
    val buildLabel: String = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}
