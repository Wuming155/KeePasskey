package com.keepasskey.app.sync

import android.content.Context
import com.keepasskey.sync.engine.SyncCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「本地库已用新主凭据重新加密，云端副本尚未替换」的持久标记表（`ISSUE-P3-528` 立规）。
 *
 * ## 解决的问题
 *
 * 更换主密码 / 改绑密钥文件只改变**文件头凭据**，不改变条目树内容。而同步的
 * 「本地内容是否变过」判据只比树内容（[com.keepasskey.app.sync.KdbxContentComparator]），
 * 且装配期在判「无变化」时会**直接复用旧缓存字节**当作本地侧上传内容
 * （`SyncCycleSetup` 的 `localBytes` 分支）⇒ 换密后的下一次同步会判为
 * 「与云端一致（UpToDate）」，**一次上传都不发生**：
 * ① 换密回执承诺的「云端旧版本副本将在下次同步后被替换」不成立；
 * ② 旧主密码对云端副本**持续有效**（而换密的本意正是让旧口令失效）。
 *
 * ## 口径
 *
 * - 写：主凭据变更**成功后**由 [SyncCoordinator.markLocalVaultRecrypted] 置位；
 * - 读：同步周期装配段取用，把本地内容变化三态**强制**为「有变化」
 *   （`LocalContentChangeState.CHANGED`）——序列化走当前会话凭据，本地侧因此胜出并上传；
 * - 销：装配段**读后即清**。失败（离线等）时本地缓存已写入新凭据版本（`updateVersion`），
 *   下一周期仍由 `hasLocalChanges` 判为「本地有修改」而继续重试上传；
 * - 落点：与 [SyncCredentialsStore] **共用同一 prefs 文件**（键 `SyncCredentialsStore.PREFS_NAME`）——
 *   同步关系终止（换服务器 / 退出同步）时 `clear()` 连带销毁本标记，
 *   残留标记不会让新配置继承「待替换」意图（与 [SyncVaultBindingStore] 同一取舍）。
 *
 * 键形态 `recrypted_vault_<SHA-256(remotePath)>`，值为固定占位（存在即置位，非敏感）。
 */
@Singleton
class SyncCredentialRotationStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(SyncCredentialsStore.PREFS_NAME, Context.MODE_PRIVATE)

    /** 置位 `remotePath` 的「本地已重新加密、待替换云端副本」标记。 */
    fun markRecrypted(remotePath: String) {
        prefs.edit().putString(keyFor(remotePath), PENDING).apply()
    }

    /** 读取标记（不移除）。 */
    fun isRecrypted(remotePath: String): Boolean = prefs.getString(keyFor(remotePath), null) != null

    /**
     * 读后即清：返回该 `remotePath` 是否带待替换标记，并把标记销掉。
     *
     * 语义＝「本次周期已按有本地修改处理」；失败重试的兜底由本地缓存的版本号承担（见类 KDoc）。
     */
    fun consumeRecrypted(remotePath: String): Boolean {
        val key = keyFor(remotePath)
        val present = prefs.getString(key, null) != null
        if (present) prefs.edit().remove(key).apply()
        return present
    }

    /** 清除标记（同步关系终止 / 库身份切换时调用）。 */
    fun clear(remotePath: String) {
        prefs.edit().remove(keyFor(remotePath)).apply()
    }

    private fun keyFor(remotePath: String): String =
        KEY_PREFIX + SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8))

    private companion object {
        const val KEY_PREFIX = "recrypted_vault_"

        /** 值本身无信息量，仅以「键存在」表达置位（避免空串在部分实现下被当作「未置位」）。 */
        const val PENDING = "1"
    }
}
