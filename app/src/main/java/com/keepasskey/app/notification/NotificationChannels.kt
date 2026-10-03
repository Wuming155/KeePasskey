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
    /**
     * 已解锁常驻状态通知（`ISSUE-P3-440` 走查修订，§428）：**默认重要度 + 通道级静音**。
     *
     * 原为 `IMPORTANCE_LOW`，但 **MIUI / HyperOS 把低重要度通知归入「静默通知」**——该分类下
     * 通知在折叠态**不渲染动作按钮**，用户须**长按**才看得到「立即锁定 / 复制用户名 / 复制验证码」
     * （2026-10-03 装机走查反馈「只有长按才会出现…选项」）。§426 曾按 `PD-71` 判其为平台行为；
     * 本批定位到「**通道重要度**」这一自家可控的成因（参考实现同口径），故按缺陷修正。
     *
     * 参考实现（`docs/references/` 定向源码检索，只读）：KeePassDX 全部通知通道用
     * `IMPORTANCE_DEFAULT`（`services/NotificationService.kt:67-72`）且动作按钮可直接点按；
     * Monica 的 Smart Copy 通知用 `IMPORTANCE_HIGH`（`utils/SmartCopyNotificationHelper.kt:154-165`）。
     * 本通道**不需要 heads-up**，取 `DEFAULT` 并在通道级声明无声无震动
     * （见 [NotificationChannels.ensureCreated] 的 `setSound(null, null)` + `enableVibration(false)`），
     * 于是「正常通知栏可见 + 动作可直接点」与「不发声、不横幅」两者兼得。
     *
     * **重要度不可程序化修改**（Android 既定约束）：故本批换新 id（`_v2`），
     * 并在 [NotificationChannels.ensureCreated] 内删除旧 id 的遗留通道，避免系统设置里残留同名空通道。
     */
    UNLOCKED_STATUS(
        channelId = "keepasskey_unlocked_status_v2",
        nameRes = R.string.notification_channel_unlocked_name,
        descriptionRes = R.string.notification_channel_unlocked_desc,
        importance = NotificationManager.IMPORTANCE_DEFAULT
    ),

    /** 自动填充后的一次性验证码通知：默认重要度（在通知栏可见），发送时静默不响铃 */
    AUTOFILL_TOTP(
        channelId = "keepasskey_autofill_totp",
        nameRes = R.string.notification_channel_totp_name,
        descriptionRes = R.string.notification_channel_totp_desc,
        importance = NotificationManager.IMPORTANCE_DEFAULT
    ),

    /**
     * 后台同步失败通知（ISSUE-P3-298 ④）：低重要度（静默、无横幅、不响铃），
     * 仅让「库已连续未同步成功」对用户可见。可关：用户可随时在系统通知设置中
     * 关闭该通道（通道级开关即本通知的关闭面，应用内不另设重复开关）。
     */
    SYNC_FAILURE(
        channelId = "keepasskey_sync_failure",
        nameRes = R.string.notification_channel_sync_failure_name,
        descriptionRes = R.string.notification_channel_sync_failure_desc,
        importance = NotificationManager.IMPORTANCE_LOW
    ),

    /**
     * 旧版无障碍自动填充的「检测到口令框」通知（ISSUE-P3-324；ISSUE-P3-374 AC③ 降为**低调**）。
     * 该通知是本通道的**唯一用户入口**（点按进选择器），但属「安静的状态提示」而非即时通讯——
     * `IMPORTANCE_LOW`：不发声、不横幅弹出、静默入通知栏。**限定**：Android 不允许代码修改
     * 已创建通道的重要度，存量安装上仍为建立时的 `DEFAULT`，需用户在系统通知设置内调整
     * （或卸载重装后生效），本条如实声明不虚称「改完即全量生效」。
     * 通道可关：用户在系统通知设置关闭本通道即等效于临时静默该通道，应用内不另设重复开关。
     */
    LEGACY_AUTOFILL(
        channelId = "keepasskey_legacy_autofill",
        nameRes = R.string.notification_channel_legacy_autofill_name,
        descriptionRes = R.string.notification_channel_legacy_autofill_desc,
        importance = NotificationManager.IMPORTANCE_LOW
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

    /** 后台同步失败通知的通知 id（ISSUE-P3-298 ④；失败复用同一 id，恢复即撤销） */
    const val ID_SYNC_FAILURE = 1003

    /** 旧版无障碍自动填充「检测到口令框」通知的通知 id（ISSUE-P3-324；同 id 复用去重） */
    const val ID_LEGACY_AUTOFILL = 1004

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
     * 旧「已解锁」通道 id（§428 之前的 `IMPORTANCE_LOW` 形态）。
     *
     * 重要度不可程序化修改 ⇒ 换新 id 后必须**显式删除**旧通道，否则系统通知设置里会残留
     * 一个同名（但永不投递）的空通道，用户无从分辨。
     */
    private const val LEGACY_ID_UNLOCKED_STATUS = "keepasskey_unlocked_status"

    /**
     * 幂等建立全部通道（重复调用无害；已在系统侧存在的通道仅更新名称/描述）。
     *
     * 用户若曾手动调整过通道重要度，系统会保留用户设置（本方法不回收该决定）。
     *
     * §428：**静音下沉到通道层**——原先只靠发送侧 `Notification.setSilent(true)`。该标志会把通知
     * 推向 OEM 的「静默」分类，而 MIUI/HyperOS 对静默通知在折叠态**不渲染动作按钮**（须长按）；
     * 「不发声/不震动」本就该由**通道**表达，与决定动作渲染的**重要度**解耦。
     * 参考：KeePassDX `services/NotificationService.kt:67-72` 建立通道即 `setSound(null, null)` +
     * `enableVibration(false)`。
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
                // §428：通道级静音（无声、无震动）——重要度只决定「能否可见 + 能否渲染动作」
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
        // §428 迁移：旧「已解锁」通道无法就地升级重要度，显式删除以免残留同名空通道（不存在时为幂等空操作）
        manager.deleteNotificationChannel(LEGACY_ID_UNLOCKED_STATUS)
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

    /** 同步失败通知的跳转请求码（ISSUE-P3-298 ④） */
    private const val REQUEST_CODE_SYNC_FAILURE = 3003

    /** 已解锁常驻通知「立即锁定」动作请求码（ISSUE-P3-386） */
    private const val REQUEST_CODE_UNLOCKED_LOCK = 3004

    /** 已解锁常驻通知「复制用户名」动作请求码（ISSUE-P3-440） */
    private const val REQUEST_CODE_UNLOCKED_COPY_USERNAME = 3005

    /** 已解锁常驻通知「复制验证码」动作请求码（ISSUE-P3-440） */
    private const val REQUEST_CODE_UNLOCKED_COPY_TOTP = 3006

    fun openAppForUnlockedStatus(context: Context): PendingIntent =
        openApp(context, REQUEST_CODE_UNLOCKED_STATUS)

    fun openAppForTotp(context: Context): PendingIntent =
        openApp(context, REQUEST_CODE_AUTOFILL_TOTP)

    fun openAppForSyncFailure(context: Context): PendingIntent =
        openApp(context, REQUEST_CODE_SYNC_FAILURE)

    /**
     * ISSUE-P3-386：常驻通知「立即锁定」动作。
     *
     * 走广播接收器 [VaultLockActionReceiver] → [com.keepasskey.app.security.AutoLockManager.triggerLock]
     * （与自动锁同收口），**不**另起 Activity、不打开主界面——用户离开设备时可直接锁库。
     */
    fun lockVaultNow(context: Context): PendingIntent {
        val intent = Intent(context, VaultLockActionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_UNLOCKED_LOCK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * `ISSUE-P3-440`：常驻通知「复制用户名」动作（`PD-69` 字段子集之一）。
     *
     * 与「立即锁定」同形态走广播接收器：点击后**不展开任何明文 UI**，
     * 由 [UnlockedNotificationCopyReceiver] 在后台完成读取与剪贴板写入。
     */
    fun copyUsernameFromNotification(context: Context): PendingIntent =
        copyFromNotification(context, REQUEST_CODE_UNLOCKED_COPY_USERNAME, UnlockedNotificationCopyField.USERNAME)

    /**
     * `ISSUE-P3-440`：常驻通知「复制验证码」动作（`PD-69` 字段子集之一）。
     *
     * 验证码属 30 秒级一次性动态值：走敏感剪贴板通道（`EXTRA_IS_SENSITIVE` + 定时擦除），
     * 与详情页 / 列表徽标复制同一链路。
     */
    fun copyTotpFromNotification(context: Context): PendingIntent =
        copyFromNotification(context, REQUEST_CODE_UNLOCKED_COPY_TOTP, UnlockedNotificationCopyField.TOTP)

    /**
     * 复制类动作的统一构造：**载荷只有字段名**（[UnlockedNotificationCopyField.extraValue]）——
     * 不含条目 id、不含任何条目内容，动作的作用对象在点击时从
     * [UnlockedNotificationEntryTracker] 现取。
     */
    private fun copyFromNotification(
        context: Context,
        requestCode: Int,
        field: UnlockedNotificationCopyField
    ): PendingIntent {
        val intent = Intent(context, UnlockedNotificationCopyReceiver::class.java)
            .putExtra(UnlockedNotificationCopyReceiver.EXTRA_COPY_FIELD, field.extraValue)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

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
