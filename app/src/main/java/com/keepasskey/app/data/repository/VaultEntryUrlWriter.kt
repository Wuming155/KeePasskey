package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import java.time.Instant

/**
 * 条目 `URL` 字段的**字段级**写入口（`ISSUE-P3-442` 搜索词自愈回灌）。
 *
 * 为什么独立成器而不是挂在 [VaultEntryWriteCoordinator] 上：
 * - 该协调器已逼近单文件规模闸门，新增方法的代价是「把大文件推向 tier1」，故按职责边界
 *   独立成器（与 `HotpAdvanceCoordinator` 等既有「一个写入面一件器」的形态一致）；
 * - 语义上它不是「条目保存」（不做整条重建、不记历史修订），与协调器内的合并保存是两件事。
 *
 * 硬约束（AC②）：
 * - **只改 `URL` 一个字段**——`Override URL`、`Notes`、自定义字段（含 `{REF:…}` 原文所在的
 *   任何字段）逐字保留；不走整条重建路径（那条路上 `URL` 会被整段替换、引用会丢失）；
 * - **不产生历史修订**（同 `setEntryFavorite` / `updateEntryOtpConfig`：字段级即时写）；
 * - 落盘仍走会话唯一出口 [persistSession]（与其余写入口同一原子写 / 备份链路）。
 */
internal class VaultEntryUrlWriter(
    private val strings: StringsProvider,
    private val databaseSession: DatabaseSession,
    private val persistSession: suspend () -> KdbxResult<Unit>
) {

    suspend fun updateUrl(entryId: String, url: String): KdbxResult<Unit> {
        val entry = when (
            val resolved = resolveExistingEntryForFieldWrite(databaseSession, strings, entryId)
        ) {
            is KdbxResult.Success -> resolved.data
            is KdbxResult.Failure -> return resolved
        }
        val updated = entry.copy(
            fields = entry.fields + (
                KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)
                ),
            times = entry.times.copy(lastModificationTime = Instant.now())
        )
        databaseSession.saveEntry(updated)
        return persistSession()
    }
}
