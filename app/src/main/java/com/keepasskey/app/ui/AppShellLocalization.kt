package com.keepasskey.app.ui

import android.content.Context
import android.content.res.Configuration
import com.keepasskey.app.data.repository.AppLanguage
import java.util.Locale

/**
 * 外壳层语言 / 配置派生纯函数（`§185` 自 `KeePasskeyApp.kt` 平移的续拆：
 * `ISSUE-P3-359` AC④ 给外壳加装全局 Snackbar 宿主后母文件触及 tier2 预算，
 * 按行数分档闸门下沉——包未变，既有引用与测试的 import 零改动）。
 */

/** 语言偏好 → [Locale]；[AppLanguage.SYSTEM] 返回 null 表示「跟随系统、不改写配置」（§185 下沉，可 JVM 单测） */
internal fun localeFor(appLanguage: AppLanguage): Locale? = when (appLanguage) {
    AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.EN_US -> Locale.ENGLISH
    AppLanguage.SYSTEM -> null
}

/** 按 [locale] 改写 [base] 的语言与布局方向；`null` 时返回原样副本（§185：原先两处重复逻辑并为一处） */
internal fun localizedConfigurationOf(base: Configuration, locale: Locale?): Configuration {
    val cfg = Configuration(base)
    if (locale != null) {
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
    }
    return cfg
}

/** 供 `LocalContext` 使用的本地化上下文；`null` 语言时退回原上下文 */
internal fun localizedContextOf(context: Context, base: Configuration, locale: Locale?): Context =
    if (locale == null) {
        context
    } else {
        context.createConfigurationContext(localizedConfigurationOf(base, locale))
    }
