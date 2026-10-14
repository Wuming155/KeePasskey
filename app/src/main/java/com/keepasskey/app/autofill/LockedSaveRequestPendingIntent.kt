package com.keepasskey.app.autofill

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.keepasskey.app.passkey.PasswordSaveActivity

/**
 * 为每个锁定态保存请求构造显式且独立的系统入口（`ISSUE-P2-603`）。
 *
 * `requestToken` 同时进入参与 `filterEquals` 的 `Intent.data` 和 Activity extras；生产调用目标固定为
 * [PasswordSaveActivity]。`targetActivity` 为设备契约测试提供同包合成接收端，以验证 Android 系统实际
 * 保留 A/B 两个 PendingIntent 及各自 extras，而不是只比较 requestCode。
 */
internal object LockedSaveRequestPendingIntent {

    fun create(
        context: Context,
        requestCode: Int,
        requestToken: String,
        callingPackage: String,
        webDomain: String?,
        username: String,
        password: String,
        targetActivity: Class<out Activity> = PasswordSaveActivity::class.java
    ): PendingIntent {
        val intent = Intent(context, targetActivity).apply {
            data = Uri.parse("keepasskey://autofill-save-request/$requestToken")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PasswordSaveActivity.EXTRA_REQUEST_TOKEN, requestToken)
            putExtra(PasswordSaveActivity.EXTRA_PACKAGE_NAME, callingPackage)
            putExtra(PasswordSaveActivity.EXTRA_WEB_DOMAIN, webDomain)
            putExtra(PasswordSaveActivity.EXTRA_USERNAME, username)
            putExtra(PasswordSaveActivity.EXTRA_PASSWORD, password)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
