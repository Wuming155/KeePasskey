package com.keepasskey.app.data.importer

import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CancellationException

/**
 * 单次导入的落库执行体（从 [VaultImporter] 拆出，保持单一职责与文件行数阈值）。
 *
 * 冲突判定键、策略语义、回收站归位与擦除纪律见 [VaultImporter] 类 KDoc。
 */
internal class ImportPersistRun(
    private val repository: VaultRepository,
    private val resolver: GroupPathResolver,
    private val policy: ImportConflictPolicy,
    private val untitledLabel: String,
    private val warnings: ImportWarningCollector
) {

    private val known = mutableMapOf<EntryKey, UiVaultEntry>()

    var imported: Int = 0
        private set
    var updated: Int = 0
        private set
    var skipped: Int = 0
        private set
    var failed: Int = 0
        private set
    var movedToRecycleBin: Int = 0
        private set

    /** 首个落库异常的**类型名**（仅用于日志留痕，绝不记录异常消息以免夹带明文）。 */
    var firstFailureType: String? = null
        private set

    /** 以既有条目快照预热查重索引（含分组内既有条目，避免重复导入产生重复条目）。 */
    fun seed(existing: List<UiVaultEntry>) {
        existing.forEach { known[EntryKey(it.groupId, it.title.trim(), it.username.trim())] = it }
    }

    /**
     * 逐条落库。单条失败不中断整批（计入 [failed] 与警告），但协程取消必须原样上抛；
     * 每条用毕立即 [ImportedEntry.clear]（外层 `VaultImporter` 仍有全量兜底清零）。
     */
    suspend fun run(entries: List<ImportedEntry>) {
        entries.forEachIndexed { index, entry ->
            try {
                persistOne(index + 1, entry)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                recordFailure(index + 1, t)
            } finally {
                entry.clear()
            }
        }
    }

    private suspend fun persistOne(ordinal: Int, entry: ImportedEntry) {
        val resolution = resolver.resolve(entry.groupPath)
        if (resolution.creationFailed) {
            warnings.add(ImportWarningLocation.groupPath(entry.groupPath), ImportWarningReason.GROUP_CREATE_FAILED)
        }
        val title = entry.title.trim().ifEmpty { untitledLabel }
        val key = EntryKey(resolution.groupId, title, entry.username.trim())
        val existing = known[key]
        when {
            existing == null -> createNew(ordinal, key, resolution.groupId, title, entry)
            policy == ImportConflictPolicy.SKIP_EXISTING -> skipDuplicate(ordinal, entry)
            policy == ImportConflictPolicy.UPDATE_PASSWORD -> updateExistingPassword(ordinal, existing, entry)
            // CREATE_DUPLICATE：一律新建（同键既有条目不参与比对）
            else -> createNew(ordinal, key, resolution.groupId, title, entry)
        }
    }

    /** 默认策略：跳过并如实记录（不覆盖既有密码，报告显式给出 skipped 计数）。 */
    private fun skipDuplicate(ordinal: Int, entry: ImportedEntry) {
        skipped++
        warnings.add(ImportWarningLocation.entry(ordinal), ImportWarningReason.DUPLICATE_ENTRY_SKIPPED)
        // 分组路径比条目序号更有助于用户定位冲突，二者择一保留路径信息
        if (entry.groupPath.isNotEmpty()) {
            warnings.add(ImportWarningLocation.groupPath(entry.groupPath), ImportWarningReason.DUPLICATE_ENTRY_SKIPPED)
        }
    }

    /**
     * 覆盖既有条目密码：提交**既有条目投影**（仅密码字段变化，其余字段原样保留），
     * 密码以独占 `CharArray` 副本交给 `saveEntry`，由仓库的擦除契约清零。
     */
    private suspend fun updateExistingPassword(ordinal: Int, existing: UiVaultEntry, entry: ImportedEntry) {
        if (entry.password.isEmpty()) {
            // 源侧无密码可覆盖：不改动既有密码（覆盖为空等同于抹掉用户密码）
            skipDuplicate(ordinal, entry)
            return
        }
        val submission = entry.password.copyOf()
        val result = try {
            repository.saveEntry(existing, passwordChars = submission)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            recordFailure(ordinal, t)
            return
        } finally {
            submission.fill(NUL_CHAR)
        }
        if (result.isSuccess) updated++ else recordFailure(ordinal, null)
    }

    private suspend fun createNew(
        ordinal: Int,
        key: EntryKey,
        groupId: String?,
        title: String,
        entry: ImportedEntry
    ) {
        val uiEntry = UiVaultEntry(
            id = KdbxUuid.random().toHexString(),
            title = title,
            username = entry.username,
            url = entry.url,
            notes = entry.notes,
            groupId = groupId,
            category = EntryCategory.LOGIN
        )
        val passwordCopy = entry.password.copyOf()
        val totpCopy = entry.totpSecret?.copyOf()
        val saved = try {
            repository.saveEntry(uiEntry, passwordChars = passwordCopy, totpSecretChars = totpCopy)
        } finally {
            // 纵深防御：仓库契约已负责清零副本，此处再清一次，避免实现缺陷导致明文副本驻留
            passwordCopy.fill(NUL_CHAR)
            totpCopy?.fill(NUL_CHAR)
        }
        if (saved.isFailure) {
            recordFailure(ordinal, null)
            return
        }
        imported++
        known[key] = uiEntry
        if (entry.deleted) routeToRecycleBin(ordinal, uiEntry.id)
    }

    /**
     * 源数据中已删除的条目：先落库再走仓库既有的**软删除**语义（移入库内回收站），
     * 从而复用 `RecycleBinCoordinator` 的 `previousParentGroup` 与回收站懒创建逻辑，
     * 避免导入器自行实现回收站语义而绕开既有约束。
     */
    private suspend fun routeToRecycleBin(ordinal: Int, entryId: String) {
        val moved = try {
            repository.deleteEntry(entryId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            KdbxResult.Failure(t)
        }
        if (moved.isSuccess) movedToRecycleBin++ else recordFailure(ordinal, null)
    }

    private fun recordFailure(ordinal: Int, error: Throwable?) {
        failed++
        if (firstFailureType == null && error != null) firstFailureType = error.javaClass.simpleName
        warnings.add(ImportWarningLocation.entry(ordinal), ImportWarningReason.ENTRY_SAVE_FAILED)
    }

    /** 查重键：目标分组 + 标题 + 用户名（精确匹配，区分大小写）。 */
    private data class EntryKey(val groupId: String?, val title: String, val username: String)

    private companion object {
        const val NUL_CHAR = '\u0000'
    }
}
