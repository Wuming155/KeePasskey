package com.keepasskey.app.passkey

import android.app.PendingIntent
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
 * 「unique request code per entry」的**唯一性域是进程持久范围**，不限于单次响应：
 * `PendingIntent` 记录的匹配键是 `(requestCode, Intent.filterEquals)`（`extras` 不参与匹配，
 * 见设备侧回归 `PendingIntentMatchKeyDeviceTest`），故两次不同响应只要落到同一
 * `requestCode` + 同一落地组件，第二次 `FLAG_UPDATE_CURRENT` 就会**就地覆写**第一次记录，
 * 表现为「用户点中的是旧候选，实际拉起的却是新上下文的请求」。
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
     * **`FLAG_UPDATE_CURRENT` 在 requestCode 全进程唯一之后不再有覆写对象**——
     * 它保留的语义只是「同一逻辑入口重复创建时刷新 extras」，见 [nextRequestCode] 的说明。
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
     * 「unique request code per entry」且域覆盖整个进程生命周期。
     *
     * 基线 `1000` 刻意避开解锁 Action 旧常量区间与自动填充通道（`100`/`2001`/`2100`/`2200`）
     * 的取值段，便于日志与抓包中辨认。
     */
    fun nextRequestCode(): Int = requestCodeAllocator.getAndIncrement()

    /** 进程级单调计数器（进程重启即复位——`PendingIntent` 记录本身也不跨进程存活） */
    private val requestCodeAllocator = AtomicInteger(REQUEST_CODE_BASE)

    /** 分配基线（见 [nextRequestCode] 的取值说明） */
    private const val REQUEST_CODE_BASE = 1000
}
