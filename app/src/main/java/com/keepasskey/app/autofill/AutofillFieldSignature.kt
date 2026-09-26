package com.keepasskey.app.autofill

import java.util.Locale

/**
 * 表单字段角色（ISSUE-P3-43）。
 *
 * [wireName] 参与目标键原文，故**一经发布不得修改**——改动会使既有屏蔽记录全部失效。
 */
enum class AutofillFieldRole(val wireName: String) {
    /** 账号 / 用户名框 */
    USERNAME("u"),

    /** 密码框 */
    PASSWORD("p")
}

/**
 * 字段屏蔽目标键构造（ISSUE-P3-43 ②；ISSUE-P3-328 用户裁决去 Keystore 化）。
 *
 * 把「包名 + 域 + 角色」压成一个规范化目标键，用于记住
 * 「用户说过：这个表单的这个框不要填」。
 *
 * 构造：`"v3|<包名>|<域>|<角色>"` 规范化串本身即持久化键（**明文落盘**）。
 *
 * **安全边界（如实声明，2026-09-25 用户裁决改明文后的口径）**：目标键持久化于应用私有
 * `SharedPreferences`，**可读出用户屏蔽过的包名 / 域名**——该面与 `PD-36`（大附件明文落
 * 私有目录）的已接受边界同源：读取 prefs 需同 UID / root，而该对手同时可读 `filesDir` 下的
 * `.kdbx` 库文件本身，元数据不构成额外秘密面。原 v2 的「Keystore HMAC 抗枚举」加固随
 * `ISSUE-P3-328` 一并移除。**边界之外**：进程内代码执行属威胁模型之外，本项不承诺防御。
 */
object AutofillFieldSignature {

    /**
     * 目标键格式版本号：变更判据时递增即可自然失效旧记录。
     *
     * ISSUE-P3-46：`v1`（`SHA-256(随机盐‖...)`，盐与签名同库落盘）→ `v2`（Keystore HMAC）。
     * ISSUE-P3-328：`v2` → `v3`（明文目标键，去 Keystore 依赖）——hex 签名与明文键不同域，
     * 既有 v2 签名不可能命中新目标，但会长期占据计数，由 [AutofillFieldBlocklistStore]
     * 的 schema 迁移一次性清除（等价于屏蔽记录清空，不会误命中）。
     */
    const val SCHEMA_VERSION = "v3"

    private const val FIELD_SEPARATOR = "|"

    /**
     * 构造字段屏蔽目标键。
     *
     * @param packageName 调用应用包名（经 [AutofillPackageNames] 严格校验）
     * @param webDomain 表单自报的域；null / 空表示「纯 App 表单，无域」
     * @param role 字段角色
     * @return 规范化目标键；**包名非法时返回 null**（调用方按「视为已屏蔽」保守处理）
     */
    fun of(
        packageName: String,
        webDomain: String?,
        role: AutofillFieldRole
    ): String? {
        val normalizedPackage = AutofillPackageNames.normalize(packageName) ?: return null
        val normalizedDomain = normalizeDomain(webDomain)

        return listOf(SCHEMA_VERSION, normalizedPackage, normalizedDomain, role.wireName)
            .joinToString(FIELD_SEPARATOR)
    }

    /** 目标键是否为本版本格式（读取侧据此丢弃旧版本残留条目）。 */
    fun isWellFormedTarget(value: String): Boolean =
        value.startsWith("$SCHEMA_VERSION$FIELD_SEPARATOR")

    /**
     * 域归一化：小写、去首尾空白、去末尾根点。
     *
     * 刻意**不做** PSL 收敛（不把 `a.example.com` 归并到 `example.com`）——
     * 用户屏蔽的是「他当时看到的那个表单」，过度归并会把屏蔽意图放大到同一注册域下的其他页面。
     */
    private fun normalizeDomain(webDomain: String?): String =
        webDomain?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
}
