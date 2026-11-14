package com.keepasskey.app.autofill

import com.keepasskey.app.security.CallerCertDigests
import java.util.Locale

/**
 * KeePassDX 口径的「条目应用关联」自定义字段策略（纯 JVM，无 Android 依赖）。
 *
 * 数据契约照抄 `参考项目/KeePassDX/.../model/AppOriginEntryField.kt`：
 * - `AndroidApp` 放**纯包名**（明文，不带 `android://`）；
 * - `AndroidApp Signature` 放签名证书 SHA-256（受保护，冒号分隔大写 hex）；
 * - 第二组起用 `_1` / `_2` … 后缀，包名与签名**同编号成对**；
 * - 编号必须连续：解析从 0 起遍历，遇包名键缺失即停（KeePassDX `parseFields` 同款）。
 *
 * 与 `URL` 标准字段完全解耦：写入链不再动 URL，旧条目 `android://<包名>` 形态仍由
 * `DomainMatcher` 兼容读取，不迁移、不重写。询问与写入的产品口径见
 * `docs/architecture/产品裁决登记.md` 的「自动填充与通行密钥」段。
 *
 * 本对象只做键名、编号、签名格式与匹配归一；落库由 `VaultEntryAppBindingWriter` 承担，
 * 字段读取由消费侧按 `ProtectedString` 契约完成。
 */
object AndroidAppBindingFields {

    /** KeePassDX `APPLICATION_ID_FIELD_NAME` */
    const val APPLICATION_ID_FIELD_NAME = "AndroidApp"

    /** KeePassDX `APPLICATION_SIGNATURE_FIELD_NAME` */
    const val APPLICATION_SIGNATURE_FIELD_NAME = "AndroidApp Signature"

    /** KeePassDX 多签名分隔符；本仓写入单摘要，读取时按首段归一以兼容第三方库。 */
    private const val SIGNATURE_DELIMITER = "##SIG##"

    /** 一组关联：包名 + 可选签名摘要（null / 空白 = 条目未声明签名，KeePassDX 允许）。 */
    data class Binding(
        val packageName: String,
        val signatureHex: String?
    )

    /** 后缀规则与 KeePassDX `EntryInfo.suffixFieldNamePosition` 逐字一致：`position>0` 用 `_N`，`0` 无后缀。 */
    private fun suffix(position: Int): String = if (position > 0) "_$position" else ""

    /** 生成第 [position] 组的两个键（包名键, 签名键）。 */
    fun keysFor(position: Int): Pair<String, String> {
        val s = suffix(position)
        return "$APPLICATION_ID_FIELD_NAME$s" to "$APPLICATION_SIGNATURE_FIELD_NAME$s"
    }

    /**
     * 从 0 起连续解析，遇包名键缺失 / 空白即停。签名键缺失或空白记为 null。
     *
     * 只认严格键名，不会把 `AndroidApp_foo` 之类误当序列；与 KeePassDX 的
     * `generateSequence(0) { it + 1 }.takeWhile` 同形。
     */
    fun parse(fields: Map<String, String>): List<Binding> = buildList {
        var position = 0
        while (true) {
            val (appKey, signatureKey) = keysFor(position)
            val packageName = fields[appKey]?.trim()?.takeIf { it.isNotEmpty() } ?: break
            val signature = fields[signatureKey]?.trim()?.takeIf { it.isNotEmpty() }
            add(Binding(packageName, signature))
            position++
        }
    }

    /**
     * 该包名是否已绑定；已存在返回 null（不再写、不再问），否则返回第一个空位。
     *
     * [parse] 遇缺即停 ⇒ 已解析序列的长度就是第一个可用的连续空位，与 KeePassDX
     * `addUniqueField` 「按键名找第一个空位」同语义。
     */
    fun nextPosition(existing: List<Binding>, packageName: String): Int? {
        if (existing.any { it.packageName.equals(packageName, ignoreCase = true) }) return null
        return existing.size
    }

    /**
     * 签名归一：忽略大小写与冒号 / 空白 / 连字符，只保留大写 hex 字符；
     * 多签名形态按 KeePassDX 分隔符取首段。写入与比对共用本函数。
     */
    fun normalizeHex(value: String): String =
        value.substringBefore(SIGNATURE_DELIMITER)
            .uppercase(Locale.ROOT)
            .filter { it in '0'..'9' || it in 'A'..'F' }

    /**
     * 本仓 `CallerCertDigests` 的连续大写 hex → KeePassDX 冒号分隔大写 hex
     * （对齐 `Signature.signatureToSha256Fingerprint` 的 `"%02X".format` + `:` 拼接）。
     */
    fun toColonHex(hex: String): String {
        val normalized = normalizeHex(hex)
        if (normalized.isEmpty()) return ""
        return normalized.chunked(2).joinToString(":")
    }

    /**
     * 消费侧匹配：包名精确相等，且「条目未声明签名」或「调用方任一签名摘要归一后等于该签名」。
     *
     * 摘要集合的「任一命中」与 `CallerCertDigests` 的签名轮换期语义一致；
     * 调用方摘要不可读时 `anyMatch` 恒 false，有签名约束的绑定不得放行。
     * 本函数**不含** P2-46 首次绑定门——该门由调用方以 `packageDimensionAuthorized` 显式给出。
     */
    fun matches(
        bindings: List<Binding>,
        callingPackage: String,
        callerDigests: CallerCertDigests
    ): Boolean {
        val pkg = callingPackage.trim()
        if (pkg.isEmpty()) return false
        return bindings.any { binding ->
            if (!binding.packageName.equals(pkg, ignoreCase = true)) return@any false
            val signature = binding.signatureHex ?: return@any true
            callerDigests.anyMatch { normalizeHex(it) == normalizeHex(signature) }
        }
    }
}
