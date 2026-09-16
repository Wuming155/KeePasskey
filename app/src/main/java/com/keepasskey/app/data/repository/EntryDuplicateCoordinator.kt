package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * 条目克隆协调器（TASK-16，拆分自 RealVaultRepository 职责）。
 *
 * 克隆语义（对齐 KeePass 2.x Duplicate Entry）：
 * - 分配全新 [KdbxUuid]（否则会话层视为「更新」覆盖原条目）；
 * - 全字段保真复制：标准/自定义字段、受保护字段、TOTP 配置、附件引用、tags、
 *   AutoType 序列与 customData 全量随拷；
 * - 历史修订清空（克隆体是新生命周期的起点，不继承原条目 history）；
 * - 时间属性重置：creation/modification/access/locationChanged 全部取克隆时刻；
 * - 标题保持不变（KeePass 2.x 默认行为：克隆即逐字节保真，重命名交由用户后续编辑）。
 */
internal class EntryDuplicateCoordinator(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    /**
     * 克隆 [id] 指向的条目至同一分组。
     * 成功返回新条目的十六进制 UUID（KdbxResult.Success.data），供 UI 导航至克隆体。
     */
    suspend fun duplicateEntry(id: String): KdbxResult<String> {
        val uuid = parseKdbxUuidOrNull(id)
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
                strings.get(R.string.repo_entry_not_found)
            )
        val db = databaseSession.databaseFlow.first()
            ?: return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.repo_db_locked)),
                strings.get(R.string.repo_db_locked)
            )
        val source = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
            ?: return KdbxResult.Failure(
                IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
                strings.get(R.string.repo_entry_not_found)
            )
        if (databaseSession.isReadOnly) {
            return KdbxResult.Failure(
                IllegalStateException(strings.get(R.string.readonly_save_rejected)),
                strings.get(R.string.readonly_save_rejected)
            )
        }

        val clone = source.toFreshClone()
        databaseSession.saveEntry(clone)
        val persistResult = persistSession()
        return when (persistResult) {
            is KdbxResult.Success -> KdbxResult.Success(clone.id.toHexString())
            is KdbxResult.Failure -> persistResult
        }
    }
}

/** 克隆体构造：新 UUID + 重置时间属性 + 清空历史；其余字段全量保真 */
internal fun KdbxEntry.toFreshClone(now: Instant = Instant.now()): KdbxEntry = copy(
    id = KdbxUuid.random(),
    times = KdbxTimes(creationTime = now),
    history = emptyList()
)
