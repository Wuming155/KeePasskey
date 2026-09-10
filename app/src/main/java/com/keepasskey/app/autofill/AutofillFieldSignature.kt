package com.keepasskey.app.autofill

import java.security.MessageDigest
import java.util.Locale

/**
 * 表单字段角色（ISSUE-P3-43）。
 *
 * [wireName] 参与签名原文，故**一经发布不得修改**——改动会使既有屏蔽记录全部失效。
 */
enum class AutofillFieldRole(val wireName: String) {
    /** 账号 / 用户名框 */
    USERNAME("u"),

    /** 密码框 */
    PASSWORD("p")
}

/**
 * 字段签名计算（ISSUE-P3-43 ②）。
 *
 * 签名把「包名 + 域 + 角色」压成一个**不可逆**的定长十六进制串，用于记住
 * 「用户说过：这个表单的这个框不要填」，而**不持久化任何明文表单内容或域名**。
 *
 * 构造：`SHA-256(salt || "v1|<包名>|<域>|<角色>")` 取前 [SIGNATURE_HEX_LENGTH] 个 hex 字符（128 bit）。
 * - `salt` 为**每次安装随机生成**并与签名同库持久化（由 [AutofillFieldBlocklistStore] 管理），
 *   使同一表单在不同设备上的签名互不相同，杜绝跨设备关联；
 * - 128 bit 截断对本用途足够：命中即「更保守地不填充」，碰撞的后果是多屏蔽而非多填充。
 *
 * **安全边界（如实声明，不夸大）**：本签名是**不可逆**的（无法从签名直接读出包名或域名），
 * 但**不具备抗枚举性**——若攻击者同时取得应用私有目录中的盐与签名集合，可对候选包名/域名逐一
 * 计算比对，从而判断用户是否屏蔽过某个特定站点。要抵抗该枚举需把盐换成 Keystore 内的
 * 不可导出密钥（HMAC），属后续加固项，已在 ACTIVE_ISSUES 登记。
 */
object AutofillFieldSignature {

    /** 签名十六进制长度（128 bit）。 */
    const val SIGNATURE_HEX_LENGTH = 32

    /** 签名格式版本号：参与原文，未来变更判据时递增即可自然失效旧记录。 */
    private const val SCHEMA_VERSION = "v1"

    private const val FIELD_SEPARATOR = "|"

    private const val DIGEST_ALGORITHM = "SHA-256"

    /**
     * 计算字段签名。
     *
     * @param salt 每安装随机盐（由存储层提供；空盐视为非法）
     * @param packageName 调用应用包名（经 [AutofillPackageNames] 严格校验）
     * @param webDomain 表单自报的域；null / 空表示「纯 App 表单，无域」
     * @return 定长小写 hex 签名；**包名非法或盐为空时返回 null**（调用方须按 fail-closed 处理）
     */
    fun of(salt: ByteArray, packageName: String, webDomain: String?, role: AutofillFieldRole): String? {
        if (salt.isEmpty()) return null
        val normalizedPackage = AutofillPackageNames.normalize(packageName) ?: return null
        val normalizedDomain = normalizeDomain(webDomain)

        val canonical = listOf(SCHEMA_VERSION, normalizedPackage, normalizedDomain, role.wireName)
            .joinToString(FIELD_SEPARATOR)

        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM).run {
            update(salt)
            digest(canonical.toByteArray(Charsets.UTF_8))
        }
        return digest.toHexString().take(SIGNATURE_HEX_LENGTH)
    }

    /**
     * 域归一化：小写、去首尾空白、去末尾根点。
     *
     * 刻意**不做** PSL 收敛（不把 `a.example.com` 归并到 `example.com`）——
     * 用户屏蔽的是「他当时看到的那个表单」，过度归并会把屏蔽意图放大到同一注册域下的其他页面。
     */
    private fun normalizeDomain(webDomain: String?): String =
        webDomain?.trim()?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()

    private fun ByteArray.toHexString(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
