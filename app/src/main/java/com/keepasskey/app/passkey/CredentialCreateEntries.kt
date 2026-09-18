package com.keepasskey.app.passkey

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
 */
internal object CredentialCreateEntries {

    /**
     * PendingIntent requestCode：与拆分前的服务常量**同值**（103 / 104），
     * 仅因唯一使用点在本文件而随职责一并迁入。
     */
    private const val REQUEST_CODE_CREATE_PASSKEY = 103
    private const val REQUEST_CODE_CREATE_PASSWORD = 104

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
            putExtra(PasskeyCreateActivity.EXTRA_ORIGIN, callingOrigin)
        }
        // ISSUE-P1-10：不记录 rpId / 账号标签（会暴露用户注册的站点域）
        AppLog.i(TAG, "已向系统返回 Passkey CreateEntry")
        return CreateEntry.Builder(
            fields.userName.ifBlank { context.getString(R.string.cred_create_entry_title) },
            entryPendingIntent(context, REQUEST_CODE_CREATE_PASSKEY, intent)
        )
            .setDescription(context.getString(R.string.cred_create_entry_subtitle))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .build()
    }

    /** 传统密码保存入口 */
    fun passwordEntry(context: Context, callingPackage: String, callingOrigin: String): CreateEntry {
        val intent = Intent(context, PasswordSaveActivity::class.java).apply {
            putExtra(PasswordSaveActivity.EXTRA_PACKAGE_NAME, callingPackage)
            putExtra(PasswordSaveActivity.EXTRA_WEB_DOMAIN, callingOrigin)
        }
        return CreateEntry.Builder(
            context.getString(R.string.cred_create_entry_title),
            entryPendingIntent(context, REQUEST_CODE_CREATE_PASSWORD, intent)
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
     */
    private fun entryPendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, requestCode, intent, CredentialPendingIntents.ENTRY_FLAGS)
}
