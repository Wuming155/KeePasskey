package com.keepasskey.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/**
 * 锁定态保存请求的系统入口（`ENG-20261028-21`，沿用 `ISSUE-P2-603` 的每请求身份）。
 *
 * ## 交给系统的内容（留存边界）
 * `PendingIntent` 由系统持有、本应用无法确定性清除其中的 extras，因此这里**只放**
 * 一次性令牌与调用方归属上下文（包名、表单自报域）；用户名与口令一律不进 Intent，
 * 改由 [LockedSavePayloadStore] 以 Keystore 封印记录承载，落地页按令牌取回。
 * 构造签名本身即护栏：本工厂没有接收口令的参数，敏感载荷在类型层面就进不了系统句柄。
 *
 * ## 身份
 * `requestToken` 同时进入参与 `filterEquals` 的 `Intent.data` 与 extras：`FLAG_UPDATE_CURRENT`
 * 下若两请求共用同一 `requestCode` 且身份相同，后一次会就地覆写前一次已交付的 extras
 * （保存串单）。生产侧 requestCode 由进程级单调分配器给出，`data` 再提供跨进程不复用的随机身份。
 *
 * ## 标志位
 * `FLAG_IMMUTABLE`：本入口**无任何 fillIn 需求**（全仓 fillIn 仅用于自动填充数据集与
 * Credential Manager 的回传注入），而承载保存请求的句柄不应被接收方改写。
 * 不使用 `FLAG_ONE_SHOT`：用户取消后可能重新点选，单次性由台账与载荷载体共同保证。
 */
internal object LockedSaveRequestPendingIntent {

    const val EXTRA_REQUEST_TOKEN = "com.keepasskey.extra.AUTOFILL_SAVE_REQUEST_TOKEN"
    const val EXTRA_PACKAGE_NAME = "com.keepasskey.extra.AUTOFILL_SAVE_PACKAGE_NAME"
    const val EXTRA_WEB_DOMAIN = "com.keepasskey.extra.AUTOFILL_SAVE_WEB_DOMAIN"

    private const val URI_SCHEME = "keepasskey"
    private const val URI_AUTHORITY = "autofill-save-request"

    fun create(
        context: Context,
        requestCode: Int,
        requestToken: String,
        callingPackage: String,
        webDomain: String?
    ): PendingIntent {
        val intent = Intent(context, AutofillLockedSaveActivity::class.java).apply {
            data = Uri.Builder()
                .scheme(URI_SCHEME)
                .authority(URI_AUTHORITY)
                .appendPath(requestToken)
                .build()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_REQUEST_TOKEN, requestToken)
            putExtra(EXTRA_PACKAGE_NAME, callingPackage)
            putExtra(EXTRA_WEB_DOMAIN, webDomain)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 严格解析请求令牌：`Intent.data` 与 extras 必须**同时存在、内容一致且为规范 UUID**，
     * 任一不符即 null（调用方 fail-closed）。非导出 Activity 只由本应用 PendingIntent 拉起，
     * 这里仍按不可信输入校验，避免把畸形身份带进台账与载体键。
     */
    fun requestTokenFromIntent(intent: Intent): String? {
        val data = intent.data ?: return null
        if (data.scheme != URI_SCHEME || data.authority != URI_AUTHORITY) return null
        if (data.query != null || data.fragment != null || data.pathSegments.size != 1) return null
        val fromData = canonicalUuid(data.lastPathSegment) ?: return null
        val fromExtra = canonicalUuid(intent.getStringExtra(EXTRA_REQUEST_TOKEN)) ?: return null
        return if (fromData == fromExtra) fromData else null
    }

    private fun canonicalUuid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return parsed.toString().takeIf { it == raw }
    }
}
