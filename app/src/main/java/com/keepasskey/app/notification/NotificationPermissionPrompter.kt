package com.keepasskey.app.notification

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「是否已询问过通知权限」的持久化记录（ISSUE-P3-18 验收标准 2）。
 *
 * 只落一个布尔标志，不含任何用户数据。生产环境使用 [SharedPrefsNotificationPermissionAskStore]
 * 使标志跨冷启动存活（杜绝「杀进程即重置 → 每次启动再弹一次」）；JVM 单测注入内存实现。
 */
interface NotificationPermissionAskStore {
    fun wasAsked(): Boolean
    fun markAsked()
}

/**
 * [NotificationPermissionAskStore] 的 SharedPreferences 实现。
 */
@Singleton
class SharedPrefsNotificationPermissionAskStore @Inject constructor(
    @ApplicationContext context: Context
) : NotificationPermissionAskStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun wasAsked(): Boolean = prefs.getBoolean(K_ASKED, false)

    override fun markAsked() {
        prefs.edit().putBoolean(K_ASKED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "com.keepasskey.notification_permission"
        const val K_ASKED = "post_notifications_asked"
    }
}

/**
 * 通知权限状态与请求流程的唯一出口（ISSUE-P3-18 验收标准 2）。
 *
 * 决策本身由纯函数 [NotificationGate.shouldRequestNotificationPermission] 承担；
 * 本类只负责采集三项 Android 侧事实（是否已授权 / 是否已询问 / 是否需要解释）并落到系统 API。
 * 被拒绝时**不重试、不弹第二次**，相关通知一律静默降级（验收标准「不得崩溃或反复弹窗」）。
 */
@Singleton
class NotificationPermissionPrompter @Inject constructor(
    // 允许为 null 仅用于纯 JVM 单元测试注入（生产 DI 注入 @ApplicationContext）；
    // 注意不可设默认值——Kotlin 默认参数会生成合成无参构造器，与 @Inject 双构造器冲突
    @ApplicationContext private val context: Context?,
    private val askStore: NotificationPermissionAskStore
) {

    /** 通知权限当前是否可用（API 33+ 即 `POST_NOTIFICATIONS` 的授权结果，统一走 compat 层） */
    fun isGranted(): Boolean {
        val ctx = context ?: return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /**
     * 依闸门判定本次是否应发起权限请求（`activity` 仅用于查询「是否需要向用户解释」）。
     *
     * 返回 [NotificationPermissionDecision.REQUEST] 时**已**落「已询问」标志——先落标志再弹窗，
     * 进程在弹窗期间被杀也不会在下次冷启动重复打扰。
     */
    fun decide(activity: Activity): NotificationPermissionDecision = resolveDecision(
        permissionGranted = isGranted(),
        showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )
    )

    /**
     * 拾取到的 Android 事实 → 决策（与 Activity 解耦，便于 JVM 单测直接驱动「请求仅一次」状态机）。
     */
    fun resolveDecision(
        permissionGranted: Boolean,
        showRationale: Boolean
    ): NotificationPermissionDecision {
        val alreadyAsked = askStore.wasAsked()
        val request = NotificationGate.shouldRequestNotificationPermission(
            permissionGranted = permissionGranted,
            alreadyAsked = alreadyAsked,
            showRationale = showRationale
        )
        if (request) {
            askStore.markAsked()
        } else {
            AppLog.i(
                TAG,
                "不发起通知权限请求（已授权=$permissionGranted，已询问=$alreadyAsked，需解释=$showRationale）"
            )
        }
        return if (request) NotificationPermissionDecision.REQUEST else NotificationPermissionDecision.SKIP
    }

    /** 系统权限对话框回执：仅记录非敏感诊断日志；被拒绝时相关通知静默降级，不再重复请求 */
    fun onRequestResult(granted: Boolean) {
        AppLog.i(
            TAG,
            if (granted) "通知权限已授予" else "通知权限被拒绝：相关通知静默降级（不再重复请求）"
        )
    }

    private companion object {
        const val TAG = "NotificationPermission"
    }
}
