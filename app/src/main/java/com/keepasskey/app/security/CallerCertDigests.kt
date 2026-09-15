package com.keepasskey.app.security

import java.util.Locale

/**
 * ISSUE-P3-93（审计 F-19）：调用方**签名证书摘要集合**（SHA-256，大写、无冒号）。
 *
 * ## 缺陷形态
 *
 * 此前各处（自动填充与通行密钥两条链）都只取
 * `signingInfo.apkContentsSigners?.firstOrNull()` 的**单个**摘要。签名轮换期（或应用本就由多个
 * 签名者签署时）系统会同时提供多个签名者：`apkContentsSigners` 为**当前有效**签名者集合、
 * `signingCertificateHistory` 为**历史**签名者集合。只取首个的结果**随系统返回顺序变化**——
 * 白名单 / DAL / 信任记录都可能因「恰好没取到那一个」而误判未授权。
 * 方向为 **fail-closed**（表现为签名轮换期的可用性缺陷，而非放行面放松），故定级 P3。
 *
 * ## 本类型语义
 *
 * **任一摘要命中即通过**——与 Android「一个应用可由多个签名者之一签署」的模型一致。
 * 元素顺序：`apkContentsSigners` 在前（当前有效），`signingCertificateHistory` 在后（历史），
 * 归一化为大写、去重、丢弃空白项；[primary] 作为**展示/记录**用途的主摘要。
 */
@JvmInline
value class CallerCertDigests(val values: List<String>) {

    /**
     * 主摘要（**仅**用于展示与信任记录写入）：当前有效签名者的首个摘要。
     * 无可读摘要（包不可见 / 读取失败）时为 null——展示侧须如实标注「不可读」，不得伪造。
     */
    val primary: String? get() = values.firstOrNull()

    val isEmpty: Boolean get() = values.isEmpty()

    /** 是否存在任一摘要满足 [predicate]（放行判定一律走本方法，不得只看 [primary]） */
    fun anyMatch(predicate: (String) -> Boolean): Boolean = values.any(predicate)

    companion object {
        val EMPTY = CallerCertDigests(emptyList())

        /** 由原始摘要序列构造：归一化（trim + 大写 + 去空 + 去重），保序。 */
        fun of(digests: Iterable<String?>): CallerCertDigests = CallerCertDigests(
            digests.mapNotNull { it?.trim()?.takeIf { d -> d.isNotEmpty() } }
                .map { it.uppercase(Locale.ROOT) }
                .distinct()
        )

        /** 单摘要便捷构造（兼容既有「单摘要」调用点与测试） */
        fun ofSingle(digest: String?): CallerCertDigests =
            if (digest == null) EMPTY else of(listOf(digest))
    }
}
