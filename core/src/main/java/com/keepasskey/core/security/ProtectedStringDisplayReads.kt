package com.keepasskey.core.security

/**
 * `ProtectedString?` 的**展示面**读取（`ISSUE-P2-534`）：`null` 与「已清零」同义，均降级为 [fallback]。
 *
 * 存在理由：条目里的可空字段（自定义字段值、可选标准字段）在展示 / 检索 / 差异面上大量以
 * `field?.readString().orEmpty()` 的形态出现——`?.` 只挡 `null`，挡不住「实例存在但已被并发擦除」。
 * 本扩展把两种「不可读」收敛成同一个降级出口，调用方无须逐点写 `takeUnless { it.cleared }`。
 *
 * 使用边界与 [ProtectedString.readStringForDisplay] **逐字相同**：仅限非持久化的展示 / 检索 / 差异面；
 * 写路径、凭据下发、`{REF:}` 取值面必须继续走 fail-fast 或 `cleared` 预判。
 */
fun ProtectedString?.readStringForDisplayOrEmpty(fallback: String = ""): String =
    this?.readStringForDisplay(fallback) ?: fallback

/**
 * `ProtectedString?` 的**展示面 CharArray 读取**（`ISSUE-P2-534`）：`null` 与「已清零 / 正在被
 * 并发擦除」同义，一律返回 `null`（＝「不可读」，与 [ProtectedString.readChars] 的 fail-fast 形态区分）。
 *
 * 返回值是**独占副本**，调用方用毕必须在 `finally` 中清零（借用契约与 [ProtectedString.readChars] 相同）。
 * 使用边界同 [ProtectedString.readStringForDisplay]：仅限非持久化的展示 / 差异消费面
 * （典型调用点：冲突裁决页的「掩码 + 长度 + 字符形态」线索）。
 */
fun ProtectedString?.readCharsForDisplayOrNull(): CharArray? =
    try {
        this?.readChars()
    } catch (_: IllegalStateException) {
        null
    }
