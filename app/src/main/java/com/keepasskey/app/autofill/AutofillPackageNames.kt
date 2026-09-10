package com.keepasskey.app.autofill

import java.util.Locale

/**
 * Android 包名的归一化与严格校验（ISSUE-P3-43）。
 *
 * 单一来源：包级黑名单（`AutofillBlocklistStore`）、字段签名（[AutofillFieldSignature]）
 * 与保存侧黑名单（`AutofillSaveBlocklistStore`）**共用同一判据**，
 * 避免三处各写一份正则而随时间漂移——那会造成「A 处认为合法、B 处认为非法」的判定裂缝。
 *
 * 判据（与整改前 `AutofillBlocklistStore.normalize` 逐字一致）：
 * 小写化后要求 ≥2 段、每段字母开头、仅 `[a-z0-9_]`、总长 3..255。
 */
object AutofillPackageNames {

    /** "a.b" 为最短合法包名 */
    private const val MIN_PACKAGE_LENGTH = 3

    private const val MAX_PACKAGE_LENGTH = 255

    private val PACKAGE_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")

    /**
     * 归一化并校验包名。
     * @return 归一化后的包名；非法返回 null（调用方须按 fail-closed 处理，绝不静默放行）
     */
    fun normalize(packageName: String): String? {
        val trimmed = packageName.trim().lowercase(Locale.ROOT)
        if (trimmed.length !in MIN_PACKAGE_LENGTH..MAX_PACKAGE_LENGTH) return null
        return if (PACKAGE_PATTERN.matches(trimmed)) trimmed else null
    }
}
