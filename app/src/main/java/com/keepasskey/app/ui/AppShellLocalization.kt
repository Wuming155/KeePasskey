package com.keepasskey.app.ui

import android.content.Context
import android.content.res.Configuration
import com.keepasskey.app.data.repository.AppLanguage
import com.keepasskey.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
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

/**
 * 非 Compose 装配点的本地化上下文快捷派生（`ISSUE-P3-390`：确认页 / 选择器 / TOTP 通知共用）——
 * `appLanguage` 在 DataStore，须挂起读取快照；返回上下文的 `getString` 即按应用内语言取文案。
 * 语言偏好为 SYSTEM 时返回原上下文（跟随系统），与主外壳口径一致。
 */
internal suspend fun localizedContextForAppLanguage(
    context: Context,
    settings: SettingsRepository
): Context = localizedContextOf(
    context,
    context.resources.configuration,
    localeFor(settings.getSettings().first().appLanguage)
)
