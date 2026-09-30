package com.keepasskey.app.sync

/**
 * 全站强制 HTTPS（Wave 14 口径）的端点归一化单点。
 *
 * ISSUE-P2-399：原实现只存在于 `SettingsSyncController.normalizeHttpsEndpoint`（private），
 * 云端打开链路需要同一套「保存前归一化」语义，故抽出为本对象；控制器改为委托，
 * 行为零变化。两处消费方（同步配置保存、云端打开导入）不得再各写一份。
 */
object HttpsEndpointPolicy {

    /**
     * 端点归一化与校验。
     * 空串原样返回（允许清空配置）；无 scheme 输入自动补 https://；
     * 显式非 https scheme（http:// 等）返回 null 表示拒绝保存。
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return if (withScheme.startsWith("https://", ignoreCase = true)) withScheme else null
    }
}
