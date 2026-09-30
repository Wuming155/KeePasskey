package com.keepasskey.app.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.keepasskey.app.autofill.legacy.LegacyAutofillAccessibilityService
import com.keepasskey.core.log.AppLog

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
 * `ISSUE-P2-405`（真机反馈后收口）：用户要求「点开关**立刻**跳无障碍权限页」。
 * 此前「优先详情页 + resolveActivity 前置门」在部分 ROM（MIUI）上表现为按钮在、点了没反应。
 * 现口径：
 * - **开关开启路径**：优先尝试本服务详情页（可跳到开关），失败立即回落**通用** `ACCESSIBILITY_SETTINGS`
 *   （必跳、不依赖详情页是否存在）；
 * - 两路启动均包 `FLAG_ACTIVITY_NEW_TASK`，避免非 Activity 上下文启动失败；
 * - 启动失败一律 `AppLog` 留痕（不吞成静默）；
 * - 清单 `<queries>` 已声明上述 Settings action，消除包可见性导致的解析/启动失败。
 *
 * 关于「预选本应用」：`ACTION_REQUEST_SET_AUTOFILL_SERVICE` 是官方文档（`AutofillService` 类说明）
 * 指定的启用入口，但**没有**公开 extra 可预设组件（`Settings` 的常量表中不存在此类 extra，
 * 本批初版曾按臆测的 `EXTRA_AUTOFILL_SERVICE_COMPONENT` 实现，编译期即被否决）。
 * 故此处只发 action，落在系统页由用户点选——**不得**用未公开 extra 冒充「已预选」。
 */
internal object SystemSettingsNavigation {

    private const val TAG = "SettingsNavigation"

    /** 自动填充服务设置页（官方 `AutofillService` 文档指定的启用入口）。 */
    fun autofillServiceIntent(context: Context): Intent? =
        Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(context.packageManager) != null }

    /**
     * 通用无障碍服务设置页。
     *
     * 返回**非空** Intent（不再以 `resolveActivity` 前置否决）：系统 `ACCESSIBILITY_SETTINGS`
     * 是公开 action，配合清单 `<queries>` 后应可启动；若个别 ROM 仍拒绝，由
     * [launchSafely] 在运行期失败并留日志，而不是在 UI 上直接藏掉入口。
     */
    fun accessibilityIntent(context: Context): Intent? =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 旧版无障碍自动填充服务的系统详情页（`ISSUE-P2-405`）。
     *
     * `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` + `android.settings.extra.COMPONENT_NAME`
     * （API 30+ 公开 action / extra 字面量；本仓 compileSdk SDK stub 未暴露同名 Kotlin
     * 常量，故以命名常量承载字符串字面量，与「内部键无公开常量」的先例同口径）。
     * 可把用户直接带到本应用无障碍服务开关；ROM 不响应该 action 时回落 [accessibilityIntent]。
     * **详情页仅作增强路径**：即使 `resolveActivity` 返回 null，调用方仍应走通用无障碍页（见 [openLegacyAccessibilitySettings]）。
     */
    fun accessibilityDetailsIntent(context: Context): Intent? {
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(
                EXTRA_COMPONENT_NAME,
                ComponentName(context, LegacyAutofillAccessibilityService::class.java)
            )
        }
        return details.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: accessibilityIntent(context)
    }

    /**
     * 打开旧版无障碍通道的系统设置落点（`ISSUE-P2-405`，用户目标行为）。
     *
     * **点开关 → 立刻跳系统无障碍权限页**：
     * 1. 若本服务详情页可解析，先试详情页（直达服务开关，体验更好）；
     * 2. 失败 / 不可解析 → **无条件**尝试通用 `ACCESSIBILITY_SETTINGS`（不依赖 resolve 门，
     *    必须跳出去）；
     * 3. 两路都失败返回 false（不崩、不留死链），但必有日志。
     */
    fun openLegacyAccessibilitySettings(context: Context): Boolean {
        val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(
                EXTRA_COMPONENT_NAME,
                ComponentName(context, LegacyAutofillAccessibilityService::class.java)
            )
        }
        if (details.resolveActivity(context.packageManager) != null) {
            if (launchSafely(context, details)) return true
            AppLog.w(TAG, "无障碍详情页启动失败，回落通用无障碍设置页")
        }
        val general = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launchSafely(context, general)
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
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(context.packageManager) != null }

    /**
     * 启动系统设置页；失败返回 false 并落日志（调用方据此降级，不得成为崩溃点）。
     * 一律追加 `FLAG_ACTIVITY_NEW_TASK`：Compose 回调 / 健康卡等路径的 Context 不保证是 Activity。
     */
    fun launchSafely(context: Context, intent: Intent): Boolean {
        val resolved = runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { t ->
            AppLog.w(TAG, "启动系统设置失败 action=${intent.action}", t)
            false
        }
        return resolved
    }

    /** `android.settings.ACCESSIBILITY_DETAILS_SETTINGS`（API 30+） */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** `android.settings.extra.COMPONENT_NAME`（API 30+） */
    private const val EXTRA_COMPONENT_NAME = "android.settings.extra.COMPONENT_NAME"
}
