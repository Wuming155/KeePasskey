package com.keepasskey.app.autofill

import android.content.Context
import android.content.pm.PackageManager

/**
 * 调用方 launcher 应用名取数（ISSUE-P3-372 AC④）。
 *
 * 供候选打分的「应用名相似」排序维度使用：由持有 [Context] 的调用点现取一次并下传
 * [AutofillCandidateRanker.rank]（排序器本体保持纯函数、零 Android 依赖）。
 * 任意异常（包不存在 / 查询失败）一律回落 null——取不到只损失一个排序维度，
 * 绝不因标签取数失败影响填充链路。
 */
internal fun Context.callerAppLabelOrNull(callingPackage: String): String? {
    if (callingPackage.isBlank()) return null
    return try {
        val appInfo = packageManager.getApplicationInfo(callingPackage, 0)
        packageManager.getApplicationLabel(appInfo)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
