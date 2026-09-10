package com.keepasskey.app.data.importer

import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ISSUE-P3-19 交付物 1.1：**落库编排器** —— 把 [EntryImporter.parse] 产出的 [ImportBatch]
 * 落进当前解锁中的明文库。
 *
 * ## 落库管线
 * 1. 防御性闸门：条目数上限、会话是否锁定（fail-closed，任一不过直接 [KdbxResult.Failure]）；
 * 2. 一次性快照 `getGroups()` / `getEntries()`，构建分组解析器与查重索引
 *    （快照 + 落库过程中增量维护，保证同一批次内部的重复也能被识别）；
 * 3. 逐条：分组路径查找或创建 → 冲突策略判定 → `VaultRepository.saveEntry(...)` 提交；
 * 4. 汇总为非敏感的 [ImportOutcome]；**逐条失败不静默**（计数 + 警告 + 日志留痕），
 *    全部条目均失败时整体返回 [KdbxResult.Failure]（绝不把全败伪装成成功）。
 *
 * ## 复用 saveEntry 的擦除契约
 * `VaultRepository.saveEntry` 的 KDoc 明确：实现方在任何结果路径（成功/失败/异常）用毕后
 * 清零传入的 `passwordChars` / `totpSecretChars` / `protectedFieldChars`，**调用方须传可被清零的副本**。
 * 因此本类：
 * - 每条 `ImportedEntry.password` 先 `copyOf()` 出**独占副本**再提交，绝不复用编辑态数组；
 * - 提交副本在 `finally` 中再清一次（纵深防御，防实现缺陷导致明文副本驻留）；
 * - [ImportedEntry] 自身的数组由 [persist] 的最外层 `finally` **无条件** `clear()`，
 *   覆盖成功、失败、提前返回与协程取消全部路径。
 *
 * ## 冲突策略
 * 见 [ImportConflictPolicy]：默认 [ImportConflictPolicy.SKIP_EXISTING]——同分组同名同用户名的
 * 既有条目一律**跳过**（计入 `skipped` + 警告），既不覆盖用户密码也不产生重复条目。
 *
 * ## 回收站语义
 * `ImportedEntry.deleted == true`（源数据中位于回收站子树的条目）先正常落库，再经仓库既有的
 * `deleteEntry` **软删除**语义移入库内回收站，从而复用 `RecycleBinCoordinator` 的
 * `previousParentGroup` 记录与回收站懒创建逻辑；计数记入 [ImportOutcome.movedToRecycleBin]。
 */
@Singleton
class VaultImporter @Inject constructor(
    private val repository: VaultRepository,
    private val strings: StringsProvider,
    private val debugLog: DebugLogBuffer
) {

    /**
     * 落库 [batch]；[policy] 缺省为 [ImportConflictPolicy.SKIP_EXISTING]。
     *
     * 无论成败，返回前本批次全部 [ImportedEntry] 的敏感数组都已清零。
     */
    suspend fun persist(
        batch: ImportBatch,
        policy: ImportConflictPolicy = ImportConflictPolicy.SKIP_EXISTING
    ): KdbxResult<ImportOutcome> {
        return try {
            withContext(Dispatchers.IO) { persistInternal(batch, policy) }
        } finally {
            // 擦除纪律：成功 / 失败 / 提前返回 / 协程取消，全路径清零
            batch.entries.forEach { it.clear() }
        }
    }

    private suspend fun persistInternal(
        batch: ImportBatch,
        policy: ImportConflictPolicy
    ): KdbxResult<ImportOutcome> {
        if (batch.entries.size > ImportLimits.MAX_ENTRIES_PER_IMPORT) {
            return ImportParseGuard.failure(ImportLimitExceededException(OVER_ENTRY_LIMIT), batch.entries)
        }
        if (repository.isLocked()) {
            return ImportParseGuard.failure(ImportVaultLockedException(VAULT_LOCKED), batch.entries)
        }
        val warnings = ImportWarningCollector()
        val resolver = GroupPathResolver(repository.getGroups().first()) { repository.saveGroup(it) }
        val run = ImportPersistRun(
            repository = repository,
            resolver = resolver,
            policy = policy,
            untitledLabel = strings.get(R.string.import_untitled_entry),
            warnings = warnings
        )
        run.seed(repository.getEntries().first())
        run.run(batch.entries)
        return buildResult(batch, run, warnings)
    }

    private fun buildResult(
        batch: ImportBatch,
        run: ImportPersistRun,
        warnings: ImportWarningCollector
    ): KdbxResult<ImportOutcome> {
        if (run.imported + run.updated == 0 && run.failed > 0) {
            // 整体失败：不留「部分成功」的错觉，向上显式返回 Failure
            debugLog.error(
                TAG,
                "导入整体失败: 失败=${run.failed}, 首个异常类型=${run.firstFailureType ?: NONE_LABEL}"
            )
            return KdbxResult.Failure(ImportPersistException(ALL_ENTRIES_FAILED))
        }
        val outcome = ImportOutcome(
            source = batch.report.source,
            parsed = batch.entries.size,
            sourceSkipped = batch.report.skipped,
            imported = run.imported,
            updated = run.updated,
            skipped = run.skipped,
            failed = run.failed,
            movedToRecycleBin = run.movedToRecycleBin,
            warnings = warnings.snapshot()
        )
        // 审计留痕（ISSUE-P2-10 同一治理口径）：只记计数与来源标识，绝不记录任何条目字段
        debugLog.audit(
            TAG,
            "导入审计: 源=${outcome.source.id}, 新增=${outcome.imported}, 更新=${outcome.updated}, " +
                "跳过=${outcome.skipped}, 失败=${outcome.failed}, 回收站=${outcome.movedToRecycleBin}"
        )
        return KdbxResult.Success(outcome)
    }

    private companion object {
        const val TAG = "VaultImporter"
        const val NONE_LABEL = "无"
        const val OVER_ENTRY_LIMIT = "导入条目数超出上限"
        const val VAULT_LOCKED = "密码库未解锁，无法导入"
        const val ALL_ENTRIES_FAILED = "全部条目写入密码库失败"
    }
}
