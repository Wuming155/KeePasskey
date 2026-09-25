package com.keepasskey.app.autofill.legacy

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillAccessPolicy
import com.keepasskey.app.autofill.AutofillAuthenticationPolicy
import com.keepasskey.app.autofill.AutofillRejection
import com.keepasskey.app.data.repository.AutofillBlocklistStore
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.notification.NotificationChannelSpec
import com.keepasskey.app.notification.NotificationChannels
import com.keepasskey.app.security.RuntimeIntegrityGate
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * 旧版无障碍自动填充通道（ISSUE-P3-324）。
 *
 * 面向「系统自动填充框架不可用」的应用 / 设备的兜底通道（参考 KeePassDX / keepass2android
 * 的无障碍填充通道）。语义与既有 `KeePasskeyAutofillService` 通道对齐：
 *
 * 1. **触发**：目标应用窗口出现「可编辑且无内容的口令框」（[LegacyFieldPolicy] 判定）时
 *    发通知；口令框是唯一锚点，普通文本界面不触发；
 * 2. **放行前求值**（缺一不发）：应用内「旧版自动填充服务」开关开启 + 库已解锁 +
 *    完整性闸门放行（[RuntimeIntegrityGate.awaitEnforcement]）+ 非本应用窗口（自我排除，
 *    对齐 ISSUE-P2-226）+ 非自动填充黑名单（对齐 TASK-44）；
 * 3. **交付**：通知点按进 [LegacyFillPickerActivity]（受保护窗口 + 生物识别确认），
 *    用户选中条目后经 [LegacyAutofillCoordinator] 递交回本服务，重扫当前窗口并复核
 *    「窗口包名 = 通知时的目标包名」后才以 `ACTION_SET_TEXT` 回填——不在陈旧窗口上写入；
 * 4. **默认关闭**：开关（出厂 false）与「系统侧启用本服务」构成双闸门，任一缺失即整条静默。
 *
 * 敏感数据纪律：本服务**不物化**任何窗口文本（[LegacyFieldScanner] 只读元数据）；
 * 口令只在「用户显式选中 → 回填」一段以 String 承载（API 硬约束，同 ISSUE-P2-15 口径），
 * 不落状态流 / 日志（[LegacyAutofillCoordinator.PendingFill.toString] 已脱敏）。
 */
@AndroidEntryPoint
class LegacyAutofillAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var runtimeIntegrityGate: RuntimeIntegrityGate

    @Inject
    lateinit var settingsStore: ExtendedSettingsStore

    // TASK-44：自动填充黑名单（命中即不发通知，与本通道的填充侧同口径）
    @Inject
    lateinit var autofillBlocklistStore: AutofillBlocklistStore

    @Inject
    lateinit var coordinator: LegacyAutofillCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 同一目标窗口的提示去抖（上次通知时刻；0 = 从未通知） */
    private val lastNotifyAtMillis = AtomicLong(0L)

    override fun onServiceConnected() {
        super.onServiceConnected()
        observePendingFills()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val eventPkg = e.packageName?.toString().orEmpty()
        if (eventPkg.isEmpty()) return
        // 配置已限定事件类型，这里再短路一次：仅窗口切换 / 内容变化值得扫描
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        // 自我排除（ISSUE-P2-226 同口径）：本应用自身的窗口（含选择器页）绝不触发
        if (AutofillAccessPolicy.isSelfApp(eventPkg, packageName)) return

        serviceScope.launch { evaluateAndOffer(eventPkg) }
    }

    override fun onInterrupt() {
        // 通道无持续音频/振动反馈，系统中断无对象可处理；留空实现接口契约
    }

    override fun onDestroy() {
        // 未决回填载荷（明文凭据）随服务销毁一并丢弃，杜绝跨窗口滞留
        coordinator.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 事件触发的放行判定与提示：全部闸门通过且窗口存在可填充口令框才发通知。
     * 判定顺序刻意**先廉价后昂贵**（开关 → 锁定 → 窗口包名 → 闸门/黑名单 → 扫描）。
     */
    private suspend fun evaluateAndOffer(eventPkg: String) {
        if (!settingsStore.isAutofillLegacyAccessibilityEnabled()) return
        if (vaultRepository.isLocked()) return

        val root = withContext(Dispatchers.Main) { rootInActiveWindow } ?: return
        val targetPkg = withContext(Dispatchers.Main) { root.packageName?.toString().orEmpty() }
        // 事件包名与活动窗口不一致（如输入法窗口 / 过渡动画）：以活动窗口为准，直接忽略
        if (targetPkg.isEmpty() || targetPkg != eventPkg) return
        if (AutofillAccessPolicy.isSelfApp(targetPkg, packageName)) return

        val rejection = AutofillAccessPolicy.rejectReason(
            runtimeIntegrityGate.awaitEnforcement(),
            targetPkg,
            packageName,
            autofillBlocklistStore::isBlocked
        )
        if (rejection != null) {
            // 日志不携带调用包名等敏感标识（ISSUE-P1-10 语义）
            AppLog.i(TAG, "旧版通道放行判定未通过：$rejection")
            return
        }

        val records = withContext(Dispatchers.Main) { LegacyFieldScanner.collect(root) }
        if (LegacyFieldPolicy.selectFields(records.map { it.probe }) == null) return
        postOfferNotification(targetPkg)
    }

    /** 发「检测到口令框」通知（同 id 复用去重 + 同窗口去抖） */
    private fun postOfferNotification(targetPkg: String) {
        val now = System.currentTimeMillis()
        val last = lastNotifyAtMillis.get()
        if (now - last < NOTIFY_DEBOUNCE_MS) return
        if (!lastNotifyAtMillis.compareAndSet(last, now)) return

        val intent = Intent(this, LegacyFillPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(LegacyFillPickerActivity.EXTRA_TARGET_PACKAGE, targetPkg)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_FILL,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, NotificationChannelSpec.LEGACY_AUTOFILL.channelId)
            .setSmallIcon(NotificationChannels.SMALL_ICON_RES)
            .setContentTitle(getString(R.string.legacy_autofill_notification_title))
            .setContentText(getString(R.string.legacy_autofill_notification_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
                ?: return@runCatching
            manager.notify(NotificationChannels.ID_LEGACY_AUTOFILL, notification)
        }.onFailure {
            // 通知权限未授予 / 通道被关：如实落日志，不影响服务存活（fail-open 仅限提示面）
            AppLog.w(TAG, "旧版通道通知发送失败（通知权限或通道可能被关闭）", it)
        }
    }

    /** 观察选择器递交的待决回填载荷（单协程顺序消费，填完一条再看下一条） */
    private fun observePendingFills() {
        serviceScope.launch {
            coordinator.pending.collect { _ ->
                val fill = coordinator.consume() ?: return@collect
                performFill(fill)
            }
        }
    }

    /**
     * 回填：消费即清载荷 → 库锁定复核 → 当前窗口包名复核 → 重扫字段 → `ACTION_SET_TEXT`。
     * 任何一步不成立即整体放弃（宁可不填，绝不在错误的窗口 / 陈旧的会话上写入明文）。
     */
    private suspend fun performFill(fill: LegacyAutofillCoordinator.PendingFill) {
        // 交付前锁定复核（与选择器 ISSUE-P3-201 同口径）：锁定即丢弃
        if (!AutofillAuthenticationPolicy.canDeliverAuthResult(vaultRepository.isLocked())) {
            AppLog.w(TAG, "会话已锁定，丢弃旧版通道回填")
            return
        }
        val root = withContext(Dispatchers.Main) { rootInActiveWindow } ?: return
        val currentPkg = withContext(Dispatchers.Main) { root.packageName?.toString().orEmpty() }
        // 用户可能已离开通知对应的应用：包名不符即放弃（不跨窗口误填）
        if (currentPkg != fill.targetPackage) {
            AppLog.i(TAG, "当前窗口与通知目标不一致，放弃旧版通道回填")
            return
        }
        val records = withContext(Dispatchers.Main) { LegacyFieldScanner.collect(root) }
        val pair = LegacyFieldPolicy.selectFields(records.map { it.probe })
        if (pair == null) {
            AppLog.i(TAG, "当前窗口已无可填充口令框，放弃旧版通道回填")
            return
        }
        val delivered = withContext(Dispatchers.Main) {
            pair.usernameIndex?.let { idx -> fillViaSetText(records[idx].node, fill.username) }
            fillViaSetText(records[pair.passwordIndex].node, fill.password)
        }
        if (delivered) {
            AppLog.i(TAG, "旧版通道回填完成（口令框已写入）")
        } else {
            AppLog.w(TAG, "旧版通道回填未完成（目标框拒绝 SET_TEXT）")
        }
    }

    /** 对单个节点执行 `ACTION_SET_TEXT`（须在主线程调用） */
    private fun fillViaSetText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .getOrElse { false }
    }

    companion object {
        internal const val TAG = "LegacyAutofill"

        /** 同一目标的提示去抖窗口：内容变化事件高频触发，窗口内只提示一次 */
        internal const val NOTIFY_DEBOUNCE_MS = 2_000L

        /** 通知 PendingIntent 的 requestCode（与本服务无其它 PendingIntent 共存，具名防撞） */
        private const val REQUEST_CODE_FILL = 2401
    }
}
