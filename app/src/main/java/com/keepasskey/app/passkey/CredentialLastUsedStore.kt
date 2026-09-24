package com.keepasskey.app.passkey

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.core.session.SessionLockObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential Manager 通道「条目上次使用时刻」记录仓库（ISSUE-P3-298 ⑥）。
 *
 * ## 用途与数据源
 *
 * CM 通道在本仓**未实现** `onCompleteGetCredentialRequest` 回调（框架侧「被选凭据」
 * 信号不可得，全仓 0 命中），但本应用自己的交付落地页就是确定性的「用户选了谁」：
 * - [PasswordFillActivity]：密码凭据验证通过、回传 `RESULT_OK` 前；
 * - [PasskeyAssertionActivity]：断言签发并回传 `RESULT_OK` 前。
 *
 * 两处各记一次 `entryId → epochMillis`，候选组装侧（`CredentialResponseAssembler`）
 * 据此为 `PasswordCredentialEntry` / `PublicKeyCredentialEntry` 设置
 * `setLastUsedTime(Instant)`（androidx.credentials 1.6.0 源码实证：Builder 存在该参数，
 * 且经 Slice 序列化下发系统 UI；`@property lastUsedTime` KDoc：「the last used time the
 * credential underlying this entry was used by the user」）。系统 UI 是否据其排序属
 * 系统侧行为，本仓只保证**如实提供**数据源。
 *
 * ## 存储口径
 *
 * 仅保存条目 hex 标识（UUID）与时刻戳——与 `AutofillLastFilledStore` 同级的非敏感数据
 * （条目 UUID 不含任何明文）。**不**实现 [SessionLockObserver]：排序记忆跨锁定会话
 * 有留存价值（与「上次填充」的会话级置顶语义不同），锁定 / 换库不清除。
 * `context` 为 null（纯 JVM 单测）时退化为进程内存语义。
 */
@Singleton
class CredentialLastUsedStore @Inject constructor(
    @ApplicationContext private val context: Context?
) {

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 无持久化层时的内存回退（仅用于纯 JVM 单测；生产始终有 prefs） */
    private val memory = ConcurrentHashMap<String, Long>()

    /** 记录条目的一次真实使用（空白输入忽略） */
    fun record(entryId: String) {
        val normalized = entryId.trim()
        if (normalized.isEmpty()) return
        val now = System.currentTimeMillis()
        val p = prefs
        if (p == null) {
            memory[normalized] = now
            return
        }
        p.edit().putLong(normalized, now).apply()
    }

    /** 读取条目上次使用时刻（毫秒）；无记录返回 null */
    fun lastUsedMillis(entryId: String): Long? {
        val normalized = entryId.trim()
        if (normalized.isEmpty()) return null
        val p = prefs
        if (p == null) {
            return memory[normalized]
        }
        if (!p.contains(normalized)) return null
        return p.getLong(normalized, 0L)
    }

    private companion object {
        const val PREFS_NAME = "keepasskey_credential_last_used"
    }
}
