package com.keepasskey.app.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.keepasskey.app.autofill.legacy.LegacyAutofillAccessibilityService

/**
 * 系统设置快捷入口（本批立规）。
 *
 * 背景：应用内有若干「待用户去**系统设置**里改一项」的排障提示——系统未把本应用选为自动填充服务、
 * 存在第三方无障碍服务、**系统未把本应用登记为凭据提供者**（`ISSUE-P2-239`）。此前这些提示**只有文字指引**
 * （「请前往系统设置启用」），用户须自行走完
 * 「设置 → 系统 → 语言和输入法 → 自动填充服务 → 选择 KeePasskey」（厂商 ROM 路径各异，通常 4~5 层），
 * 属「本可一次点击、却要用户自己找路」的典型。本对象把这段路收敛为**一次点击**。
 *
 * 三条硬约束（缺一即不得接线）：
 * 1. **可解析才给入口**：`resolveActivity` 为空时不返回 `Intent` ⇒ 调用方保留原纯文案，
 *    **绝不出现死链**（点了没反应比没有入口更糟）；
 * 2. **启动失败不崩**：[launchSafely] 一律吞掉异常并返回 `false`，由调用方降级——
 *    排障入口本身不得成为新的崩溃点；
 * 3. **不做反射 hack**：只用公开的 `Settings.ACTION_*`，不猜厂商私有页面。
 *
 * 关于「预选本应用」：`ACTION_REQUEST_SET_AUTOFILL_SERVICE` 是官方文档（`AutofillService` 类说明）
 * 指定的启用入口，但**没有**公开 extra 可预设组件（`Settings` 的常量表中不存在此类 extra，
 * 本批初版曾按臆测的 `EXTRA_AUTOFILL_SERVICE_COMPONENT` 实现，编译期即被否决）。
 * 故此处只发 action，落在系统页由用户点选——**不得**用未公开 extra 冒充「已预选」。
 */
internal object SystemSettingsNavigation {

    /** 自动填充服务设置页（官方 `AutofillService` 文档指定的启用入口）。 */
    fun autofillServiceIntent(context: Context): Intent? =
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .takeIf { it.resolveActivity(context.packageManager) != null }

    /** 无障碍服务设置页（通用列表；ISSUE-P2-405 的回落落点）。 */
    fun accessibilityIntent(context: Context): Intent? =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .takeIf { it.resolveActivity(context.packageManager) != null }

    /**
     * 旧版无障碍自动填充服务的系统详情页（`ISSUE-P2-405`）。
     *
     * `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` + `android.settings.extra.COMPONENT_NAME`
     * （API 30+ 公开 action / extra 字面量；本仓 compileSdk SDK stub 未暴露同名 Kotlin
     * 常量，故以命名常量承载字符串字面量，与「内部键无公开常量」的先例同口径）
     * 可把用户直接带到本应用无障碍服务开关；ROM 不响应该 action 时回落 [accessibilityIntent]。
     * 两者皆不可解析时返回 `null`，调用方静默降级（不得死链、不得伪造「已启用」）。
     */
    fun accessibilityDetailsIntent(context: Context): Intent? {
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            putExtra(
                EXTRA_COMPONENT_NAME,
                ComponentName(context, LegacyAutofillAccessibilityService::class.java)
            )
        }
        return details.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: accessibilityIntent(context)
    }

    /**
     * 打开旧版无障碍通道的系统设置落点（`ISSUE-P2-405`）。
     *
     * 供设置页开关「打开」路径调用：优先本服务详情页，回落通用无障碍设置页。
     * 不可解析 / 启动失败返回 false（调用方不强制依赖成功）。
     */
    fun openLegacyAccessibilitySettings(context: Context): Boolean {
        val intent = accessibilityDetailsIntent(context) ?: return false
        return launchSafely(context, intent)
    }

    /**
     * 凭据提供程序设置页（`ISSUE-P2-239`）。
     *
     * `Settings.ACTION_CREDENTIAL_PROVIDER`（`android.settings.CREDENTIAL_PROVIDER`）是 API 34+
     * 的**公开** SDK 常量（本仓 compileSdk 37 实测存在），用于承载「系统未登记本应用」的修复路径，
     * 与凭据提供者声明的 `settingsActivity` 落点不同（后者是 provider 自己的设置页）。
     *
     * 降级口径同上方三条：本机 ROM 不响应该 action 时 `resolveActivity` 为空 ⇒ 返回 `null`，
     * 调用方**只**保留纯文案指引并如实说明需自行前往系统设置，**不得**伪造「已开启」。
     */
    fun credentialProviderIntent(context: Context): Intent? =
        Intent(Settings.ACTION_CREDENTIAL_PROVIDER)
            .takeIf { it.resolveActivity(context.packageManager) != null }

    /** 启动系统设置页；无 Activity 处理 / 被 ROM 拦截一律返回 false（调用方据此降级为纯文案）。 */
    fun launchSafely(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent) }.isSuccess

    /** `android.settings.ACCESSIBILITY_DETAILS_SETTINGS`（API 30+） */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** `android.settings.extra.COMPONENT_NAME`（API 30+） */
    private const val EXTRA_COMPONENT_NAME = "android.settings.extra.COMPONENT_NAME"
}
