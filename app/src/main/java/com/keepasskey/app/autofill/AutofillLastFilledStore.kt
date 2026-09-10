package com.keepasskey.app.autofill

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「上次填充条目」记忆仓库（ISSUE-P3-39）。
 *
 * 用途：在同一站点/应用再次触发填充时，把上次用户实际确认填充的条目**置顶**
 * （见 [AutofillCandidateRanker.promoteLastFilled]）——仅影响排序，**不改变**任何
 * 匹配与放行判定，也不因记忆而放宽凭据下发条件。
 *
 * 存储：仅保存条目的 hex 标识（UUID，非敏感数据），不落任何明文、域名或账号。
 * `context` 为 null（纯 JVM 单元测试注入）时退化为进程内存语义，保证可测性。
 */
@Singleton
class AutofillLastFilledStore @Inject constructor(
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 无持久化层时的内存回退（仅用于纯 JVM 单测；生产始终有 prefs） */
    @Volatile
    private var memoryEntryId: String? = null

    /** 读取上次填充条目标识；无记录返回 null */
    fun lastFilledEntryId(): String? =
        prefs?.getString(K_LAST_FILLED_ENTRY_ID, null)?.takeIf { it.isNotBlank() }
            ?: memoryEntryId

    /** 记录本次确认填充的条目标识（空白输入忽略） */
    fun record(entryId: String) {
        val normalized = entryId.trim()
        if (normalized.isEmpty()) return
        val p = prefs
        if (p == null) {
            memoryEntryId = normalized
            return
        }
        p.edit().putString(K_LAST_FILLED_ENTRY_ID, normalized).apply()
    }

    /** 清除记忆（如库切换/锁定后不再跨会话置顶） */
    fun clear() {
        memoryEntryId = null
        prefs?.edit()?.remove(K_LAST_FILLED_ENTRY_ID)?.apply()
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_autofill_last_filled"
        const val K_LAST_FILLED_ENTRY_ID = "last_filled_entry_id"
    }
}
