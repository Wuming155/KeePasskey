package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first

/**
 * **字段级**条目写入口共用的「按 id 定位既有条目」解析（`ISSUE-P3-442` 收敛）。
 *
 * 背景：收藏标记、HOTP 计数器推进、URL 回灌三个写入点此前各自照抄同一段
 * 「id 合法 → 库已解锁 → 条目存在」的三级解析（各约 12 行，逐字同形）；
 * 本函数把它收敛为唯一一份，**同时**是「三处错误语义必须一致」的保证
 * （此前任何一处漏改都会让同一类失败在不同入口给出不同文案）。
 *
 * 返回值语义：[KdbxResult.Success] 携带既有条目；[KdbxResult.Failure] 时由调用方
 * **原样返回**——`Failure : KdbxResult<Nothing>`，向上转型无信息损失。
 */
internal suspend fun resolveExistingEntryForFieldWrite(
    databaseSession: DatabaseSession,
    strings: StringsProvider,
    entryId: String
): KdbxResult<KdbxEntry> {
    val uuid = parseKdbxUuidOrNull(entryId)
        ?: return KdbxResult.Failure(
            IllegalArgumentException(strings.get(R.string.repo_invalid_entry_id)),
            strings.get(R.string.repo_entry_not_found)
        )
    val db = databaseSession.databaseFlow.first()
        ?: return KdbxResult.Failure(
            IllegalStateException(strings.get(R.string.repo_db_locked)),
            strings.get(R.string.repo_db_locked)
        )
    val entry = db.rootGroup.allEntries().firstOrNull { it.id == uuid }
        ?: return KdbxResult.Failure(
            IllegalArgumentException(strings.get(R.string.repo_entry_not_found)),
            strings.get(R.string.repo_entry_not_found)
        )
    return KdbxResult.Success(entry)
}
