package com.keepasskey.app.passkey

import android.app.PendingIntent

/**
 * Credential Manager 提供者 **PendingIntent 契约标志位**（ISSUE-P1-01 整改）。
 *
 * ## 官方契约（androidx.credentials:credentials:1.6.0）
 * `Action` / `AuthenticationAction` / `CreateEntry` / `PasswordCredentialEntry` /
 * `PublicKeyCredentialEntry` / `CustomCredentialEntry` 的 KDoc 一致要求：
 * > must be created with a unique request code per entry, with flag
 * > [PendingIntent.FLAG_MUTABLE] to allow the Android system to attach the final request,
 * > and NOT with flag [PendingIntent.FLAG_ONE_SHOT] as it can be invoked multiple times
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
     * `FLAG_MUTABLE`（允许系统注入最终请求）＋ `FLAG_UPDATE_CURRENT`（复用同一 requestCode 时刷新 extras）。
     *
     * 定义为 `const val`：`PendingIntent.FLAG_*` 均为编译期常量，纯 JVM 单测可无需
     * Robolectric 直接断言本值，防止回归到 `FLAG_IMMUTABLE`。
     */
    const val ENTRY_FLAGS: Int = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
