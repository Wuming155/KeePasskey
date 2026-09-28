package com.keepasskey.app.autofill

import com.keepasskey.core.session.SessionLockObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨请求登录字段记忆的缓存条目（ISSUE-P3-372 AC③）。
 *
 * key 为 `AutofillId.toString()`（系统视图标识，**非敏感**——不含条目、域或凭据内容）；
 * null 表示该侧当时不存在。
 */
internal data class RememberedLoginFields(
    val usernameKey: String?,
    val passwordKey: String?
) {
    /** 两侧皆空的条目不构成记忆 */
    val isEmpty: Boolean get() = usernameKey == null && passwordKey == null
}

/**
 * 跨请求登录字段记忆仓库（ISSUE-P3-372 AC③，对齐 Monica `passwordMemoryByPackage`）。
 *
 * 用途：应对「密码框在部分 FillRequest 中因布局/动画时序丢帧不可见、被首轮扫描连带丢弃，
 * 登录目标时有时无」的形态——同包同域的上一次请求识别到登录字段后缓存其 AutofillId，
 * 后续请求缺密码目标时整体回补（回补裁决见 [AutofillLoginFieldRecovery]）。
 *
 * 生命周期（AC③ 红线）：
 * - **仅服务进程内存**，不落盘、不进任何状态流；
 * - 实现 [SessionLockObserver]，库锁定 / 关闭 / 换库即清（由 `DatabaseModule` 注册）；
 * - 记忆体只有视图 id 键，绝不缓存条目 id、域名明文以外的任何凭据内容。
 *
 * 容量闸门 [MAX_CONTEXTS]：长时间运行的服务进程最多保留最近 [MAX_CONTEXTS] 个
 * 「包名|域」上下文（超出按插入序淘汰），防无界增长。
 */
@Singleton
class AutofillLoginFieldMemory @Inject constructor() : SessionLockObserver {

    private val remembered = LinkedHashMap<String, RememberedLoginFields>()

    /** 记录某上下文最近一次识别到的登录字段（任一侧为空白键的组合不落记） */
    @Synchronized
    fun remember(contextKey: String, usernameKey: String?, passwordKey: String?) {
        val entry = RememberedLoginFields(
            usernameKey = usernameKey?.takeIf { it.isNotBlank() },
            passwordKey = passwordKey?.takeIf { it.isNotBlank() }
        )
        if (entry.isEmpty) return
        remembered.remove(contextKey)
        remembered[contextKey] = entry
        while (remembered.size > MAX_CONTEXTS) {
            remembered.remove(remembered.keys.first())
        }
    }

    /** 读取某上下文的记忆；无记录返回 null（internal：返回类型为 internal 数据类） */
    @Synchronized
    internal fun recall(contextKey: String): RememberedLoginFields? = remembered[contextKey]

    /** 清空全部记忆（锁定 / 换库 / 单测复位） */
    @Synchronized
    fun clear() {
        remembered.clear()
    }

    /** ISSUE-P3-109 同款：会话锁定 / 关闭 / 换库回调（[SessionLockObserver] 契约：非阻塞、幂等） */
    override fun onSessionLocked() = clear()

    companion object {
        /** 单进程内同时保留的「包名|域」上下文上限 */
        internal const val MAX_CONTEXTS = 16
    }
}

/**
 * 跨请求回补裁决（ISSUE-P3-372 AC③，纯函数）。
 *
 * 规则（对齐 Monica `passwordMemoryByPackage` 回补分支，按本仓角色模型收敛）：
 * - 记忆的**全部**键必须仍存在于当前结构（任一缺失 ⇒ 整体不回补，防注入失效视图 id）；
 * - 仅在「当前缺密码目标」时回补（密码目标健在即无需回补）；
 * - 只回补**缺失侧**：已识别到的一侧原样保留；
 * - 两侧都不缺 / 条件不满足 ⇒ 返回 null。
 */
internal object AutofillLoginFieldRecovery {

    fun recover(
        remembered: RememberedLoginFields,
        availableKeys: Set<String>,
        hasCurrentUsername: Boolean,
        hasCurrentPassword: Boolean
    ): RememberedLoginFields? {
        if (remembered.isEmpty) return null
        if (hasCurrentPassword) return null
        val usernameKeyPresent = remembered.usernameKey?.let { it in availableKeys } ?: true
        val passwordKeyPresent = remembered.passwordKey?.let { it in availableKeys } ?: true
        if (!usernameKeyPresent || !passwordKeyPresent) return null

        val recovered = RememberedLoginFields(
            usernameKey = if (hasCurrentUsername) null else remembered.usernameKey,
            passwordKey = remembered.passwordKey
        )
        return recovered.takeUnless { it.isEmpty }
    }
}
