package com.keepasskey.app.autofill

import android.content.Context
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.EntryTotpSnapshot
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.notification.TotpNotificationPublisher
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 填充后 TOTP 二次动作（复制 / 通知）的硬超时预算：超出即放弃，保证填充回传不被拖慢 */
internal const val POST_FILL_TOTP_TIMEOUT_MS = 500L

/**
 * 「填充交付成功 → TOTP 二次动作」的共用实现（ISSUE-P3-186）。
 *
 * 此前唯一落点是确认页（`AutofillConfirmActivity.handleTotpAfterConfirm`），手动选择器路径
 * （[AutofillPickerActivity.deliver]）构造数据集回传后直接结束，无任何 TOTP 处理——
 * 设置页「填充后自动将 TOTP 动态码复制到剪贴板 / 发送验证码通知」的承诺在选择器路径
 * 静默落空。现把「读偏好 → 500ms 硬超时取 TOTP 快照 → 按偏好复制 / 发通知」收敛为本类
 * 一份实现，确认页与选择器两条填充路径共用，杜绝同语义两处实现再次漂移。
 *
 * 守卫（与确认页原实现逐条一致）：
 * - 两开关（`autofillCopyTotp` / `autofillShowTotpNotification`）皆关 → 不触达仓库
 *   （零额外开销、零副作用）；
 * - [POST_FILL_TOTP_TIMEOUT_MS] 硬超时：TOTP 计算属纯 HMAC 运算（毫秒级），超时即放弃
 *   本次二次动作，绝不拖住填充回传；
 * - 取消异常继续上抛（不得被结果兜底吞掉，否则协程取消语义被破坏）；其余异常降级为放弃并留痕；
 * - 条目无 TOTP（快照为 null / 计算失败）→ 不触碰剪贴板也不发通知；库锁定时仓库读取
 *   必然失败 → 同样放弃（锁定态不触碰任何二次动作）。
 */
@Singleton
class AutofillPostFillTotpActions @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val settingsStore: ExtendedSettingsStore,
    // ISSUE-P3-186：依赖通道接口而非实现——与 ViewModel 层同口径，决策路径可被纯 JVM 单测断言
    private val clipboardSecurityChannel: ClipboardSecurityChannel,
    private val totpNotificationPublisher: TotpNotificationPublisher,
    @ApplicationContext private val context: Context
) {

    /**
     * 填充交付后按偏好执行 TOTP 二次动作；任何异常 / 超时都不抛出，
     * 调用方（确认页 / 选择器）的填充回传不受影响。
     */
    suspend fun runAfterFill(entryId: String) {
        try {
            runPostFillTotpActions(
                entryId = entryId,
                copyEnabled = settingsStore.isAutofillCopyTotpEnabled(),
                notifyEnabled = settingsStore.load().autofillShowTotpNotification,
                calculateTotp = { vaultRepository.calculateEntryTotp(it) },
                copyToClipboard = { code ->
                    clipboardSecurityChannel.copySensitiveText(
                        label = context.getString(R.string.autofill_totp_clip_label),
                        text = code
                    )
                },
                publishNotification = { code, periodSeconds ->
                    totpNotificationPublisher.publish(code = code, periodSeconds = periodSeconds)
                }
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w(TAG, "填充后 TOTP 二次动作失败，不阻断填充回传", t)
        }
    }

    companion object {
        private const val TAG = "AutofillPostFillTotp"
    }
}

/**
 * TOTP 二次动作的可单测内核（策略判定与超时放弃路径见 `AutofillPostFillTotpActionsTest`）。
 *
 * 两个偏好相互独立（`autofillCopyTotp` / `autofillShowTotpNotification`），任一开启都会
 * 触发一次 TOTP 计算；两者皆关时不触达仓库（零开销、零副作用）。
 * `autofillCopyTotp` 沿用既有单键读取接口；`autofillShowTotpNotification` 暂无单键接口，
 * 经整体读取取得——本路径每次用户填充仅执行一次，全量读取成本可忽略。
 */
internal suspend fun runPostFillTotpActions(
    entryId: String,
    copyEnabled: Boolean,
    notifyEnabled: Boolean,
    calculateTotp: suspend (String) -> EntryTotpSnapshot?,
    copyToClipboard: (String) -> Unit,
    publishNotification: (code: String, periodSeconds: Int) -> Unit,
    timeoutMillis: Long = POST_FILL_TOTP_TIMEOUT_MS
) {
    if (entryId.isBlank()) return
    if (!copyEnabled && !notifyEnabled) return

    // 硬超时：超时即放弃本次二次动作，绝不拖住填充回传；
    // 取消异常必须继续上抛（不得被结果兜底吞掉，否则协程取消语义被破坏）
    val snapshot = withTimeoutOrNull(timeoutMillis) {
        try {
            calculateTotp(entryId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    } ?: return

    if (AutofillTotpCopyPolicy.shouldCopy(copyTotpEnabled = copyEnabled, snapshot = snapshot)) {
        copyToClipboard(snapshot.code)
    }
    if (notifyEnabled) {
        // 通知只含验证码与剩余秒数，不含任何条目标识（详见 TotpNotificationPublisher 注释；
        // publish 内部另有偏好 + 通知权限双闸门复核）
        publishNotification(snapshot.code, snapshot.periodSeconds)
    }
}
