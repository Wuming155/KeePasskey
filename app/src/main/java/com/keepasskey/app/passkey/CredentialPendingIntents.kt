package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.keepasskey.database.session.SessionCredentialTicket
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Credential Manager 提供者 **PendingIntent 契约**（标志位 + requestCode 分配，ISSUE-P1-01 / ISSUE-P2-199）。
 *
 * ## 官方契约（androidx.credentials:credentials:1.6.0）
 * `Action` / `AuthenticationAction` / `CreateEntry` / `PasswordCredentialEntry` /
 * `PublicKeyCredentialEntry` / `CustomCredentialEntry` 的 KDoc 一致要求：
 * > must be created with a unique request code per entry, with flag
 * > [PendingIntent.FLAG_MUTABLE] to allow the Android system to attach the final request,
 * > and NOT with flag [PendingIntent.FLAG_ONE_SHOT] as it can be invoked multiple times
 *
 * 「unique request code per entry」仍由进程内单调分配器满足，但它不能独自隔离进程重建：
 * `PendingIntent` 记录的匹配键是 `(requestCode, Intent.filterEquals)`（`extras` 不参与匹配，
 * 见设备侧回归 `PendingIntentMatchKeyDeviceTest`），故两次不同响应只要落到同一
 * `requestCode` + 同一落地组件，第二次 `FLAG_UPDATE_CURRENT` 就会**就地覆写**第一次记录，
 * 表现为「用户点中的是旧候选，实际拉起的却是新上下文的请求」。提供者进程死亡不代表
 * 其它进程持有的 token 失效，因此每个入口还必须有独立、随机且参与 `filterEquals` 的身份。
 *
 * 系统 Credential Manager 在用户点选条目时，是以 **fillIn Intent** 方式 `send()` 该
 * PendingIntent 的，请求本体（`EXTRA_BEGIN_GET_CREDENTIAL_REQUEST` /
 * `EXTRA_GET_CREDENTIAL_REQUEST` / `EXTRA_CREATE_CREDENTIAL_REQUEST`）由系统在 send 阶段注入。
 *
 * ## 为何必须 FLAG_MUTABLE
 * `PendingIntent.FLAG_IMMUTABLE` 的官方语义是「**传给 send 方法用于填充未设置属性的
 * 附加 Intent 将被忽略**」。一旦挂上该标志，系统注入的 fillIn extras 被静默丢弃，
 * `PendingIntentHandler.retrieve*` 在落地 Activity 侧一律返回 null，直接后果：
 * 1. [CredentialUnlockActivity] 取不到原始 `BeginGetCredentialRequest` → 链式解锁
 *    解锁成功后无法回传候选，`RESULT_CANCELED` 让选择器重新弹出并标注「无有效凭据」；
 * 2. [PasswordSaveActivity] 取不到 `CreatePasswordRequest` → 待保存密码恒为空，保存链路 100% 失败；
 * 3. [PasskeyCreateActivity] 取不到 `callingAppInfo` → 非浏览器（apk-key-hash）路径无法
 *    绑定调用包名，注册被拒。
 *
 * 这是**平台契约级**缺陷：manifest、capabilities、服务绑定全部正确，系统在设置中也能
 * 正常勾选启用，但端到端握手在「系统注入请求」这一步静默失败。
 *
 * ## 为何禁止 FLAG_ONE_SHOT
 * 同一条目可被用户多次点选（取消后重新选择），ONE_SHOT 会在首次触发后销毁 PendingIntent。
 */
object CredentialPendingIntents {

    /**
     * 挂在凭据条目 / 解锁 Action / 创建入口上的 PendingIntent 标志位：
     * `FLAG_MUTABLE`（允许系统注入最终请求）＋ `FLAG_UPDATE_CURRENT`。
     *
     * 定义为 `const val`：`PendingIntent.FLAG_*` 均为编译期常量，纯 JVM 单测可无需
     * Robolectric 直接断言本值，防止回归到 `FLAG_IMMUTABLE`。
     *
     * `FLAG_UPDATE_CURRENT` 继续符合 Credential Manager 提供者契约；请求码和随机 data 身份
     * 共同避免不同逻辑入口命中同一 token。进程重建后的旧 token 由 data 身份隔离。
     */
    const val ENTRY_FLAGS: Int = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /**
     * ISSUE-P2-199：CM 通道**进程级单调** requestCode 分配器。
     *
     * ## 缺陷形态（整改前）
     * 四条落地入口的 requestCode 分布为：解锁 Action 常量 `100`、创建 Passkey / 密码常量
     * `103` / `104`，而断言与密码填充由 `CredentialResponseAssembler` 内**每次响应新建**的
     * 分配器从 `1000` 复位发放 ⇒ 同一次用户流程即可两次从 `1000` 重排。
     * 叠加 `FLAG_UPDATE_CURRENT`，「两路响应同 requestCode + 同落地组件」会就地覆写既有记录：
     * - 创建侧：被覆写的陈旧 `origin` 使 `PasskeyCreateActivity` 跳过 DAL 校验分支；
     * - 断言侧：同调用方的多候选（同浏览器两标签页同 rpId 多账号）下点 A 实签 B。
     *
     * ## 修复口径
     * 与自动填充通道的 `AutofillDatasetBuilders`「认证入口分配器」**同构**：进程级单调、
     * 每次分配取新值、**不随响应重置**（重置反而重新引入复用）。`getAndIncrement` 属原子读改写，
     * 并发组装两路响应（服务直查 + 链式解锁）拿不到同一值，天然满足官方
     * 「unique request code per entry」。跨进程与计数器重置后的隔离由 [attachRequestIdentity]
     * 写入随机 `Intent.data`，两者共同保证 `PendingIntent` 匹配键不复用。
     *
     * ## 避开带（取值段声明）
     *
     * 基线 `1000` 刻意避开其余通道既有 requestCode 常量的取值段，便于日志与抓包中辨认。
     * 完整清单以 `AVOID-BAND:` 行承载（`CredentialRequestCodeWiringTest` 解析该行并与全仓
     * 扫描到的 `REQUEST_CODE_*` 常量逐值比对 ⇒ **新增常量漏登即变红**，避开带不再是散文承诺）：
     *
     * `AVOID-BAND: 100, 2001, 2002, 2100, 2200, 2401, 3001, 3002, 3003, 3004, 3005, 3006, 3100`
     *
     * > **避开带 = 可读性口径，不是防覆写边界**（ISSUE-P3-513 如实声明）：本分配器
     * > `getAndIncrement` **无上界**，进程生命周期内分配足够多次后终将跨越任何有限常量值
     * > （自动填充的 `AutofillDatasetBuilders` 认证入口分配器同样自 `100` 无界递增）。
     * > requestCode 取值段只服务可读性。真实隔离由每个 CM 入口的随机 `Intent.data` 身份提供，
     * > 它参与 `filterEquals`，也覆盖进程重建后 requestCode 计数器复位的情形。
     */
    fun nextRequestCode(): Int = requestCodeAllocator.getAndIncrement()

    /**
     * 为一条逻辑入口设置随机 filterEquals 身份，并可选登记只驻留于当前进程的候选上下文。
     * URI 只包含随机 UUID，不携带站点、库、条目或秘密数据。进程重建后注册表为空，旧入口
     * 无法从新请求 extras 恢复上下文，调用方必须安全失败并重新获取候选。
     */
    internal fun attachRequestIdentity(
        intent: Intent,
        context: CredentialEntryRequestContext? = null
    ): String {
        val requestId = newRequestId()
        intent.data = requestUri(requestId)
        context?.let { registerRequestContext(requestId, it) }
        return requestId
    }

    /** 解析并查找此 Intent 的当前进程上下文；身份缺失、格式错误、过期或类型不符均返回 null。 */
    internal fun requestContext(
        intent: Intent,
        expectedType: CredentialEntryRequestType
    ): CredentialEntryRequestContext? {
        val requestId = requestIdFromIntent(intent) ?: return null
        return requestContext(requestId, expectedType)
    }

    /** 随机 UUID v4 提供跨进程不复用的入口身份；纯 JVM 可测，不含 Android 对象。 */
    internal fun newRequestId(): String = UUID.randomUUID().toString()

    /** URI 中只允许本工厂生成的规范 UUID 路径，拒绝 query、fragment 和额外路径段。 */
    internal fun requestIdFromIntent(intent: Intent): String? {
        val data = intent.data ?: return null
        if (data.scheme != REQUEST_URI_SCHEME || data.authority != REQUEST_URI_AUTHORITY ||
            data.query != null || data.fragment != null || data.pathSegments.size != 1
        ) {
            return null
        }
        val rawId = data.lastPathSegment ?: return null
        return runCatching { UUID.fromString(rawId).toString() }
            .getOrNull()
            ?.takeIf { it == rawId }
    }

    /** 注册表只保存非秘密候选绑定，进程内有时限且有容量上限。 */
    internal fun registerRequestContext(
        requestId: String,
        context: CredentialEntryRequestContext,
        nowMillis: Long = monotonicNowMillis()
    ) {
        synchronized(requestContexts) {
            pruneExpiredContexts(nowMillis)
            requestContexts[requestId] = StoredRequestContext(context, nowMillis)
            while (requestContexts.size > MAX_REGISTERED_CONTEXTS) {
                val oldest = requestContexts.entries.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }
    }

    internal fun requestContext(
        requestId: String,
        expectedType: CredentialEntryRequestType,
        nowMillis: Long = monotonicNowMillis()
    ): CredentialEntryRequestContext? = synchronized(requestContexts) {
        pruneExpiredContexts(nowMillis)
        requestContexts[requestId]
            ?.context
            ?.takeIf { it.requestType == expectedType }
    }

    private fun requestUri(requestId: String): Uri = Uri.Builder()
        .scheme(REQUEST_URI_SCHEME)
        .authority(REQUEST_URI_AUTHORITY)
        .appendPath(requestId)
        .build()

    private fun pruneExpiredContexts(nowMillis: Long) {
        val iterator = requestContexts.entries.iterator()
        while (iterator.hasNext()) {
            val storedAt = iterator.next().value.registeredAtMillis
            if (nowMillis < storedAt || nowMillis - storedAt >= REQUEST_CONTEXT_TTL_MILLIS) {
                iterator.remove()
            }
        }
    }

    private fun monotonicNowMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    /** 进程级单调计数器；进程重启会复位，跨进程隔离由随机 Intent.data 身份保证。 */
    private val requestCodeAllocator = AtomicInteger(REQUEST_CODE_BASE)

    private data class StoredRequestContext(
        val context: CredentialEntryRequestContext,
        val registeredAtMillis: Long
    )

    private val requestContexts = LinkedHashMap<String, StoredRequestContext>()

    /** 分配基线（见 [nextRequestCode] 的取值说明） */
    private const val REQUEST_CODE_BASE = 1000
    private const val REQUEST_URI_SCHEME = "keepasskey"
    private const val REQUEST_URI_AUTHORITY = "credential-entry"
    private const val MAX_REGISTERED_CONTEXTS = 512
    private const val REQUEST_CONTEXT_TTL_MILLIS = 15 * 60 * 1000L
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

/** Credential Manager 入口类型；用于防止同一随机 ID 的上下文跨功能混用。 */
internal enum class CredentialEntryRequestType {
    PASSWORD_FILL,
    PASSKEY_ASSERTION,
    PASSKEY_CREATE,
    PASSWORD_SAVE,
    PASSWORD_DRAFT,
    UNLOCK
}

/** 入口候选的非秘密绑定数据；禁止放入口令、认证结果或完整请求载荷。 */
internal data class CredentialEntryRequestContext(
    val requestType: CredentialEntryRequestType,
    val databaseId: String?,
    val entryId: String?,
    val expectedOrigin: String,
    val expectedPackage: String,
    val expectedDomain: String = "",
    val sessionTicket: SessionCredentialTicket? = null
)

/** 最终系统背书的 caller 必须完整匹配密码候选注册的 origin 与 package。 */
internal fun CredentialEntryRequestContext.matchesPasswordFillCaller(
    attestedOrigin: String,
    attestedPackage: String
): Boolean = requestType == CredentialEntryRequestType.PASSWORD_FILL &&
    expectedOrigin.isNotBlank() && expectedOrigin == attestedOrigin &&
    expectedPackage.isNotBlank() && expectedPackage.trim() == attestedPackage.trim()
