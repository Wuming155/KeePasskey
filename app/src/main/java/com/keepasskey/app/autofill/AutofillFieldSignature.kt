package com.keepasskey.app.autofill

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
 * 字段签名计算（ISSUE-P3-43 ②，抗枚举加固见 ISSUE-P3-46）。
 *
 * 签名把「包名 + 域 + 角色」压成一个**不可逆**的定长十六进制串，用于记住
 * 「用户说过：这个表单的这个框不要填」，而**不持久化任何明文表单内容或域名**。
 *
 * 构造：`HMAC-SHA256(key, SCHEMA_VERSION|"<包名>|<域>|<角色>")` 取前 [SIGNATURE_HEX_LENGTH]
 * 个 hex 字符（128 bit）。
 * - `key` 由 [HmacFieldSignatureSource] 提供：生产侧为 Android Keystore 内**不可导出**的
 *   `HmacSHA256` 密钥（见 [KeystoreHmacFieldSignatureSource]），单测侧注入测试密钥；
 *   使同一表单在不同设备上的签名互不相同，杜绝跨设备关联；
 * - 128 bit 截断对本用途足够：命中即「更保守地不填充」，碰撞的后果是多屏蔽而非多填充。
 *
 * **安全边界（如实声明，不夸大）**：本签名**不可逆**且**抗枚举**——密钥驻留 Keystore 且不可导出，
 * 即便攻击者取得应用私有目录中的签名集合，也无法离线枚举「包名 × 域 × 角色」候选来判定
 * 用户屏蔽过哪些站点。**边界之外**：若攻击者已能在**进程内**执行代码（或 root 后注入本进程），
 * 则可直接调用本签名函数做在线枚举——此时其本就可读取整个 KDBX 缓存与偏好明文，
 * 属威胁模型之外，本项不承诺防御。
 */
object AutofillFieldSignature {

    /** 签名十六进制长度（128 bit）。 */
    const val SIGNATURE_HEX_LENGTH = 32

    /**
     * 签名格式版本号：参与原文，变更判据（含密钥来源）时递增即可自然失效旧记录。
     *
     * ISSUE-P3-46：`v1`（`SHA-256(随机盐‖...)`，盐与签名同库落盘，**抗枚举不足**）
     * → `v2`（`HMAC-SHA256(Keystore 不可导出密钥‖...)`）。版本号变化使既有 v1 签名
     * 不再命中；同时由 [AutofillFieldBlocklistStore] 主动清除 v1 存量数据，
     * 消除「旧签名误命中新目标」的可能性。
     */
    const val SCHEMA_VERSION = "v2"

    private const val FIELD_SEPARATOR = "|"

    /**
     * 计算字段签名。
     *
     * @param source 密钥来源（生产为 Keystore HMAC；单测注入测试密钥）
     * @param packageName 调用应用包名（经 [AutofillPackageNames] 严格校验）
     * @param webDomain 表单自报的域；null / 空表示「纯 App 表单，无域」
     * @param role 字段角色
     * @return 定长小写 hex 签名；**包名非法或密钥不可用时返回 null**（调用方须按 fail-closed 处理）
     */
    fun of(
        source: HmacFieldSignatureSource,
        packageName: String,
        webDomain: String?,
        role: AutofillFieldRole
    ): String? {
        val normalizedPackage = AutofillPackageNames.normalize(packageName) ?: return null
        val normalizedDomain = normalizeDomain(webDomain)

        val canonical = listOf(SCHEMA_VERSION, normalizedPackage, normalizedDomain, role.wireName)
            .joinToString(FIELD_SEPARATOR)

        val mac = source.hmacSha256(canonical.toByteArray(Charsets.UTF_8))
        if (mac == null || mac.isEmpty()) return null
        return mac.toHexString().take(SIGNATURE_HEX_LENGTH)
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
