package com.keepasskey.app.ui.screens.settings

import com.keepasskey.database.audit.PasswordRiskLevel

/** 健康检查问题的 UI 侧风险分类（不把 `database` 枚举直接摊进设置页状态） */
enum class HealthIssueRiskUi {
    WEAK,
    REUSED,
    EXPIRED,
    /** 同 URL + 同账号的重复条目（ISSUE-P3-409，供清理导航；合并仍走编辑/删除管线） */
    DUPLICATE
}

/**
 * 健康检查「问题条目」UI 投影（ISSUE-P3-405 / P3-409）。
 *
 * 引擎侧 [com.keepasskey.database.audit.EntryHealthIssue] 与重复扫描明细都投影到本类型，
 * 使用户能知道**哪些**条目是弱密码 / 复用 / 过期 / 重复。
 * 只保留非敏感字段（标题 / 用户名 / 风险 / 原因），绝不携带口令本身。
 */
data class HealthIssueUi(
    val entryId: String,
    val title: String,
    val username: String,
    val risk: HealthIssueRiskUi,
    val description: String
)

/** 引擎风险等级 → UI 风险分类（SAFE 不会作为问题条目出现，此处保守映射为 WEAK） */
internal fun PasswordRiskLevel.toHealthIssueRiskUi(): HealthIssueRiskUi = when (this) {
    PasswordRiskLevel.REUSED -> HealthIssueRiskUi.REUSED
    PasswordRiskLevel.EXPIRED -> HealthIssueRiskUi.EXPIRED
    PasswordRiskLevel.WEAK,
    PasswordRiskLevel.SAFE -> HealthIssueRiskUi.WEAK
}
