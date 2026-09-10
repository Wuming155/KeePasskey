package com.keepasskey.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R

/**
 * 通知通道规格（ISSUE-P3-18 验收标准 1：Android 8+ 通知必须先归属通道）。
 *
 * 以 `enum class` 表达通道，而不是散落的字符串字面量（工程规则「禁止魔法字符串」）：
 * 通道 id / 名称 / 描述 / 重要度集中声明，[NotificationChannels.ensureCreated] 据此建立，
 * JVM 单测可直接断言通道契约（id 唯一、重要度符合「状态静默 / 验证码可见」的设计取舍）。
 *
 * 隐私约定（ISSUE-P3-18 验收标准 4）：通道名称与描述为**固定通用文案**，不含任何用户数据。
 */
enum class NotificationChannelSpec(
    /** 系统侧通道 id（一经发布即不可更改语义，仅可改名/改描述） */
    val channelId: String,
    /** 通道名称资源（用户在系统通知设置中看到的名称） */
    @StringRes val nameRes: Int,
    /** 通道描述资源 */
    @StringRes val descriptionRes: Int,
    /** 通道重要度：决定是否响铃/横幅/是否出现在状态栏 */
    val importance: Int
) {
    /** 已解锁常驻状态通知：低重要度（静默、无横幅），仅作为「库仍处于解锁态」的可见凭据 */
    UNLOCKED_STATUS(
        channelId = "keepasskey_unlocked_status",
        nameRes = R.string.notification_channel_unlocked_name,
        descriptionRes = R.string.notification_channel_unlocked_desc,
        importance = NotificationManager.IMPORTANCE_LOW
    ),

    /** 自动填充后的一次性验证码通知：默认重要度（在通知栏可见），发送时静默不响铃 */
    AUTOFILL_TOTP(
        channelId = "keepasskey_autofill_totp",
        nameRes = R.string.notification_channel_totp_name,
        descriptionRes = R.string.notification_channel_totp_desc,
        importance = NotificationManager.IMPORTANCE_DEFAULT
    )
}

/**
 * 通知基础设施静态契约（ISSUE-P3-18）：通知 id、小图标与通道建立。
 *
 * 通道建立必须在任何 `notify` 之前完成——Android 8+ 上向不存在的通道发送通知会被系统直接丢弃
 * （不抛异常、不提示），调用点见 [com.keepasskey.app.MainApplication.onCreate]。
 */
object NotificationChannels {

    /** 「已解锁」常驻通知的通知 id（具名常量，禁止在调用点写字面量） */
    const val ID_UNLOCKED_STATUS = 1001

    /** 自动填充验证码通知的通知 id */
    const val ID_AUTOFILL_TOTP = 1002

    /**
     * 通知小图标。
     *
     * 采用系统框架的**单色锁形图标**：官方设计要求通知小图标必须是单色 alpha 蒙版，
     * 而本模块不持有 `res/drawable` 写权限——仓库内唯一图标 [R.drawable.ic_launcher] 是
     * 彩色全幅矢量（108dp 实心底 + 白盾牌），用作小图标会被系统渲染成纯白方块。
     * 后续若补齐品牌单色小图标，只需把本常量指向新的 `R.drawable.*`，发送逻辑无需改动。
     */
    const val SMALL_ICON_RES = android.R.drawable.ic_lock_lock

    /**
     * 幂等建立全部通道（重复调用无害；已在系统侧存在的通道仅更新名称/描述）。
     *
     * 用户若曾手动调整过通道重要度，系统会保留用户设置（本方法不回收该决定）。
     */
    fun ensureCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (spec in NotificationChannelSpec.entries) {
            val channel = NotificationChannel(
                spec.channelId,
                context.getString(spec.nameRes),
                spec.importance
            ).apply {
                description = context.getString(spec.descriptionRes)
                // 密码管理器通知不参与角标计数（避免以数字暗示敏感事件频次）
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}

/**
 * 通知点击跳转（统一指向 [MainActivity]，应用主入口）。
 *
 * `FLAG_IMMUTABLE`（Android 12+ 官方要求显式声明可变性）+ `FLAG_UPDATE_CURRENT`
 * 保证同一请求码复用同一 PendingIntent；两条通知使用不同请求码，避免相互覆盖。
 */
internal object NotificationIntents {

    /** 已解锁常驻通知的跳转请求码 */
    private const val REQUEST_CODE_UNLOCKED_STATUS = 3001

    /** 验证码通知的跳转请求码 */
    private const val REQUEST_CODE_AUTOFILL_TOTP = 3002

    fun openAppForUnlockedStatus(context: Context): PendingIntent =
        openApp(context, REQUEST_CODE_UNLOCKED_STATUS)

    fun openAppForTotp(context: Context): PendingIntent =
        openApp(context, REQUEST_CODE_AUTOFILL_TOTP)

    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
