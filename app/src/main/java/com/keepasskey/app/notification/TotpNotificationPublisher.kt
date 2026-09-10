package com.keepasskey.app.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充验证码通知发布器（ISSUE-P3-18 验收标准 3：`autofillShowTotpNotification` 真实生效）。
 *
 * 触发点：传统自动填充「已解锁分支」二次确认通过之后
 * （[com.keepasskey.app.autofill.AutofillConfirmActivity]）——即用户确认、凭据即将写入目标表单的
 * 真实落点，此时同步推送该条目的当前验证码。
 *
 * 内容与隐私（验收标准 4 逐项评估）：
 * - 通知**只含**验证码与剩余有效秒数，**不含**条目名 / 用户名 / 密码 / TOTP 种子 / 包名 / 域名，
 *   标题亦为固定通用文案；
 * - 验证码属 30 秒级短期动态值：不可逆推种子、过期即失效；且本通知**仅在用户显式开启**
 *   `autofillShowTotpNotification` 后才会发出，构成「愿意在通知栏看到验证码」的明示同意，
 *   故允许出现在正文（对照：任何条目名/用户名即便在开关开启时也绝不出现）；
 * - 加固：`VISIBILITY_SECRET`（锁屏不显示内容，不在系统锁屏泄露动态码）+
 *   `setTimeoutAfter`（验证码失效即自动撤下，避免通知栏长期驻留已作废的动态码）+
 *   静默不响铃（不以声音向旁人泄露「此刻有人在登录」）；
 * - 验证码不进入任何日志、不驻留成员变量（快照即用即弃）。
 */
@Singleton
class TotpNotificationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: ExtendedSettingsStore,
    private val permissionPrompter: NotificationPermissionPrompter
) {

    /**
     * 发送一条验证码通知。
     *
     * @param code 当前验证码（空白视为无 TOTP，按闸门拦下）
     * @param periodSeconds 验证码周期（秒）；非法值按 [NotificationGate.DEFAULT_TOTP_PERIOD_SECONDS] 兜底
     * @param nowMillis 时间源注入，便于单测断言剩余秒数边界
     * @return true 表示已交给系统通知栏；false 表示被闸门拦下或系统拒绝（静默降级，绝不抛异常）
     */
    fun publish(
        code: String,
        periodSeconds: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val prefEnabled = settingsStore.load().autofillShowTotpNotification
        if (!NotificationGate.shouldPostTotpNotification(prefEnabled, permissionPrompter.isGranted(), code)) {
            AppLog.i(TAG, "验证码通知未发送（偏好关闭或通知权限不可用），静默降级")
            return false
        }

        val remainingSeconds = NotificationGate.totpRemainingSeconds(nowMillis, periodSeconds)
        val notification = NotificationCompat.Builder(
            context,
            NotificationChannelSpec.AUTOFILL_TOTP.channelId
        )
            .setSmallIcon(NotificationChannels.SMALL_ICON_RES)
            .setContentTitle(context.getString(R.string.notification_totp_title))
            .setContentText(
                context.getString(R.string.notification_totp_text, code, remainingSeconds)
            )
            .setContentIntent(NotificationIntents.openAppForTotp(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(remainingSeconds * MILLIS_PER_SECOND)
            .build()
        return try {
            NotificationManagerCompat.from(context)
                .notify(NotificationChannels.ID_AUTOFILL_TOTP, notification)
            true
        } catch (t: SecurityException) {
            AppLog.w(TAG, "验证码通知发送被系统拒绝，静默降级", t)
            false
        }
    }

    private companion object {
        const val TAG = "TotpNotification"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
