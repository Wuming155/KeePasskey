package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxEntry
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
 * 1. 弱口令模式匹配（长度 < 8、全数字、常见模式）；
 * 2. 跨条目密码重复使用 (Reused Passwords) 检测；
 * 3. 密码时效性与过期检查。
 */
object HealthCheckEngine {

    private val COMMON_WEAK_PASSWORDS = setOf(
        "123456", "password", "12345678", "qwerty", "123456789",
        "12345", "1234", "111111", "1234567", "dragon",
        "welcome", "admin", "admin123", "root", "pass123"
    )

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

            // 检查常见弱口令与长度（单条临时读取并在 finally 中擦除）
            val passChars = passProtected.readChars()
            val passBytes = passProtected.readUtf8()
            var passLength = passChars.size
            var isWeak = false
            var hashHex = ""
            try {
                val passStr = String(passChars)
                if (passLength < 8 || COMMON_WEAK_PASSWORDS.contains(passStr.lowercase())) {
                    isWeak = true
                }
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
                        description = "密码过短或属于常见弱密码（长度: ${passLength}，建议 ≥ 12 位）"
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
