package com.keepasskey.app.security

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PersistableBundle
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.core.session.SessionLockObserver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * 2. 调度后台倒计时：超时后比对剪贴板当前内容哈希，若未被用户覆盖则自动物理置空，杜绝跨应用常驻泄密
 *    ——这是本类**实际强制**的保证（不受第三方实现影响）。
 *
 * ## ISSUE-P2-51（审计 F-18）整改：清理入口补齐 + 空读误清修复
 *
 * 原实现仅由 [performClearIfMatching]（定时器）触发清理，且后台读不到剪贴板时的
 * fail-safe 分支会因 `lastSensitiveHash` **陈旧匹配**而误清他处内容。本类现：
 * 1. 实现 [SessionLockObserver]（会话锁定即清）+ 订阅 [Intent.ACTION_SCREEN_OFF]（熄屏即清）；
 * 2. 冷启动经 [reconcileOnColdStart] 对账——进程在敏感值驻留窗口内被 kill / force-stop 时
 *    自动擦除协程不会执行，故以一处**布尔**待清标记（无明文、无口令等价摘要）跨进程留存，
 *    冷启动读取到即 fail-safe 清空；
 * 3. 覆盖写（[copyPlainText]）后置「已被超越」标志，空读分支据此**不再**误清。
 */
@Singleton
class ClipboardSecurityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : ClipboardSecurityChannel, SessionLockObserver {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clearJob: Job? = null
    private var lastSensitiveHash: ByteArray? = null

    /**
     * 自本次敏感复制以来是否发生了**可观测的覆盖写**（如 [copyPlainText]）。
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
     * ISSUE-P3-84 AC②：本应用切到后台（ProcessLifecycleOwner ON_STOP）即清空待清敏感值——
     * 敏感值只在「应用仍在前台」的窗口内允许驻留，用户切走即不再可能被其读取。
     */
    private val backgroundObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            clearPendingSensitive()
        }
    }

    private var initialized = false

    /**
     * 进程级冷启动初始化（幂等）：注册熄屏广播与后台切换观察者。
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
        clipboardManager.setPrimaryClip(clipData)

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
        clipboardManager.setPrimaryClip(clipData)

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
     * 本顺序由 `ClipboardSecurityManagerScheduledClearTest` 的源码守卫用例锁定。
     */
    private fun armScheduledClear(hash: ByteArray, customTimeoutSeconds: Int?) {
        lastSensitiveHash = hash
        // 新的敏感复制即「重置」覆盖写状态，并留存跨进程待清标记
        clipboardSuperseded = false
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, true).apply()
        scope.launch {
            val settings = settingsRepository.getSettings().first()
            // ISSUE-P3-144：判断顺序固定为「先查用户开关、后取自定义秒数」——
            // 该顺序即「显式 customTimeoutSeconds 被用户设置否决」的实现，不得互换。
            val timeoutSec = ClipboardClearPolicy.resolveScheduledTimeoutSeconds(
                customTimeoutSeconds = customTimeoutSeconds,
                autoClearClipboard = settings.autoClearClipboard,
                settingsTimeoutSeconds = settings.clipboardTimeoutSeconds
            ) ?: return@launch // 用户关闭自动擦除，或解析出的秒数 <= 0（-1 / 0 表示不清空）

            scheduleClear(timeoutSec, hash)
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
        clipboardManager.setPrimaryClip(clipData)
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
     * 调度定时清空逻辑
     */
    private fun scheduleClear(timeoutSeconds: Int, expectedHash: ByteArray) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            // ISSUE-P3-352 AC③：定时擦除是用户唯一在等待的清除时刻——**实际清掉才提示**
            // （返回 false = 已被他处覆盖 / 无残留，不误报）。熄屏 / 后台 / 锁定 / 冷启动
            // 对账路径刻意不提示：用户不在场，且后台文本 Toast 在 Android 12+ 被系统抑制。
            if (performClearIfMatching(expectedHash)) notifyClipboardCleared()
        }
    }

    /**
     * ISSUE-P3-352 AC③：定时擦除后的可见反馈（文案口径参照 Kp2a "Clipboard cleared."）。
     * 选 Toast 而非 Snackbar：清空时刻可能落在任意界面（十个 Screen 各持 SnackbarHost、
     * 无全局宿主），Toast 与 Kp2a 同型、零接线成本；[scope] 恒为主线程，满足 Toast 线程要求。
     */
    private fun notifyClipboardCleared() {
        Toast.makeText(
            context,
            context.getString(R.string.clipboard_auto_cleared),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 检查当前剪贴板是否仍为先前复制的敏感内容；若是则执行物理清空
     */
    fun performClearIfMatching(expectedHash: ByteArray): Boolean {
        val currentText = getCurrentClipText()
        if (currentText == null) {
            // P1-15 整改：Android 10+ 后台限制导致 primaryClip 返回 null。
            // ISSUE-P2-51 AC②：此时**不得**仅凭 lastSensitiveHash 相等就清空——
            // 若期间已发生覆盖写（clipboardSuperseded），匹配到的是「陈旧摘要」，
            // 清空会误伤他处内容。裁决下沉至 [ClipboardClearPolicy]，可被单测覆盖。
            if (ClipboardClearPolicy.shouldClearOnUnreadableClipboard(
                    recordedHash = lastSensitiveHash,
                    expectedHash = expectedHash,
                    superseded = clipboardSuperseded
                )
            ) {
                markCleared()
                return true
            }
            return false
        }
        val currentHash = sensitiveTextSha256(currentText)
        if (java.security.MessageDigest.isEqual(currentHash, expectedHash)) {
            markCleared()
            return true
        }
        return false
    }

    /**
     * ISSUE-P2-51：会话锁定 / 关闭时清理可能仍驻留的敏感剪贴板值
     * （由 `DatabaseSession` 经 [SessionLockObserver] 同步回调）。
     */
    override fun onSessionLocked() {
        clearPendingSensitive()
    }

    /** ISSUE-P2-51：熄屏 / 锁定的统一入口——存在待清敏感值时按摘要比对清空。 */
    private fun clearPendingSensitive() {
        val hash = lastSensitiveHash ?: return
        performClearIfMatching(hash)
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
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /** 清空并复位全部「待清」状态（摘要 / 覆盖写标志 / 跨进程标记）。 */
    private fun markCleared() {
        clearClipboard()
        lastSensitiveHash = null
        clipboardSuperseded = true
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, false).apply()
    }

    /**
     * 立即物理清空系统主剪贴板
     */
    fun clearClipboard() {
        try {
            clipboardManager.clearPrimaryClip()
        } catch (e: Exception) {
            // 回退兼容：置空文本
            val emptyClip = ClipData.newPlainText("", "")
            clipboardManager.setPrimaryClip(emptyClip)
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
    }
}
