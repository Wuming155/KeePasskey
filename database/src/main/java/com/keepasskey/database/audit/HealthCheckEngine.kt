package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.crypto.strength.PasswordStrengthEvaluator
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
 * 2. 跨条目密码重复使用 (Reused Passwords) 检测；
 * 3. 密码时效性与过期检查。
 *
 * 选择原生内核的首要理由是**秘密治理**：口令以 UTF-8 字节直接进入原生侧受管缓冲，
 * JVM 侧不新增任何 `String` 物化路径（原生侧由 Rust `Zeroizing` 确定性擦除）。
 */
object HealthCheckEngine {

    /**
     * 对数据库全部条目执行健康安全扫描
     */
    fun analyzeEntries(entries: List<KdbxEntry>): List<EntryHealthIssue> {
        val issues = mutableListOf<EntryHealthIssue>()
        // P1-1 整改：使用 SHA-256 哈希值而非明文密码建立重用索引，绝不构建全库明文密码表
        val passwordHashCountMap = mutableMapOf<String, Int>()

        // 统计密码重用频率（基于哈希，用毕显式清零明文字节）
        for (entry in entries) {
            val passProtected = entry.password
            if (passProtected != null && passProtected.length > 0) {
                val passBytes = passProtected.readUtf8()
                try {
                    if (passBytes.isNotEmpty()) {
                        val hashHex = com.keepasskey.crypto.hash.HashUtil.sha256(passBytes).toHexString()
                        passwordHashCountMap[hashHex] = (passwordHashCountMap[hashHex] ?: 0) + 1
                    }
                } finally {
                    java.util.Arrays.fill(passBytes, 0.toByte())
                }
            }
        }

        for (entry in entries) {
            val id = entry.id.toHexString()

            // 密码时效性：条目声明了过期时间且已过期（文档承诺的 EXPIRED 风险等级真实落地）
            if (entry.times.expires && entry.times.expiryTime.isBefore(Instant.now())) {
                issues.add(
                    EntryHealthIssue(
                        entryId = id,
                        title = entry.title,
                        username = entry.userName,
                        riskLevel = PasswordRiskLevel.EXPIRED,
                        description = "该条目凭据已过期（${entry.times.expiryTime}），请更新密码或清除过期标记"
                    )
                )
            }

            val passProtected = entry.password
            if (passProtected == null || passProtected.length == 0) {
                issues.add(
                    EntryHealthIssue(
                        entryId = id,
                        title = entry.title,
                        username = entry.userName,
                        riskLevel = PasswordRiskLevel.WEAK,
                        description = "该条目未设置密码或密码为空"
                    )
                )
                continue
            }

            // 检查弱口令与长度（单条临时读取并在 finally 中擦除）
            val passChars = passProtected.readChars()
            val passBytes = passProtected.readUtf8()
            val passLength = passChars.size
            var isWeak = false
            var strengthScore = 0
            var hashHex = ""
            try {
                // ISSUE-P3-36：弱口令判定下沉至 crypto 强度引擎（原生优先，失败降级为字节级近似）。
                // 该引擎只读 UTF-8 字节，不构造 String，符合敏感数据铁律。
                val strength = PasswordStrengthEvaluator.evaluate(passBytes)
                isWeak = strength.isWeak
                strengthScore = strength.score
                hashHex = com.keepasskey.crypto.hash.HashUtil.sha256(passBytes).toHexString()
            } finally {
                java.util.Arrays.fill(passChars, '0')
                java.util.Arrays.fill(passBytes, 0.toByte())
            }

            if (isWeak) {
                issues.add(
                    EntryHealthIssue(
                        entryId = id,
                        title = entry.title,
                        username = entry.userName,
                        riskLevel = PasswordRiskLevel.WEAK,
                        description = "密码过弱（长度: ${passLength}，强度评分 ${strengthScore}/4，建议 ≥ 12 位且避免常见词与规律结构）"
                    )
                )
            }

            // 检查多处复用（按哈希查重）
            val reuseCount = passwordHashCountMap[hashHex] ?: 0
            if (reuseCount > 1) {
                issues.add(
                    EntryHealthIssue(
                        entryId = id,
                        title = entry.title,
                        username = entry.userName,
                        riskLevel = PasswordRiskLevel.REUSED,
                        description = "密码在 $reuseCount 个不同条目中被重复使用"
                    )
                )
            }
        }

        return issues
    }
}
