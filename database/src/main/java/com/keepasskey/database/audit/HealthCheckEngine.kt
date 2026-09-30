package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.crypto.strength.PasswordStrengthEvaluator
import com.keepasskey.database.fieldref.FieldReferenceCharExpansion
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * 密码安全审计健康状态
 */
enum class PasswordRiskLevel {
    SAFE,
    WEAK,
    REUSED,
    EXPIRED
}

/**
 * 条目健康检查问题详情
 */
data class EntryHealthIssue(
    val entryId: String,
    val title: String,
    val username: String,
    val riskLevel: PasswordRiskLevel,
    val description: String
)

/**
 * 离线密码健康检查与安全审计引擎。
 * 遵循本地优先原则，绝不向任何外部网络上传明文或哈希前缀：
 * 1. **弱口令判定**——ISSUE-P3-36 起改由 `crypto` 的 [PasswordStrengthEvaluator] 承担
 *    （原生 Rust 内核，模式惩罚型模型；原生不可用时降级为 `PasswordStrengthFallback`）。
 *    判据为 [PasswordStrength.isWeak]，**保留**接线前的两条规则（长度 < 8、命中常见口令表）
 *    并新增「强度分档 ≤ 1」，可识别 `qwertyuiop` / `abcabcabc` / `20260101` 等旧实现无感的口令；
 *    ISSUE-P3-394 起，口令含 `{REF:...}` 引用时先经 [FieldReferenceCharExpansion]
 *    （String 引擎的 Char 通道）多级展开为被引用条目的真实口令再评分——按引用原文评分失真
 *    （keepassxc `e888fec0` 同坑）；展开结果属敏感数据，只走 CharArray/ByteArray 且用毕
 *    显式清零，绝不物化 String、绝不进日志；
 * 2. 跨条目密码重复使用 (Reused Passwords) 检测；
 * 3. 密码时效性与过期检查。
 *
 * 选择原生内核的首要理由是**秘密治理**：口令以 UTF-8 字节直接进入原生侧受管缓冲，
 * JVM 侧不新增任何 `String` 物化路径（原生侧由 Rust `Zeroizing` 确定性擦除）。
 */
object HealthCheckEngine {

    /** 强度评分满分（`crypto` 强度引擎的 0..4 分档上界） */
    private const val MAX_STRENGTH_SCORE = 4

    /**
     * 密码重用索引（ISSUE-P3-166：哈希一次算成、两趟共用）。
     *
     * P1-1 整改：以 SHA-256 哈希值而非明文密码建立索引，绝不构建全库明文密码表。
     */
    private data class ReuseIndex(
        /** SHA-256(密码) 十六进制 → 出现次数 */
        val countByHash: Map<String, Int>,
        /**
         * 条目 id → 本趟已算出的哈希，供第二趟**复用**——原实现第二趟对同一条目重新
         * `readUtf8()`（一次完整解密）并重算一遍 SHA-256，等于「同一条口令在一次扫描里
         * 被解密 3 次、哈希 2 次」。
         */
        val hashByEntryId: Map<String, String>
    )

    /**
     * 对数据库全部条目执行健康安全扫描
     */
    fun analyzeEntries(entries: List<KdbxEntry>): List<EntryHealthIssue> {
        val index = buildReuseIndex(entries)
        // ISSUE-P3-394：{REF} 展开的检索根。本方法入参的既有契约即「全库扁平化」（唯一生产
        // 调用方 SettingsHealthController 经 getKdbxEntries() 取 rootGroup.allEntries()），
        // 以合成根承载展开通道的索引即可——通道只消费 root.allEntries()，文档序与扁平列表
        // 逐位一致；合成根仅存在于本次扫描的内存中，不落任何存储。索引在通道内部惰性构建：
        // 无引用的库零额外成本。
        val referenceRoot = KdbxGroup(name = "", entries = entries)
        val issues = mutableListOf<EntryHealthIssue>()
        for (entry in entries) analyzeEntry(entry, index, issues, referenceRoot)
        return issues
    }

    /** 第一趟：统计密码重用频率（明文字节用毕显式清零） */
    private fun buildReuseIndex(entries: List<KdbxEntry>): ReuseIndex {
        val countByHash = mutableMapOf<String, Int>()
        val hashByEntryId = mutableMapOf<String, String>()
        for (entry in entries) {
            val passProtected = entry.password
            if (passProtected != null && passProtected.length > 0) {
                val passBytes = passProtected.readUtf8()
                try {
                    if (passBytes.isNotEmpty()) {
                        val hashHex = com.keepasskey.crypto.hash.HashUtil.sha256(passBytes).toHexString()
                        countByHash[hashHex] = (countByHash[hashHex] ?: 0) + 1
                        hashByEntryId[entry.id.toHexString()] = hashHex
                    }
                } finally {
                    java.util.Arrays.fill(passBytes, 0.toByte())
                }
            }
        }
        return ReuseIndex(countByHash, hashByEntryId)
    }

    /** 第二趟：逐条目产出「时效 / 空密码 / 弱口令 / 重用」四类问题 */
    private fun analyzeEntry(
        entry: KdbxEntry,
        index: ReuseIndex,
        issues: MutableList<EntryHealthIssue>,
        referenceRoot: KdbxGroup
    ) {
        // 密码时效性：条目声明了过期时间且已过期（文档承诺的 EXPIRED 风险等级真实落地）
        if (entry.times.expires && entry.times.expiryTime.isBefore(Instant.now())) {
            issues.add(
                issueOf(
                    entry,
                    PasswordRiskLevel.EXPIRED,
                    "该条目凭据已过期（${entry.times.expiryTime}），请更新密码或清除过期标记"
                )
            )
        }

        val passProtected = entry.password
        if (passProtected == null || passProtected.length == 0) {
            // ISSUE-P3-407：通行密钥条目本无传统密码（WebAuthn 私钥走自定义字段），
            // 空密码是正常形态，不得再判为弱密码误报。形状短路只扫 schema 键名，
            // 不读取/解密字段值，符合敏感数据铁律与 ISSUE-P3-171 的零解密口径。
            if (!hasPasskeySchemaFields(entry)) {
                issues.add(issueOf(entry, PasswordRiskLevel.WEAK, "该条目未设置密码或密码为空"))
            }
            return
        }

        // ISSUE-P3-394：口令含 {REF:...} 引用时先经 FieldReferenceEngine 多级展开再评估
        // （keepassxc e888fec0 同坑：按引用原文评分失真）。展开结果是被引用条目的真实口令——
        // 敏感数据铁律：只走 CharArray/ByteArray 且用毕显式清零，绝不物化 String、绝不进日志；
        // 未命中 / 超限引用由展开通道保守回退引用原文（与填充通道 resolve 的语义一致）。
        // 无引用时展开入口返回 null，直接以本次读出的原文评估（零额外副本、零额外解密）。
        val rawChars = passProtected.readChars()
        val expanded = FieldReferenceCharExpansion.resolvePasswordFace(rawChars, referenceRoot)
        if (expanded != null) java.util.Arrays.fill(rawChars, '0')
        val passChars = expanded ?: rawChars
        // 单条临时读取并在 finally 中擦除；字节数组由 CharArray 按 UTF-8 重编码而来
        // （模型侧内容均为合法 UTF-8 文本，往返无损），省去第二遍整段解密。
        val passBytes = utf8BytesOf(passChars)
        val passLength = passChars.size
        var isWeak = false
        var strengthScore = 0
        try {
            // ISSUE-P3-36：弱口令判定下沉至 crypto 强度引擎（原生优先，失败降级为字节级近似）。
            // 该引擎只读 UTF-8 字节，不构造 String，符合敏感数据铁律。
            val strength = PasswordStrengthEvaluator.evaluate(passBytes)
            isWeak = strength.isWeak
            strengthScore = strength.score
        } finally {
            java.util.Arrays.fill(passChars, '0')
            java.util.Arrays.fill(passBytes, 0.toByte())
        }

        if (isWeak) {
            issues.add(
                issueOf(
                    entry,
                    PasswordRiskLevel.WEAK,
                    "密码过弱（长度: $passLength，强度评分 $strengthScore/$MAX_STRENGTH_SCORE，" +
                        "建议 ≥ 12 位且避免常见词与规律结构）"
                )
            )
        }

        // 检查多处复用（按哈希查重）。
        // ISSUE-P3-166：直接复用第一趟已算出的哈希——两趟「取哈希」的前置条件（长度非 0 且
        // 字节非空）逐字相同，故此处必然命中；原实现对同一份字节重算一遍 SHA-256。
        val hashHex = index.hashByEntryId[entry.id.toHexString()].orEmpty()
        val reuseCount = index.countByHash[hashHex] ?: 0
        if (reuseCount > 1) {
            issues.add(
                issueOf(
                    entry,
                    PasswordRiskLevel.REUSED,
                    "密码在 $reuseCount 个不同条目中被重复使用"
                )
            )
        }
    }

    /**
     * 形状短路：条目自定义字段是否携带 passkey schema 键（KPEX / v1 / 本仓扩展）。
     *
     * ISSUE-P3-407：只扫键名、不物化值——绝大多数普通条目无 passkey 字段，零额外解密成本；
     * 判定口径与 UI 侧 `isPasskey` 同源（`PasskeyData.isPasskeyFieldKey`）。
     * 不完整 schema（例如仅 RP 尚未写入私钥）也视作 passkey 相关条目，
     * 空密码不判弱，避免半成品通行密钥被健康检查误报。
     */
    private fun hasPasskeySchemaFields(entry: KdbxEntry): Boolean =
        entry.customFields.any { field -> PasskeyData.isPasskeyFieldKey(field.key) }

    /**
     * CharArray → UTF-8 字节（敏感中间量不落 String，手法同 `ProtectedString` 的自有编码通道）：
     * 编码器内部缓冲承载过明文，一并清零；返回的字节副本由调用方负责清零。
     */
    private fun utf8BytesOf(chars: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        try {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return bytes
        } finally {
            if (buffer.hasArray()) java.util.Arrays.fill(buffer.array(), 0.toByte())
        }
    }

    /** 以条目自身标识 + 风险等级 + 描述装配问题项（四路共用，消除重复构造） */
    private fun issueOf(
        entry: KdbxEntry,
        riskLevel: PasswordRiskLevel,
        description: String
    ): EntryHealthIssue = EntryHealthIssue(
        entryId = entry.id.toHexString(),
        title = entry.title,
        username = entry.userName,
        riskLevel = riskLevel,
        description = description
    )
}
