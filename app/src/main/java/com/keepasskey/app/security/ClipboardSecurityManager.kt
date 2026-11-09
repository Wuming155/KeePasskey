package com.keepasskey.app.security

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.keepasskey.app.R
import com.keepasskey.app.coroutines.guardedScope
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.session.SessionLockObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 剪贴板安全复制通道（ISSUE-P1-25 AC①）。
 *
 * 抽象出接口的目的：让 ViewModel 层「按内容敏感面选择通道」的决策可被纯 JVM 单测断言
 * （敏感通道必须设 `EXTRA_IS_SENSITIVE` 并调度自动擦除），无需 Android 剪贴板环境。
 */
interface ClipboardSecurityChannel {

    /** 复制敏感文本（设 `EXTRA_IS_SENSITIVE` + 调度自动擦除） */
    fun copySensitiveText(
        label: CharSequence,
        text: CharSequence,
        customTimeoutSeconds: Int? = null
    )

    /** 复制敏感 CharArray 载体（同受保护链路） */
    fun copySensitiveChars(
        label: CharSequence,
        chars: CharArray,
        customTimeoutSeconds: Int? = null
    )

    /** 复制普通文本（不标记敏感属性） */
    fun copyPlainText(label: CharSequence, text: CharSequence)

    /**
     * ISSUE-P3-360 AC③：本次敏感复制将调度的自动擦除秒数——null 表示**不会**自动清空
     * （用户关闭自动清空 / 超时配置 ≤ 0）。裁决与 [armScheduledClear] 同源走
     * [ClipboardClearPolicy.resolveScheduledTimeoutSeconds]，消息侧不得另行编造时长。
     * 默认实现返回 null（保守：不提示清空），纯 JVM 假通道无需覆写。
     */
    suspend fun scheduledClearSeconds(): Int? = null
}

/**
 * 剪贴板安全管理器。
 * 遵循安全与防御规范：
 * 1. 复制密码/敏感数据时注入 `ClipDescription.EXTRA_IS_SENSITIVE = true`——按平台文档语义，
 *    该标记是**渲染提示**：Android 13+ 的系统复制视觉确认（气泡/预览）不再显示其明文，
 *    **不改变剪贴板行为、也不为 ClipData 增加安全属性**；故本类不据此宣称
 *    「不进入剪贴板历史 / 云同步」（历史与云同步属系统 / 厂商实现面，本应用无法强制，见 ISSUE-P3-114）；
 * 2. 调度前后台擦除倒计时：前台到期核验摘要置空；后台受 Android 10+ 读取限制无法核验时，
 *    延后至回前台核验清空，避免在后台盲目清空误伤其它应用内容。
 *
 * ## 清理时机与各自语义（ENG-20261028-03 与 ENG03）
 *
 * | 时机 | 语义 |
 * | --- | --- |
 * | 定时到期 | 用户配置的保留时长；前台到期即清空；后台到期因平台读取限制无法核验所有权，延后至回前台核验清空，避免误清他处新值 |
 * | 熄屏（[Intent.ACTION_SCREEN_OFF]） | 立即触发清理；后台/锁屏不可读时标为已到期，延后至回前台核验清空 |
 * | 会话锁定（[SessionLockObserver]） | 立即触发清理；后台不可读时标为已到期，延后至回前台核验清空 |
 * | 切到后台 | **仅在未配置保留时长时**触发清理（关闭自动擦除 / 保留时长「不清空」/ ≤0）；配置了时长则不提前清，窗口由定时器负责 |
 * | 回到前台 | 延后清理入口：定时擦除已到期、熄屏或会话锁定等在后台未能核验时，按摘要核验补清；若已被外部覆盖则复位标记不误动他处内容 |
 * | 冷启动对账 | 保守清空（跨进程布尔标记；可能一并清除其它应用写入的内容，见产品裁决登记） |
 *
 * ## ENG03 整改：安全条件清理策略与空读误清修复
 *
 * 原实现后台读不到剪贴板时按「未见变更事件」旧摘要 fail-safe 清空，但 AOSP 官方实现
 * （`ClipboardService`）对无焦点后台应用抑制变更广播；后台未收事件绝不等于未被覆盖，
 * 盲目清空会误删第三方应用新内容。本类现：
 * 1. 实现 [SessionLockObserver]（会话锁定即清）+ 订阅 [Intent.ACTION_SCREEN_OFF]（熄屏即清）；
 * 2. 冷启动经 [reconcileOnColdStart] 对账——进程在敏感值驻留窗口内被 kill / force-stop 时
 *    自动擦除协程不会执行，故以一处**布尔**待清标记（无明文、无口令等价摘要）跨进程留存，
 *    冷启动读取到即 fail-safe 清空（已接受边界）；
 * 3. 覆盖写（[copyPlainText]）后置「已被超越」标志，确认为外部覆盖时（[markSuperseded]）及时清除待清标记；
 * 4. 空读分支（Android 10+ 后台无焦点限制）绝不物理清空，待清标记与截止期延后至回到前台核验（ENG03）。
 */
@Singleton
class ClipboardSecurityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ClipboardSecurityChannel, SessionLockObserver {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val scope = guardedScope(Dispatchers.Main, "ClipboardSecurityManager")
    private var clearJob: Job? = null
    private var lastSensitiveHash: ByteArray? = null

    /**
     * 本次待清敏感值是否**已调度**定时擦除（即用户配置了正数保留时长）。
     *
     * ENG-20261028-03：它同时决定「切到后台是否立即清空」——有窗口时后台不动，可用粘贴窗口与
     * 设置保持一致；无窗口（关闭自动擦除，或保留时长选「不清空」/ ≤0）时离开前台即清。
     * 由 [armScheduledClear] 依据设置解析结果置位。
     */
    private var pendingPasteWindowScheduled = false

    /** 本次待清敏感值的清理期限（`SystemClock.elapsedRealtime()` 基准）；[NO_DEADLINE] 表示无计划。 */
    private var pendingClearDeadlineAtMs = NO_DEADLINE

    /**
     * 本应用自己发起、尚未被变更回调抵消的剪贴板写次数（见 [clipChangedListener]）。
     *
     * 计数偏大 ⇒ 后续某次**外部**变更会被误当作自身写入而漏判覆盖，可能因此误清他处内容；
     * 故写入口先记账、写入抛异常时立即撤销（失败的写不会产生变更回调），并只接受
     * [lastSelfClipWriteAtMs] 起 [SELF_WRITE_ATTRIBUTION_WINDOW_MS] 内的抵消（未被派发的
     * 事件不会长期留下可用额度）。
     */
    @Volatile
    private var pendingSelfClipWrites = 0

    /** 最近一次本应用剪贴板写入的时刻（`SystemClock.elapsedRealtime()`，0 = 尚未写入过）。 */
    @Volatile
    private var lastSelfClipWriteAtMs = 0L

    /**
     * 自本次敏感复制以来是否发生了**可观测的覆盖写**：本应用覆盖写（如 [copyPlainText]），
     * 或 [clipChangedListener] 报告的其它应用写入。
     * ISSUE-P2-51 AC②：后台读不到剪贴板时，仅凭 `lastSensitiveHash` 相等就清空会误清
     * 已被用户/他应用覆盖的内容 —— 该标志为 true 时不得再按摘要匹配清空。
     */
    private var clipboardSuperseded = false

    /** 跨进程留存「可能存在未擦除敏感值」布尔标记（不含任何明文或摘要）。 */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) clearPendingSensitive()
        }
    }

    /**
     * 剪贴板变更监听（OnPrimaryClipChangedListener）。
     *
     * ⚠ **平台行为依据（ENG03）**：
     * 查证 AOSP 官方实现 `com.android.server.clipboard.ClipboardService`：
     * 其 `sendClipChangedBroadcast` 在分发事件前，会对每个 listener 显式校验
     * `clipboardAccessAllowed(AppOpsManager.OP_READ_CLIPBOARD, ...)`；
     * 该校验要求调用方具有窗口焦点（`isDefaultDeviceAndUidFocused` -> `mWm.isUidFocused(uid)`）或为默认 IME。
     * 故本应用处于后台且无输入焦点时，系统**不会**向本应用派发 `dispatchPrimaryClipChanged`！
     *
     * 因此，原先「剪贴板变更事件不受后台读取限制，故可以此证明无外部覆盖」的假设在 Android 10+（API 29+）
     * 上**不成立**。在后台未收到变更事件，绝对不能推定剪贴板未被其它应用修改。
     * 本 listener 仅在应用处于前台（拥有焦点）期间能可靠捕获外部修改（如分屏、悬浮窗或 IME 覆盖写）；
     * 后台到期清理策略见 [performClearIfMatching] 与 [ClipboardClearPolicy]。
     *
     * 回调由 `ClipboardManager` 在主线程 Handler 派发；本类的复制入口均在主线程调用。
     */
    private val clipChangedListener = object : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            if (isAttributableToOwnWrite()) {
                pendingSelfClipWrites--
                return
            }
            if (lastSensitiveHash == null) return
            clipboardSuperseded = true
        }
    }

    /**
     * 本次变更事件是否可归因于本应用自己的写入：存在未抵消的记账，且发生在最近一次自身写入的
     * [SELF_WRITE_ATTRIBUTION_WINDOW_MS] 时限内。超时限的记账视为失效（例如系统对同内容写入
     * 未再派发事件），不得用来抵消后续真正的外部覆盖事件。
     */
    private fun isAttributableToOwnWrite(): Boolean =
        pendingSelfClipWrites > 0 &&
            SystemClock.elapsedRealtime() - lastSelfClipWriteAtMs <= SELF_WRITE_ATTRIBUTION_WINDOW_MS

    /**
     * 切到后台 / 回到前台的处理。
     *
     * 后台（ENG-20261028-03）：仅在**没有可用粘贴窗口**（本次复制未调度定时擦除）时立即清空。
     * 有窗口时后台不清空、由定时器在到期时负责——否则「复制口令 → 切到目标应用粘贴」会在用户
     * 配置的时长之前失去剪贴板内容。无窗口时沿用 ISSUE-P3-84 AC② 的补偿控制：敏感值只在应用
     * 处于前台期间驻留，与设置页 `sec_clipboard_risk_notice` 告知用户的清空时机一致。
     *
     * 回前台（ENG03）：定时擦除、熄屏、会话锁定在后台触发时，若剪贴板不可读则延后物理清空并保留
     * deadline 与待清标记；回到前台（onStart / onResume）后剪贴板重新可读，此处按摘要核验：
     * 匹配则物理清空；不匹配则确认为外部覆盖，复位待清标记而不动他处内容；窗口未到期的复制不在此列。
     */
    private val backgroundObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (ClipboardClearPolicy.shouldClearOnBackgroundSwitch(pendingPasteWindowScheduled)) {
                clearPendingSensitive()
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            clearPendingSensitiveIfOverdue()
        }

        override fun onResume(owner: LifecycleOwner) {
            clearPendingSensitiveIfOverdue()
        }
    }

    private var initialized = false

    /**
     * 进程级冷启动初始化（幂等）：注册熄屏广播、剪贴板变更监听与前后台观察者。
     *
     * 由 `MainApplication.onCreate`（主线程、唯一冷启动点）调用——[ProcessLifecycleOwner.get]
     * 要求主线程，故不在构造期注册，避免自动填充 / 凭据提供者等后台入口构造本单例时越线程。
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        // 熄屏广播为系统保护广播，注册为「非导出」即可接收，无需权限声明。
        context.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED
        )
        // 监听与单例同生命周期：进程终止即随之失效，无需在此摘除。
        clipboardManager.addPrimaryClipChangedListener(clipChangedListener)
        ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundObserver)
    }

    /**
     * 复制敏感内容（如密码、PIN、TOTP 密钥）至剪贴板，并启动自动擦除倒计时
     *
     * ⚠ **ISSUE-P3-144：`customTimeoutSeconds` 被用户设置「否决」的语义**
     * [customTimeoutSeconds] **不能**强制擦除——`UserSettings.autoClearClipboard == false` 时，
     * 无论是否传入非 null 值都**不会**调度任何清除（判断顺序见 [armScheduledClear]）。
     * 任何「强制不可关闭的短擦除」需求**必须先改变该判断顺序**，本口径由回归用例锁定。
     *
     * @param label 剪贴板标签（展示给系统的提示）
     * @param text 敏感明文字符串
     * @param customTimeoutSeconds 自定义清空延迟（秒）。为 null 时遵从 UserSettings；**非 null 时仍受
     *   `autoClearClipboard == false` 否决**（详见上条与 [armScheduledClear]），`<= 0` 亦表示不清空
     */
    override fun copySensitiveText(
        label: CharSequence,
        text: CharSequence,
        customTimeoutSeconds: Int?
    ) {
        val clipData = ClipData.newPlainText(label, text)
        clipData.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        writingOwnClip { clipboardManager.setPrimaryClip(clipData) }

        armScheduledClear(sensitiveTextSha256(text), customTimeoutSeconds)
    }

    /**
     * ISSUE-P2-15：复制 CharArray 载体（受保护字段 / 生成结果）至受保护剪贴板。
     *
     * [chars] 为调用方持有的**借用副本**，本方法只读不写；方法返回前已完成 ClipData 序列化
     * 与摘要计算，调用方可安全地在 `finally` 中 `fill('0')`。应用侧全程不经 String 物化
     * （仅 Android 框架跨进程写入属系统边界），并与 [copySensitiveText] 复用同一自动擦除链路
     * （摘要算法一致，「当前剪贴板是否仍为先前敏感值」比对不受通道差异影响）。
     *
     * ⚠ **ISSUE-P3-144**：[customTimeoutSeconds] 的否决语义与 [copySensitiveText] 完全一致——
     * 用户关闭「自动擦除剪贴板」时，本通道的非 null 自定义秒数同样**不生效**。
     *
     * @param label 剪贴板标签（展示给系统的提示）
     * @param chars 借用副本，本方法只读不写
     * @param customTimeoutSeconds 自定义清空延迟（秒）；为 null 时遵从 UserSettings，
     *   **非 null 时仍受 `autoClearClipboard == false` 否决**，`<= 0` 亦表示不清空
     */
    override fun copySensitiveChars(
        label: CharSequence,
        chars: CharArray,
        customTimeoutSeconds: Int?
    ) {
        val clipData = ClipData.newPlainText(label, SensitiveCharSequence(chars))
        clipData.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        writingOwnClip { clipboardManager.setPrimaryClip(clipData) }

        armScheduledClear(sensitiveTextSha256(SensitiveCharSequence(chars)), customTimeoutSeconds)
    }

    /**
     * 记录敏感值摘要并（按用户配置）调度后台自动擦除。
     *
     * ⚠ **ISSUE-P3-144（否决语义，不得去掉本注释）**：清除**是否调度**以及**用几秒**由
     * [ClipboardClearPolicy.resolveScheduledTimeoutSeconds] 裁决，该函数内部**先**判
     * `settings.autoClearClipboard`，**后**才消费 [customTimeoutSeconds] ⇒ 用户关闭
     * 「自动擦除剪贴板」时，显式传入的非 null 自定义秒数被**静默否决**（不调度清除）。
     *
     * 任何「敏感路径强制**不可关闭**的短擦除」需求，**必须先把该判断顺序改为**
     * 「敏感路径无条件调度，仅把用户设置作为**上限**而非**开关**」并同步更新本 KDoc 与
     * [copySensitiveText] / [copySensitiveChars] / [ClipboardClearPolicy] 的 KDoc；
     * 直接照现状实现会产出本仓反复出现的**假加固**（声称强制、实际仍可关闭）。
     * 本顺序由 `ClipboardSecurityManagerScheduledClearTest` 的行为用例锁定（关闭开关时自定义
     * 秒数一律被否决，改判据即变红）；纯语句调序不改变结果，没有源码文本守卫。
     */
    private fun armScheduledClear(hash: ByteArray, customTimeoutSeconds: Int?) {
        lastSensitiveHash = hash
        // 新的敏感复制即「重置」覆盖写状态与清理计划，并留存跨进程待清标记
        clipboardSuperseded = false
        pendingPasteWindowScheduled = false
        pendingClearDeadlineAtMs = NO_DEADLINE
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, true).apply()
        scope.launch {
            val settings = settingsRepository.getSettings().first()
            // ISSUE-P3-144：判断顺序固定为「先查用户开关、后取自定义秒数」——
            // 该顺序即「显式 customTimeoutSeconds 被用户设置否决」的实现，不得互换。
            val timeoutSec = ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                customTimeoutSeconds = customTimeoutSeconds,
                autoClearClipboard = settings.autoClearClipboard,
                settingsTimeoutSeconds = settings.clipboardTimeoutSeconds
            )
            // ENG-20261028-03：解析结果同时决定「切到后台是否立即清空」。无定时窗口（用户关闭
            // 自动擦除，或秒数 <= 0：-1 / 0 表示不清空）时没有可等待的粘贴窗口，离开前台即清；
            // 有窗口则后台不动，由 scheduleClear 的定时器在到期时清空。
            pendingPasteWindowScheduled = timeoutSec != null
            if (timeoutSec != null) scheduleClear(timeoutSec, hash)
        }
    }

    /**
     * 复制普通文本（如用户名、网址），不标记敏感属性。
     *
     * ISSUE-P1-25 AC②：**不得**在此取消既有的敏感值自动擦除计划——此前无条件
     * `cancelScheduledClear()` 会把「上一次敏感复制」的擦除计划一并取消，使该敏感值
     * （若仍留在剪贴板）失去时间窗约束。
     *
     * ISSUE-P2-51 AC②：但覆盖写发生后面临另一风险——后台读不到剪贴板时，原 fail-safe
     * 分支会因 `lastSensitiveHash` 陈旧匹配而**误清**刚写入的普通内容。故此处仅置
     * [clipboardSuperseded]（计划保留但到期自然不动作）+ 清除跨进程待清标记。
     */
    override fun copyPlainText(label: CharSequence, text: CharSequence) {
        val clipData = ClipData.newPlainText(label, text)
        writingOwnClip { clipboardManager.setPrimaryClip(clipData) }
        clipboardSuperseded = true
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /**
     * ISSUE-P3-360 AC③：按用户设置现场裁决「将调度的清空秒数」（与 [armScheduledClear]
     * 的裁决函数、参数完全一致——消息文案与真实调度绝不能两处口径）。
     */
    override suspend fun scheduledClearSeconds(): Int? {
        val settings = settingsRepository.getSettings().first()
        return ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
            customTimeoutSeconds = null,
            autoClearClipboard = settings.autoClearClipboard,
            settingsTimeoutSeconds = settings.clipboardTimeoutSeconds
        )
    }

    /**
     * 本应用自己发起的一次剪贴板写。写操作本身也会触发 [clipChangedListener] 回调，必须先记账
     * 再写，否则该回调会被误判为「外部覆盖」，使到期清空被跳过而漏清敏感值。
     */
    private fun writingOwnClip(write: () -> Unit) {
        pendingSelfClipWrites++
        lastSelfClipWriteAtMs = SystemClock.elapsedRealtime()
        try {
            write()
        } catch (t: Throwable) {
            // 写失败不会产生变更回调，撤销预记计数，避免后续把外部事件误抵消。
            pendingSelfClipWrites--
            throw t
        }
    }

    /**
     * 调度定时清空逻辑：同时记录本次复制的清理期限（[clearPendingSensitiveIfOverdue] 依据），
     * 到期按摘要比对清空。
     */
    private fun scheduleClear(timeoutSeconds: Int, expectedHash: ByteArray) {
        clearJob?.cancel()
        pendingPasteWindowScheduled = true
        pendingClearDeadlineAtMs = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L
        clearJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            // ISSUE-P3-352 AC③：定时擦除是用户唯一在等待的清除时刻——**实际清掉才提示**
            // （返回 false = 已被他处覆盖 / 无残留，不误报）。熄屏 / 后台 / 锁定 / 冷启动
            // 对账路径刻意不提示：用户不在场，且后台文本 Toast 在 Android 12+ 被系统抑制。
            if (performClearIfMatching(expectedHash)) notifyClipboardCleared()
        }
    }

    /**
     * ISSUE-P3-352 AC③：定时擦除后的可见反馈（文案参照 Kp2a "Clipboard cleared."）。
     * ISSUE-P3-446：全局宿主可达即发 Snackbar；不可达**如实保留 Toast 兜底**
     * （发通道会滞留缓冲、迟至下次开主界面才弹出陈旧提示；后台 Toast 本就被系统抑制）。
     * 熄屏 / 后台 / 锁定 / 冷启动对账路径仍刻意不提示：用户不在场。
     */
    private fun notifyClipboardCleared() {
        if (AppSnackbarChannel.hostActive.value) {
            AppSnackbarChannel.trySend(AppSnackbarEvent(UiMessage(R.string.clipboard_auto_cleared)))
        } else {
            Toast.makeText(context, R.string.clipboard_auto_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 检查当前剪贴板是否仍为先前复制的敏感内容；若是则执行物理清空。
     *
     * 读得到内容时以**摘要**核验：匹配则物理清空；不匹配则确认为外部覆盖，复位待清状态而不动剪贴板。
     * 读不到内容时（Android 10+ 后台读取限制，primaryClip 为 null），因无法证明内容仍属本应用，
     * 严禁物理清空（避免误删其它应用或用户新写入的内容，ENG03 安全条件清理策略），
     * 待清状态延后至回到前台核验。
     */
    fun performClearIfMatching(expectedHash: ByteArray): Boolean {
        val currentText = getCurrentClipText()
        if (currentText == null) {
            // 安全条件清理策略（ENG03）：Android 10+ 后台限制导致 primaryClip 返回 null。
            // 后台不可读且无法接收变更广播 ⇒ 无法证明当前剪贴板值仍属本应用。
            // 后台无法证明值仍属本应用则不得物理清空他处内容；保留 deadline 与待清标记延后回前台核验。
            return false
        }
        val currentHash = sensitiveTextSha256(currentText)
        if (java.security.MessageDigest.isEqual(currentHash, expectedHash)) {
            markCleared()
            return true
        }
        // 读得到且不等于本应用写入值 ⇒ 确证已由外部/用户覆盖：
        // 复位待清记录与跨进程标记（避免冷启动误清他处新值），且严禁物理清空当前剪贴板。
        markSuperseded()
        return false
    }

    /**
     * ISSUE-P2-51：会话锁定 / 关闭时清理可能仍驻留的敏感剪贴板值
     * （由 `DatabaseSession` 经 [SessionLockObserver] 同步回调）。
     */
    override fun onSessionLocked() {
        clearPendingSensitive()
    }

    /**
     * 熄屏 / 会话锁定 / 无粘贴窗口切后台的统一入口。
     * 试图立即物理清空；若此时剪贴板不可读（后台或无焦点），将截止期标为已到期并保留待清标记，
     * 延后至回到前台核验清空，避免在无法证明所有权时误清他处内容。
     */
    private fun clearPendingSensitive() {
        val hash = lastSensitiveHash ?: return
        pendingClearDeadlineAtMs = SystemClock.elapsedRealtime()
        performClearIfMatching(hash)
    }

    /**
     * 回到前台时核验补清**已到期**的敏感值（ENG03）。
     *
     * 定时擦除到期、熄屏、会话锁定或无窗口后台切换若发生于后台/无焦点时，因无法证明内容归属
     * 而延后物理清空；回到前台后剪贴板重新可读，此处按摘要核验：
     * - 若仍为本应用写入的敏感值：物理清空并复位标记；
     * - 若已被外部覆盖：复位标记，不误动他处内容；
     * - 若回前台瞬间窗口焦点尚未完成 IPC 派发导致偶发空读：延后微任务重试一次。
     * 未到期时不得动剪贴板。
     */
    private fun clearPendingSensitiveIfOverdue() {
        val hash = lastSensitiveHash ?: return
        val deadline = pendingClearDeadlineAtMs
        if (deadline == NO_DEADLINE || SystemClock.elapsedRealtime() < deadline) return
        if (!performClearIfMatching(hash)) {
            // 若仍有待清记录且处于前台（说明非覆盖、仅因窗口焦点延迟导致空读），主线程微任务重试一次
            if (lastSensitiveHash != null &&
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                scope.launch {
                    delay(150L)
                    val retryHash = lastSensitiveHash ?: return@launch
                    val retryDeadline = pendingClearDeadlineAtMs
                    if (retryDeadline != NO_DEADLINE && SystemClock.elapsedRealtime() >= retryDeadline) {
                        performClearIfMatching(retryHash)
                    }
                }
            }
        }
    }

    /**
     * ISSUE-P2-51：冷启动对账。
     *
     * 进程在敏感值驻留窗口内被 kill / force-stop 时，[scheduleClear] 的延时协程不会执行，
     * 敏感值可能跨进程驻留至下次复制。跨进程仅留存一处**布尔**待清标记（无明文、无摘要），
     * 冷启动读到即 fail-safe 清空。默认 30 秒窗口内正常路径已由定时器清空，故本分支极少触发。
     */
    fun reconcileOnColdStart() {
        if (!prefs.getBoolean(KEY_PENDING_SENSITIVE, false)) return
        clearClipboard()
        lastSensitiveHash = null
        clipboardSuperseded = true
        pendingPasteWindowScheduled = false
        pendingClearDeadlineAtMs = NO_DEADLINE
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /** 确证已被外部覆盖：复位待清记录与跨进程标记，但**不**物理清空系统剪贴板。 */
    private fun markSuperseded() {
        lastSensitiveHash = null
        clipboardSuperseded = true
        pendingPasteWindowScheduled = false
        pendingClearDeadlineAtMs = NO_DEADLINE
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /** 清空并复位全部「待清」状态（摘要 / 覆盖写标志 / 清理计划 / 跨进程标记）。 */
    private fun markCleared() {
        clearClipboard()
        lastSensitiveHash = null
        clipboardSuperseded = true
        pendingPasteWindowScheduled = false
        pendingClearDeadlineAtMs = NO_DEADLINE
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /**
     * 立即物理清空系统主剪贴板（本应用发起的写，其变更回调由 [writingOwnClip] 抵消）
     */
    fun clearClipboard() {
        try {
            writingOwnClip { clipboardManager.clearPrimaryClip() }
        } catch (e: Exception) {
            // 回退兼容：置空文本
            val emptyClip = ClipData.newPlainText("", "")
            writingOwnClip { clipboardManager.setPrimaryClip(emptyClip) }
        }
    }

    /**
     * 取消进行中的自动清空计划
     */
    fun cancelScheduledClear() {
        clearJob?.cancel()
        clearJob = null
        lastSensitiveHash = null
        clipboardSuperseded = true
        pendingPasteWindowScheduled = false
        pendingClearDeadlineAtMs = NO_DEADLINE
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /**
     * 获取当前系统剪贴板文本
     */
    fun getCurrentClipText(): String? {
        val primaryClip = clipboardManager.primaryClip ?: return null
        if (primaryClip.itemCount > 0) {
            val item = primaryClip.getItemAt(0)
            return item.text?.toString()
        }
        return null
    }

    private companion object {
        const val PREFS_NAME = "clipboard_security"
        const val KEY_PENDING_SENSITIVE = "pending_sensitive_clip"

        /** [ClipboardSecurityManager.pendingClearDeadlineAtMs] 的「无清理计划」取值。 */
        const val NO_DEADLINE = Long.MIN_VALUE

        /**
         * 自身写入的变更事件抵消时限：事件在派发后立即排入主线程队列，正常远小于本时限；
         * 超出时限的记账不再用于抵消，防止「系统未派发某次自身写入事件」留下长期额度吞掉真实覆盖事件。
         */
        const val SELF_WRITE_ATTRIBUTION_WINDOW_MS = 5_000L
    }
}
