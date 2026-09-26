package com.keepasskey.app.autofill

import java.util.Locale

/**
 * Android 包名的归一化与严格校验（ISSUE-P3-43）。
 *
 * 单一来源：包级黑名单（`AutofillBlocklistStore`）、字段屏蔽目标键（[AutofillFieldSignature]）
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

    /**
     * 条目「关联具体应用」的绑定形式：`android://<包名>`。
     *
     * 这是包名维度放行判据（`DomainMatcher.extractAndroidBoundPackage` /
     * `isAndroidPackageMatch`）唯一认得的形态，**写入侧与判据侧必须逐字一致**——
     * 因此构造收敛到本处，避免各处各写一遍字面量后漂移成「写进去却匹配不上」。
     * 传入包名不做校验：调用方（应用选择器 / 凭据写入链）拿到的已是系统给出的包名。
     */
    fun boundUrl(packageName: String): String = "$BINDING_SCHEME://$packageName"

    /** `android://` 绑定的 scheme 字面量（见 [boundUrl]） */
    const val BINDING_SCHEME = "android"
}
