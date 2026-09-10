package com.keepasskey.app.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import com.keepasskey.app.data.repository.SettingsRepository
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
 * 剪贴板安全管理器。
 * 遵循安全与防御规范：
 * 1. 复制密码/敏感数据时注入 ClipDescription.EXTRA_IS_SENSITIVE = true，阻断 Android 13+ 系统浮动气泡明文窥探；
 * 2. 调度后台倒计时：超时后比对剪贴板当前内容哈希，若未被用户覆盖则自动物理置空，杜绝跨应用常驻泄密。
 */
@Singleton
class ClipboardSecurityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clearJob: Job? = null
    private var lastSensitiveHash: ByteArray? = null

    /**
     * 复制敏感内容（如密码、PIN、TOTP 密钥）至剪贴板，并启动自动擦除倒计时
     * @param label 剪贴板标签（展示给系统的提示）
     * @param text 敏感明文字符串
     * @param customTimeoutSeconds 自定义清空延迟（秒），若为 null 则遵从 UserSettings
     */
    fun copySensitiveText(
        label: CharSequence,
        text: CharSequence,
        customTimeoutSeconds: Int? = null
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
    fun copySensitiveChars(
        label: CharSequence,
        chars: CharArray,
        customTimeoutSeconds: Int? = null
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
        scope.launch {
            val settings = settingsRepository.getSettings().first()
            if (!settings.autoClearClipboard) return@launch

            val timeoutSec = customTimeoutSeconds ?: settings.clipboardTimeoutSeconds
            if (timeoutSec <= 0) return@launch // -1 或 0 表示不清空

            scheduleClear(timeoutSec, hash)
        }
    }

    /**
     * 复制普通文本（如用户名、网址），不标记敏感属性
     */
    fun copyPlainText(label: CharSequence, text: CharSequence) {
        cancelScheduledClear()
        val clipData = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clipData)
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
            // P1-15 整改：Android 10+ 后台限制导致 primaryClip 返回 null；
            // 此时只要最后一次复制的哈希仍匹配记录，执行 fail-safe 保护清空
            if (lastSensitiveHash?.contentEquals(expectedHash) == true) {
                clearClipboard()
                lastSensitiveHash = null
                return true
            }
            return false
        }
        val currentHash = sensitiveTextSha256(currentText)
        if (java.security.MessageDigest.isEqual(currentHash, expectedHash)) {
            clearClipboard()
            lastSensitiveHash = null
            return true
        }
        return false
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
}
