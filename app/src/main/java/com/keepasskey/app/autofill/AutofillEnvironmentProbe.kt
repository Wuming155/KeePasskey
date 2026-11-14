package com.keepasskey.app.autofill

import android.content.Context
import android.provider.Settings
import android.view.autofill.AutofillManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自动填充链路的环境快照，写进用户可导出的诊断日志。
 *
 * 用途：区分四类完全不同的「没弹窗」——系统未把本应用设为自动填充服务、系统已设但服务
 * 未被绑定、请求到了但表单目标未识别、请求到了且 SaveInfo 已注册但系统未展示保存 UI。
 * 只输出布尔、开关与装机时间，不含包名、条目、账号或任何凭据值。
 *
 * 装机时间用于核对「设备上跑的是不是刚打的包」（历史 §434 教训）：版本号恒为 0.1.0，
 * 只有 [android.content.pm.PackageInfo.lastUpdateTime] 能区分同一版本的不同构建。
 */
internal fun autofillEnvironmentLine(
    context: Context,
    autofillChannelEnabled: Boolean,
    offerSaveEnabled: Boolean
): String {
    val manager = context.getSystemService(AutofillManager::class.java)
    val configuredService = runCatching {
        Settings.Secure.getString(context.contentResolver, "autofill_service")
    }.getOrNull()
    val installedAt = runCatching {
        val lastUpdate = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(lastUpdate))
    }.getOrDefault("未知")

    return buildString {
        append("自动填充环境: 系统支持=").append(manager?.isAutofillSupported() == true)
        append(", 系统已启用=").append(manager?.isEnabled() == true)
        append(", 本应用为当前服务=").append(manager?.hasEnabledAutofillServices() == true)
        append(", 系统配置指向本应用=").append(configuredService?.startsWith(context.packageName) == true)
        append(", 应用内通道=").append(if (autofillChannelEnabled) "开" else "关")
        append(", 保存提示=").append(if (offerSaveEnabled) "开" else "关")
        append(", 装机时间=").append(installedAt)
    }
}
