package com.keepasskey.app.autofill

import android.os.SystemClock
import com.keepasskey.app.passkey.DomainMatcher
import java.util.Locale

/**
 * 会话授权上下文（ISSUE-P3-42）：绑定「调用包名 + 目标域名」。
 *
 * 字段签名（表单具体字段）暂不纳入作用域——填充服务端与确认 Activity 端无一致来源可复现
 * 同一签名，纳入会导致授权永不命中；故作用域**只**收敛到包名 + 域（更保守：跨域不互通）。
 */
data class AutofillGrantContext(
    val packageName: String,
    val webDomain: String?
) {
    fun normalized(): AutofillGrantContext = copy(
        packageName = packageName.trim().lowercase(Locale.ROOT),
        webDomain = webDomain
            ?.let { DomainMatcher.extractDomain(it) }
            ?.removePrefix("www.")
            ?.trim('.')
            ?.takeIf { it.isNotBlank() }
    )
}

/**
 * 填充侧会话授权存储（ISSUE-P3-42）。
 *
 * 语义（**安全边界严格**）：
 * - 仅在**密码库已解锁**后生效：授权的作用是「避免短时间内对同一包名+域重复弹出二次确认」，
 *   **绝不**作用于「库锁定时跳过解锁」——库锁定时一律先解锁，与授权无关；
 * - 授权按「包名 + 域」严格匹配，跨包名 / 跨域一律不命中；
 * - TTL 到期即失效（`elapsedRealtime` 单调时钟，不受系统时间调整影响）。
 *
 * 默认 TTL 保守（[DEFAULT_TTL_MILLIS]），且**总开关默认关闭**——关闭时调用方根本不查询本存储，
 * 行为与既有的「每次下发前强制二次确认」完全一致。
 */
class AutofillSessionGrantStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {

    private data class Grant(val context: AutofillGrantContext, val expiresAtMillis: Long)

    @Volatile
    private var activeGrant: Grant? = null

    fun grant(context: AutofillGrantContext) {
        activeGrant = Grant(context.normalized(), elapsedRealtime() + ttlMillis)
    }

    fun isGranted(context: AutofillGrantContext): Boolean {
        val grant = activeGrant ?: return false
        if (elapsedRealtime() >= grant.expiresAtMillis) {
            clear()
            return false
        }
        return grant.context == context.normalized()
    }

    fun clear() {
        activeGrant = null
    }

    companion object {
        /** 默认授权有效期：30 秒（短窗，避免长期免确认） */
        const val DEFAULT_TTL_MILLIS = 30_000L
    }
}

/**
 * 进程内单例授权门面（生产使用）。
 *
 * 注意：本对象初始化会解析 `SystemClock`，**纯 JVM 单测请直接构造 [AutofillSessionGrantStore]
 * 并注入假时钟**，不要引用本对象。
 */
object AutofillSessionGrants {
    private val store = AutofillSessionGrantStore()

    fun grant(context: AutofillGrantContext) = store.grant(context)

    fun isGranted(context: AutofillGrantContext): Boolean = store.isGranted(context)

    fun clear() = store.clear()
}

/**
 * 授权门限策略（纯函数，便于单测）：是否因存在有效授权而**跳过**重复二次确认。
 *
 * 仅当「用户开启开关」且「库已解锁」且「存在匹配的有效授权」三者同时成立才为 true。
 */
object AutofillAuthenticationPolicy {
    fun skipRepeatConfirmation(
        sessionGrantEnabled: Boolean,
        vaultLocked: Boolean,
        grantActive: Boolean
    ): Boolean = sessionGrantEnabled && !vaultLocked && grantActive
}
