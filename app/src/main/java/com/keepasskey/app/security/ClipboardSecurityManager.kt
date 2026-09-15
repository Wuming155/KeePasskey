package com.keepasskey.app.security

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PersistableBundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
     * @param label 剪贴板标签（展示给系统的提示）
     * @param text 敏感明文字符串
     * @param customTimeoutSeconds 自定义清空延迟（秒），若为 null 则遵从 UserSettings
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

    /** 记录敏感值摘要并（按用户配置）调度后台自动擦除 */
    private fun armScheduledClear(hash: ByteArray, customTimeoutSeconds: Int?) {
        lastSensitiveHash = hash
        // 新的敏感复制即「重置」覆盖写状态，并留存跨进程待清标记
        clipboardSuperseded = false
        prefs.edit().putBoolean(KEY_PENDING_SENSITIVE, true).apply()
        scope.launch {
            val settings = settingsRepository.getSettings().first()
            if (!settings.autoClearClipboard) return@launch

            val timeoutSec = customTimeoutSeconds ?: settings.clipboardTimeoutSeconds
            if (timeoutSec <= 0) return@launch // -1 或 0 表示不清空

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
     * 调度定时清空逻辑
     */
    private fun scheduleClear(timeoutSeconds: Int, expectedHash: ByteArray) {
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(timeoutSeconds * 1000L)
            performClearIfMatching(expectedHash)
        }
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

/**
 * 剪贴板「空读」分支的纯裁决逻辑（ISSUE-P2-51 AC②，自 [ClipboardSecurityManager] 抽出以便单测）。
 *
 * Android 10+ 后台读不到主剪贴板（`primaryClip == null`），原实现仅凭「记录摘要 == 计划摘要」
 * 即 fail-safe 清空；但若期间已发生覆盖写（如 `copyPlainText`），该匹配命中的是**陈旧摘要**，
 * 清空会误伤他处内容。故此处显式要求「未发生覆盖写」。
 */
internal object ClipboardClearPolicy {

    /**
     * @param recordedHash 记录的最后敏感值摘要（null=无待清敏感值）
     * @param expectedHash 本次计划要清空的摘要
     * @param superseded 自本次敏感复制以来是否已发生可观测的覆盖写
     * @return 是否应执行清空
     */
    fun shouldClearOnUnreadableClipboard(
        recordedHash: ByteArray?,
        expectedHash: ByteArray,
        superseded: Boolean
    ): Boolean {
        if (superseded) return false
        if (recordedHash == null) return false
        return java.security.MessageDigest.isEqual(recordedHash, expectedHash)
    }
}
