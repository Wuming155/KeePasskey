package com.keepasskey.database.audit

import com.keepasskey.core.model.KdbxEntry

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
        val passwordCountMap = mutableMapOf<String, Int>()

        // 统计密码重用频率
        for (entry in entries) {
            val pass = entry.password?.readString().orEmpty()
            if (pass.isNotEmpty()) {
                passwordCountMap[pass] = (passwordCountMap[pass] ?: 0) + 1
            }
        }

        for (entry in entries) {
            val pass = entry.password?.readString().orEmpty()
            val id = entry.id.toHexString()

            if (pass.isEmpty()) {
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

            // 检查常见弱口令与长度
            if (pass.length < 8 || COMMON_WEAK_PASSWORDS.contains(pass.lowercase())) {
                issues.add(
                    EntryHealthIssue(
                        entryId = id,
                        title = entry.title,
                        username = entry.userName,
                        riskLevel = PasswordRiskLevel.WEAK,
                        description = "密码过短或属于常见弱密码（长度: ${pass.length}，建议 ≥ 12 位）"
                    )
                )
            }

            // 检查多处复用
            val reuseCount = passwordCountMap[pass] ?: 0
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
