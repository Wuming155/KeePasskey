package com.keepasskey.app.autofill

import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.keepasskey.app.autofill.legacy.LegacyAutofillAccessibilityService
import com.keepasskey.core.log.AppLog

/**
 * 系统侧「本应用旧版无障碍服务」启用态读取（`ISSUE-P2-405`）。
 *
 * 供 [AutofillHealthProbe] 与设置页开关 resume 同步共用同一口径，避免两处判定漂移。
 *
 * 以公开 `AccessibilityManager.getEnabledAccessibilityServiceList` 读取，并与
 * [LegacyAutofillAccessibilityService] 的 `ComponentName` 精确比对。异常一律收敛为
 * **未启用**（不谎报正常），日志不携带窗口内容或凭据信息。
 */
internal object LegacyAccessibilitySystemState {

    private const val TAG = "LegacyA11ySystemState"

    /** `AccessibilityServiceInfo.FEEDBACK_ALL_MASK`（全部反馈类型） */
    private const val FEEDBACK_ALL_MASK = -1

    fun isEnabled(context: Context): Boolean = try {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(context, LegacyAutofillAccessibilityService::class.java)
        am.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)
            .any { info ->
                val ri = info.resolveInfo ?: return@any false
                val cn = ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name)
                cn == expected || cn.flattenToString() == expected.flattenToString()
            }
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取旧版无障碍服务系统启用态失败，按未启用处理", t)
        false
    }
}
