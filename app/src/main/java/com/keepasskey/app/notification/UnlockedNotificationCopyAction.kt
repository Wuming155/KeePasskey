package com.keepasskey.app.notification

import android.content.Context
import android.widget.Toast
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.AppSnackbarChannel
import com.keepasskey.app.ui.AppSnackbarEvent
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.keepasskey.app.coroutines.guardedScope

/**
 * 「已解锁」常驻通知的**复制快捷动作**可作用字段。
 *
 * **字段子集即安全裁决结论**（`ISSUE-P3-440` 前置硬门，落 `PD-69`）：
 * 只允许 `用户名` 与 `TOTP 动态码`——两者属低敏面（用户名是公开登录标识；TOTP 是 30 秒级
 * 一次性码，过期即失效）；**密码与受保护自定义字段一律禁止出现**在通知动作上
 * （通知动作是「零二次确认」的一键路径，主密钥级机密不得经此出库）。
 *
 * [extraValue] 是跨 PendingIntent 传递的唯一载荷——**刻意不含条目 id 或任何用户数据**，
 * 作用对象在点击时从 [UnlockedNotificationEntryTracker] 现取。
 */
enum class UnlockedNotificationCopyField(val extraValue: String) {
    USERNAME("username"),
    TOTP("totp");

    companion object {
        /** 未知 / 缺失的 extra 一律返回 null（接收器据此静默忽略，绝不猜测字段）。 */
        fun fromExtra(value: String?): UnlockedNotificationCopyField? =
            entries.firstOrNull { it.extraValue == value }
    }
}

/**
 * 常驻通知复制动作的执行体（`ISSUE-P3-440` AC①）。
 *
 * 契约：
 * - **不展开任何明文 UI**（AC②）：全部动作在后台完成，反馈只有 Snackbar（主界面可达时）
 *   或 Toast 兜底——绝不打开条目详情 / 编辑页；
 * - 复用既有剪贴板安全通道 [ClipboardSecurityChannel]（`EXTRA_IS_SENSITIVE` 标记 +
 *   用户设置的定时擦除 + 熄屏 / 后台 / 锁库清除链），不新开第二条写剪贴板路径；
 * - **锁库第二道闸门**：动作点击时若会话已非解锁态（通知此时已被撤销，但残留点击仍可能到达），
 *   直接放弃——不读条目、不写剪贴板；
 * - 取值一律经仓库现读（`getKdbxEntry` / `calculateEntryTotp`），不用通知侧缓存的字段值。
 *
 * 协程：自持受控 Application 级 [scope]（SupervisorJob + Default），与控制器同形态，
 * 禁止裸 GlobalScope；接收器经 `goAsync()` 等待本次动作结束。
 */
@Singleton
class UnlockedNotificationCopyAction @Inject constructor(
    @ApplicationContext private val context: Context,
    private val entryTracker: UnlockedNotificationEntryTracker,
    private val vaultRepository: VaultRepository,
    private val clipboard: ClipboardSecurityChannel,
    private val databaseSession: DatabaseSession
) {

    private val scope = guardedScope(Dispatchers.Default)

    /**
     * 执行一次复制；[onFinished] 在动作结束后（无论成败）于协程侧回调，
     * 供广播接收器 `PendingResult.finish()` 收尾。
     */
    fun perform(field: UnlockedNotificationCopyField, onFinished: () -> Unit = {}) {
        scope.launch {
            try {
                copy(field)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // 异常不携带敏感载荷；只如实回报「复制失败」，不落日志细节
                notify(UiMessage(R.string.clipboard_copy_failed, isError = true))
            } finally {
                onFinished()
            }
        }
    }

    private suspend fun copy(field: UnlockedNotificationCopyField) {
        if (!NotificationGate.isUnlockedState(databaseSession.state.value)) return
        val entryId = entryTracker.entryId.value ?: return
        when (field) {
            UnlockedNotificationCopyField.USERNAME -> copyUsername(entryId)
            UnlockedNotificationCopyField.TOTP -> copyTotp(entryId)
        }
    }

    private suspend fun copyUsername(entryId: String) {
        val username = vaultRepository.getKdbxEntry(entryId)?.userName.orEmpty()
        if (username.isBlank()) {
            // 复用列表行复制用户的「未配置」文案（同一语义，不另造一份）
            notify(UiMessage(R.string.vault_copy_username_missing, isError = true))
            return
        }
        val copied = runCatching { clipboard.copyPlainText(clipLabel(), username) }.isSuccess
        notify(
            if (copied) UiMessage(R.string.notification_copy_username_done)
            else UiMessage(R.string.clipboard_copy_failed, isError = true)
        )
    }

    private suspend fun copyTotp(entryId: String) {
        val code = vaultRepository.calculateEntryTotp(entryId)?.code?.takeIf { it.isNotBlank() }
        if (code == null) {
            notify(UiMessage(R.string.vault_copy_totp_missing, isError = true))
            return
        }
        val copied = runCatching { clipboard.copySensitiveText(clipLabel(), code) }.isSuccess
        notify(
            if (copied) UiMessage(R.string.notification_copy_totp_done)
            else UiMessage(R.string.clipboard_copy_failed, isError = true)
        )
    }

    /** 剪贴板标签取**固定通用文案**：绝不把条目名 / 用户名写进系统剪贴板描述。 */
    private fun clipLabel(): CharSequence = context.getString(R.string.notification_copy_clip_label)

    /**
     * 反馈（AC②）：主界面可达时走全局 Snackbar（导航不中断即可见），
     * 否则 Toast 兜底——与剪贴板定时清空提示同一口径（见 [ClipboardSecurityChannel] 实现）。
     */
    private fun notify(message: UiMessage) {
        if (AppSnackbarChannel.hostActive.value) {
            AppSnackbarChannel.trySend(AppSnackbarEvent(message))
        } else {
            Toast.makeText(context, message.resId, Toast.LENGTH_SHORT).show()
        }
    }
}
