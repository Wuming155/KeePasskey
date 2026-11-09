package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
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
 *    判据为 [PasswordStrength.isWeak]，**保留**接线前的两条规则（长度 < 8、命中常见口令表；
 *    长度按 Unicode 码点计，代理对算 1 个，与强度模型的 TOO_SHORT / 长度分档同一口径）
 *    并新增「强度分档 ≤ 1」，可识别 `qwertyuiop` / `abcabcabc` / `20260101` 等旧实现无感的口令；
 *    ISSUE-P3-394 起，口令含 `{REF:...}` 引用时先经 [FieldReferenceCharExpansion]
 *    （String 引擎的 Char 通道）多级展开为被引用条目的真实口令再评分——按引用原文评分失真
 *    （keepassxc `e888fec0` 同坑）；展开结果属敏感数据，只走 CharArray/ByteArray 且用毕
 *    显式清零，绝不物化 String、绝不进日志；
 * 2. 跨条目密码重复使用 (Reused Passwords) 检测——ISSUE-P3-166 起以摘要而非明文建索引，
 *    ENG-20261028-29 起该摘要按**生效口令**（即下述展开后的真实口令）计算：两趟共用同一口径，
 *    「直接值」与「指向同一口令的引用」归为同一重用，「引用原文相同但有效值不同」不再误判为重用；
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
     * ENG-20261028-29：哈希对象是**生效口令**（[effectivePasswordChars]，即 `{REF:...}` 展开后的
     * 真实口令），与第二趟的强度评估同口径——否则「直接值」与「引用同一口令的引用值」会被判为
     * 两个不同口令（漏报重用）。
     */
    private data class ReuseIndex(
        /** SHA-256(生效口令) 十六进制 → 出现次数 */
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
        // ISSUE-P3-394：{REF} 展开的检索根。本方法入参的既有契约即「全库扁平化」（唯一生产
        // 调用方 SettingsHealthController 经 getKdbxEntries() 取 rootGroup.allEntries()），
        // 以合成根承载展开通道的索引即可——通道只消费 root.allEntries()，文档序与扁平列表
        // 逐位一致；合成根仅存在于本次扫描的内存中，不落任何存储。索引在通道内部惰性构建：
        // 无引用的库零额外成本。两趟（重用哈希 / 强度评估）都以本根展开，检索结果必然一致。
        val referenceRoot = KdbxGroup(name = "", entries = entries)
        val index = buildReuseIndex(entries, referenceRoot)
        val issues = mutableListOf<EntryHealthIssue>()
        for (entry in entries) analyzeEntry(entry, index, issues, referenceRoot)
        return issues
    }

    /**
     * 条目的**生效口令**（ENG-20261028-29）：无口令字段、空口令，或 `{REF:...}` 展开后为空时返回
     * `null`——空口令语义是「本条目不需要密码」（ISSUE-P3-408），既不计弱口令也不计重用。
     *
     * 两趟必须共用本函数，口令口径才可能一致；展开只在口令含引用时发生（无引用时展开通道
     * 直返 `null`，零额外副本、零额外解密），因此代价仅落在引用条目上。
     *
     * @return 敏感 `CharArray`，**归调用方所有**，用毕必须显式清零
     */
    private fun effectivePasswordChars(entry: KdbxEntry, referenceRoot: KdbxGroup): CharArray? {
        val stored = entry.password ?: return null
        if (stored.length == 0) return null
        val rawChars = stored.readChars()
        val expanded = FieldReferenceCharExpansion.resolvePasswordFace(rawChars, referenceRoot)
            ?: return rawChars
        // 展开成功：原文副本即刻作废，只保留展开产物（两者都是敏感 Char）
        java.util.Arrays.fill(rawChars, '0')
        if (expanded.isEmpty()) {
            java.util.Arrays.fill(expanded, '0')
            return null
        }
        return expanded
    }

    /** 第一趟：按**生效口令**统计重用频率（字符与字节临时量用毕显式清零，不跨趟留存明文） */
    private fun buildReuseIndex(entries: List<KdbxEntry>, referenceRoot: KdbxGroup): ReuseIndex {
        val countByHash = mutableMapOf<String, Int>()
        val hashByEntryId = mutableMapOf<String, String>()
        for (entry in entries) {
            val passChars = effectivePasswordChars(entry, referenceRoot) ?: continue
            val passBytes = try {
                utf8BytesOf(passChars)
            } finally {
                java.util.Arrays.fill(passChars, '0')
            }
            try {
                val hashHex = com.keepasskey.crypto.hash.HashUtil.sha256(passBytes).toHexString()
                countByHash[hashHex] = (countByHash[hashHex] ?: 0) + 1
                hashByEntryId[entry.id.toHexString()] = hashHex
            } finally {
                java.util.Arrays.fill(passBytes, 0.toByte())
            }
        }
        return ReuseIndex(countByHash, hashByEntryId)
    }

    /**
     * 第二趟：逐条目产出「时效 / 弱口令 / 重用」问题。
     *
     * ISSUE-P3-408（用户裁决）：**空密码不计入弱密码**——空密码语义是「本条目不需要密码」
     * （通行密钥 / 资料条目等常见形态），不是弱密码。强度评估只针对已填写的口令；
     * 该豁免按 ENG-20261028-29 统一到**生效口令**口径：`{REF:...}` 展开为空同样视为无口令，
     * 与第一趟的重用计数逐字同判（两趟共用 [effectivePasswordChars]）。
     */
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

        // ISSUE-P3-394 / ENG-20261028-29：以**生效口令**评估——口令含 {REF:...} 时先经
        // FieldReferenceEngine 多级展开为被引用条目的真实口令（keepassxc e888fec0 同坑：按引用
        // 原文评分失真）。展开结果属敏感数据，只走 CharArray/ByteArray 且用毕显式清零，
        // 绝不物化 String、绝不进日志；未命中 / 超限引用由展开通道保守回退引用原文（与填充通道
        // resolve 的语义一致）。无口令 / 空口令 / 展开后为空 → 返回 null，本条目不参与弱口令与重用判定。
        val passChars = effectivePasswordChars(entry, referenceRoot) ?: return
        // 单条临时读取并在 finally 中擦除；字节数组由 CharArray 按 UTF-8 重编码而来
        // （模型侧内容均为合法 UTF-8 文本，往返无损），省去第二遍整段解密。
        val passBytes = utf8BytesOf(passChars)
        // ENG-20261028-07：文案里的「长度」与 crypto 强度模型的长度口径一致——**Unicode 码点**
        // （代理对算 1 个）。`CharArray.size` 是 UTF-16 码元数，会把 1 个 emoji 数成 2 个，
        // 导致「长度: 14」与同一行判出的 TOO_SHORT 自相矛盾。
        val passLength = Character.codePointCount(passChars, 0, passChars.size)
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

        // 检查多处复用（按生效口令的摘要查重）。
        // ISSUE-P3-166：直接复用第一趟已算出的摘要——两趟取口令都走 [effectivePasswordChars]，
        // 前置条件（有口令且生效口令非空）逐字相同，故凡进入本行的条目必然在第一趟留有摘要；
        // 原实现对同一份字节重算一遍 SHA-256。摘要本身不是明文，可跨趟留存。
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
