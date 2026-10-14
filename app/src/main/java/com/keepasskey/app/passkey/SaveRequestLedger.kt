package com.keepasskey.app.passkey

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 锁定态自动填充保存请求的**单次消费台账**（ISSUE-P2-603）。
 *
 * 每个保存请求由 `KeePasskeyAutofillService.dispatchLockedSave` 下发的**唯一令牌**标识
 * （令牌本身不含任何凭据）。令牌在此登记为「已消费」后，同令牌再次进入即被拒绝——
 * 覆盖「系统入口被重复点击」与「进程重建后旧 `PendingIntent` 被再次触发」两类重复消费。
 *
 * - **不落明文**：只存令牌的 SHA-256 十六进制摘要（令牌不含凭据，摘要进一步消除可读性）；
 * - **跨进程可判**：记录持久化于应用私有 `SharedPreferences`，进程重建后仍生效，
 *   不依赖仅内存的标记；
 * - **过期回收**：超过 [TTL_MS] 的记录在读写时就地清理（覆盖「超时」失效语义）。
 */
@Singleton
class SaveRequestLedger @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 该令牌是否已在 [TTL_MS] 内被消费（已消费或未记录均按需清理过期项）。 */
    fun isConsumed(token: String): Boolean {
        val key = digest(token)
        val at = prefs.getLong(key, 0L)
        if (at == 0L) return false
        if (System.currentTimeMillis() - at > TTL_MS) {
            prefs.edit().remove(key).apply()
            return false
        }
        return true
    }

    /** 登记令牌为已消费（本请求成功、取消或超时后调用），同时清理过期记录。 */
    fun markConsumed(token: String) {
        prune()
        prefs.edit().putLong(digest(token), System.currentTimeMillis()).apply()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            val at = (value as? Long) ?: continue
            if (now - at > TTL_MS) editor.remove(key)
        }
        editor.apply()
    }

    private fun digest(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    internal companion object {
        internal const val PREFS_NAME = "autofill_save_requests"

        /** 消费记录有效期：短于用户复现保存动作的窗口，防止长期累积。 */
        internal const val TTL_MS = 15 * 60 * 1000L
    }
}
