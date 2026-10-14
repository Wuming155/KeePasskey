package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.autofill.DuplicateEntryScanner
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ISSUE-P3-385 / P3-381 / P3-382 / P2-379：设置页与库 Meta / 探测 / 去重 / 前台闲置
 * 的写通道协作对象（自 [SettingsPreferencesController] 按单一职责拆出，行数门禁）。
 */
internal class SettingsDatabaseMetaController(
    private val settingsRepository: SettingsRepository,
    // ISSUE-P1-601：库 Meta 写入经统一提交边界（commitDatabaseMeta），不再直连 session.save
    private val vaultRepository: com.keepasskey.app.data.repository.VaultRepository,
    private val databaseSession: DatabaseSession?,
    private val scope: CoroutineScope
) {

    /**
     * ISSUE-P3-385：库级 Meta 编辑（库名 / 库描述 / 默认用户名）。
     * 与 PD-35（合并时 Meta 以本地为准）不冲突——编辑本地内存树，合并语义仍按 PD-35。
     *
     * ISSUE-P1-601：改写经 [VaultRepository.commitDatabaseMeta] 统一边界——
     * 漂移检查（fail-closed）→ 变换 → 落盘一并收口；命中外改即拒绝、不应用本意图。
     */
    fun setDatabaseMeta(
        databaseName: String?,
        databaseDescription: String?,
        defaultUserName: String?
    ) {
        val session = databaseSession ?: return
        scope.launch {
            // 无活动库 / 会话未就绪时如实 no-op（沿用既有语义；session 仅作就绪判据）
            session.databaseFlow.value ?: return@launch
            vaultRepository.commitDatabaseMeta { cur ->
                val now = Instant.now()
                cur.copy(
                    databaseName = databaseName ?: cur.databaseName,
                    databaseNameChanged = if (databaseName != null && databaseName != cur.databaseName) now else cur.databaseNameChanged,
                    databaseDescription = databaseDescription ?: cur.databaseDescription,
                    databaseDescriptionChanged = if (databaseDescription != null && databaseDescription != cur.databaseDescription) now else cur.databaseDescriptionChanged,
                    defaultUserName = defaultUserName ?: cur.defaultUserName,
                    defaultUserNameChanged = if (defaultUserName != null && defaultUserName != cur.defaultUserName) now else cur.defaultUserNameChanged
                )
            }
        }
    }

    /** ISSUE-P3-381：回前台远端探测开关写通道 */
    fun setSyncProbeOnResumeEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setSyncProbeOnResumeEnabled(enabled) }
    }

    /** ISSUE-P2-379：前台闲置自动锁定开关写通道 */
    fun setAutoLockForegroundEnabled(enabled: Boolean) {
        scope.launch { settingsRepository.setAutoLockForegroundEnabled(enabled) }
    }

    /** ISSUE-P2-379：前台闲置超时写通道 */
    fun setAutoLockForegroundTimeoutSeconds(seconds: Int) {
        scope.launch { settingsRepository.setAutoLockForegroundTimeoutSeconds(seconds) }
    }

    /** ISSUE-P3-382：库内重复条目只读报告 */
    fun scanDuplicateEntries(): List<DuplicateEntryScanner.DuplicateGroup> {
        val session = databaseSession ?: return emptyList()
        val db = session.databaseFlow.value ?: return emptyList()
        val entries = buildList {
            fun walk(g: KdbxGroup) {
                addAll(g.entries)
                g.subgroups.forEach { walk(it) }
            }
            walk(db.rootGroup)
        }
        return DuplicateEntryScanner.scan(entries)
    }

    /**
     * ISSUE-P3-409：重复条目扫描 → 健康检查明细投影（组/条目计数 + 可点击条目列表）。
     * 映射逻辑放在本控制器，避免 `SettingsViewModel` 因注入体膨胀触发 tier1 行数闸门。
     */
    fun scanDuplicateHealthIssues(
        strings: com.keepasskey.app.ui.model.StringsProvider
    ): SettingsHealthController.DuplicateScanResult {
        val groups = scanDuplicateEntries()
        val issues = groups.flatMap { group ->
            val othersInGroup = group.entries.size - 1
            group.entries.map { entry ->
                HealthIssueUi(
                    entryId = entry.id.toHexString(),
                    title = entry.title,
                    username = entry.userName,
                    risk = HealthIssueRiskUi.DUPLICATE,
                    description = strings.get(
                        com.keepasskey.app.R.string.health_issue_duplicate_desc,
                        othersInGroup
                    )
                )
            }
        }
        return SettingsHealthController.DuplicateScanResult(
            groupCount = groups.size,
            entryCount = issues.size,
            issues = issues
        )
    }
}
