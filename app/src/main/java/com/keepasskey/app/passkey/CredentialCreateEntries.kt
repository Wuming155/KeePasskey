package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.credentials.provider.Action
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.CreateEntry
import com.keepasskey.app.R
import com.keepasskey.core.log.AppLog
import org.json.JSONObject

/**
 * `onBeginCreateCredential` 的**保存入口装配**（ISSUE-P3-188 自
 * [KeePasskeyCredentialProviderService.buildBeginCreateResponse] 下沉为同包协作对象）。
 *
 * 职责边界：只回答「这类请求呈现哪个 CreateEntry、带上哪些参数」；
 * 运行环境完整性门控与响应对象组装仍留在服务内（`return responseBuilder.build()` 的
 * 收口点不迁移，避免拆分改变下发时序）。返回 `null` 表示该请求被 fail-closed 拒绝，
 * 调用方据此不追加任何条目。
 *
 * PendingIntent requestCode：**不再使用本文件原有常量**（原为 103 / 104，ISSUE-P2-199）——
 * 创建入口原先恒用固定 requestCode，而落地 Intent 只带 extras（无 action / data ⇒ 同组件的
 * `Intent.filterEquals` 恒为真），叠加 `FLAG_UPDATE_CURRENT` 后第二次创建请求会**就地覆写**
 * 第一次的 PendingIntent 记录，后者携带的陈旧 `origin` / `rpId` 会让 `PasskeyCreateActivity`
 * 的 DAL 门控整段被跳过。现统一取自 [CredentialPendingIntents.nextRequestCode]（进程级单调），
 * 从根上消除覆写对象。
 */
internal object CredentialCreateEntries {

    /** 沿用服务 TAG，使入口装配与门控留痕可在同一标签下串读 */
    private const val TAG = "KeePasskeyCredentialProviderService"

    /**
     * Passkey 注册入口。
     *
     * `rp.id` 缺失时回落为调用 origin 的可注册域；Wave 12 授权收紧（对齐官方
     * 「rp.id 须为 origin 可注册后缀」）：创建分支与断言分支同等 fail-closed——
     * rp.id 不可信时拒绝呈现创建入口。
     */
    fun passkeyEntry(
        context: Context,
        request: BeginCreatePublicKeyCredentialRequest,
        callingOrigin: String
    ): CreateEntry? {
        val fields = parseCreateFields(request.requestJson)
        val rpId = fields.rpId.ifBlank { DomainMatcher.extractDomain(callingOrigin) }
        if (!DomainMatcher.isRpIdTrustedForCreation(rpId, callingOrigin)) {
            // ISSUE-P1-10：日志不得携带 rpId 等敏感标识（会暴露用户注册的站点域）
            AppLog.w(TAG, "拒绝创建请求：rp.id 不可信（非调用方可注册后缀或为公共后缀）")
            return null
        }

        val intent = Intent(context, PasskeyCreateActivity::class.java).apply {
            putExtra(PasskeyCreateActivity.EXTRA_RP_ID, rpId)
            putExtra(PasskeyCreateActivity.EXTRA_USER_NAME, fields.userName)
            putExtra(PasskeyCreateActivity.EXTRA_USER_DISPLAY_NAME, fields.userDisplayName)
            putExtra(PasskeyCreateActivity.EXTRA_CHALLENGE, fields.challenge)
            // ISSUE-P3-511：本 extra 是**有意的无读取点写入**——ISSUE-P2-199 起 origin
            // 一律由本次系统背书的 CallingAppInfo 现场重新派生（见 PasskeyCreateActivity
            // 的 EXTRA_ORIGIN KDoc），读取点已按 fail-closed 设计删除，故此处写入不再被消费。
            // 保留写入的三个理由：① 与断言侧匹配键常量同值对齐（PendingIntent 匹配键口径）；
            // ② 兼容既有设备侧用例（PendingIntentMatchKeyDeviceTest）；③ 抓包 / 日志中可辨认
            // 组装期 origin。**禁止**据此复活读取点：复用陈旧 origin 曾导致 DAL 门控整段跳过。
            putExtra(PasskeyCreateActivity.EXTRA_ORIGIN, callingOrigin)
        }
        // ISSUE-P1-10：不记录 rpId / 账号标签（会暴露用户注册的站点域）
        AppLog.i(TAG, "已向系统返回 Passkey CreateEntry")
        return CreateEntry.Builder(
            fields.userName.ifBlank { context.getString(R.string.cred_create_entry_title) },
            entryPendingIntent(context, intent)
        )
            .setDescription(context.getString(R.string.cred_create_entry_subtitle))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .build()
    }

    /**
     * ISSUE-P3-345 / PD-51：无匹配时的「新建密码条目」[Action]。
     *
     * 官方契约（androidx.credentials.provider.Action，Added in 1.2.0）：呈现于选择器独立的
     * 「Actions」类目；官方举例标题即 `Add a new Password`。落地页
     * [PasswordDraftActivity] 保存成功后 `setGetCredentialResponse` 直接完成本次登录，
     * 放弃时 `RESULT_CANCELED`（系统重新弹出选择器，不把用户推出流程）。
     * PendingIntent 契约与既有候选条目一致（[CredentialPendingIntents]：
     * FLAG_MUTABLE + 非 ONE_SHOT + 进程级唯一 requestCode）。
     * **文案零插值**（PD-51 裁决 3）：标题不得携带域名 / 包名。
     */
    fun createPasswordAction(context: Context, callingPackage: String, callingOrigin: String): Action {
        val intent = Intent(context, PasswordDraftActivity::class.java).apply {
            // 归属数据由本应用下发（组件 exported=false）；落地页再与系统认证值交叉核对
            putExtra(PasswordDraftActivity.EXTRA_PACKAGE_NAME, callingPackage)
            putExtra(PasswordDraftActivity.EXTRA_WEB_DOMAIN, callingOrigin)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            CredentialPendingIntents.nextRequestCode(),
            intent,
            CredentialPendingIntents.ENTRY_FLAGS
        )
        return Action.Builder(
            context.getString(R.string.cred_action_create_password_title),
            pendingIntent
        ).build()
    }

    /** 传统密码保存入口 */
    fun passwordEntry(context: Context, callingPackage: String, callingOrigin: String): CreateEntry {
        val intent = Intent(context, PasswordSaveActivity::class.java).apply {
            putExtra(PasswordSaveActivity.EXTRA_PACKAGE_NAME, callingPackage)
            putExtra(PasswordSaveActivity.EXTRA_WEB_DOMAIN, callingOrigin)
        }
        return CreateEntry.Builder(
            context.getString(R.string.cred_create_entry_title),
            entryPendingIntent(context, intent)
        )
            .setDescription(context.getString(R.string.cred_create_entry_subtitle))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .build()
    }

    /** 注册请求 JSON 的展示面字段（ISSUE-P3-188：仅收拢解析中间量，取值口径不变） */
    private data class CreateFields(
        val rpId: String,
        val userName: String,
        val userDisplayName: String,
        val challenge: String
    )

    /** 解析失败按「全空字段」处理（与拆分前一致：不因此拒绝请求，`rp.id` 另有 origin 回落） */
    private fun parseCreateFields(requestJson: String): CreateFields {
        var rpId = ""
        var userName = ""
        var userDisplayName = ""
        var challenge = ""
        try {
            val json = JSONObject(requestJson)
            rpId = json.optJSONObject(WebAuthnJson.RP)?.optString(WebAuthnJson.ID).orEmpty()
            val userObj = json.optJSONObject(WebAuthnJson.USER)
            userName = userObj?.optString(WebAuthnJson.NAME).orEmpty()
            userDisplayName = userObj?.optString(WebAuthnJson.DISPLAY_NAME).orEmpty()
            challenge = json.optString(WebAuthnJson.CHALLENGE)
        } catch (e: Exception) {
            AppLog.w(TAG, "解析 BeginCreatePublicKeyCredentialRequest JSON 失败", e)
        }
        return CreateFields(rpId, userName, userDisplayName, challenge)
    }

    /**
     * ISSUE-P1-01：必须 FLAG_MUTABLE，系统需注入 ProviderCreateCredentialRequest
     * （标志位口径集中在 [CredentialPendingIntents.ENTRY_FLAGS]）。
     *
     * ISSUE-P2-199：requestCode 由 [CredentialPendingIntents.nextRequestCode] 进程级单调发放
     * （标志位与 requestCode 两类契约现同处一个对象，避免再次漂移）。
     */
    private fun entryPendingIntent(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            CredentialPendingIntents.nextRequestCode(),
            intent,
            CredentialPendingIntents.ENTRY_FLAGS
        )
}
