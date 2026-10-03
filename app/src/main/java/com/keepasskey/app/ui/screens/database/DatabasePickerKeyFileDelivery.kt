package com.keepasskey.app.ui.screens.database

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.KeyFileVaultCopyStore
import com.keepasskey.app.ui.model.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 生成型密钥文件的**一次性交付**状态机（ISSUE-P3-21 验收 2）。
 *
 * 自 `DatabasePickerViewModel` 拆出（§391 行数分档闸门，**纯结构性改动、行为逐字不变**）：
 * 建库时若生成了密钥文件，它是复合密钥的第二因子——**不保存即永久无法解锁**，故本类只承载
 * 「待保存提示 → SAF 写盘 → 关闭提示」这一段状态，写盘复用既有导出通道
 * [VaultRepository.exportKeyFileBytes]，**不新造**第二条密钥文件生成 / 读取路径。
 *
 * ISSUE-P2-460 AC②/AC③：
 * - 建库成功即收编副本（收编在 VM 建库成功路径），故 [saveTo] 在会话已锁、导出通道取不到
 *   字节时**回落本库私有目录副本**——交付不再因会话失效而必然失败；
 * - 导出与副本都取不到字节时，显式提示「密钥文件已丢失」，**不得**再静默报「保存失败」
 *   （真机取证：§408 建库窗口弹窗宿主随会话锁定被拆，导出恒空、用户只见「保存失败」一条）。
 *
 * @param publish 反馈消息出口（VM 的 `publishPickerMessage`，保持消息通道单一）
 */
internal class DatabasePickerKeyFileDeliveryController(
    private val vaultRepository: VaultRepository,
    private val appContext: Context?,
    private val scope: CoroutineScope,
    private val publish: (UiMessage) -> Unit,
    /** ISSUE-P2-460 AC③：私有目录收编副本通道（可空 = 单测未装配） */
    private val vaultCopyStore: KeyFileVaultCopyStore? = null
) {

    /** ISSUE-P3-21：生成型密钥文件的一次性交付状态（Screen 据此弹出强制保存提示） */
    private val deliveryFlow = MutableStateFlow<KeyFileDeliveryState>(KeyFileDeliveryState.None)
    val delivery: StateFlow<KeyFileDeliveryState> = deliveryFlow.asStateFlow()

    /** 本次交付对应库的 id（副本键；建库成功路径与选中事件同一取值口径） */
    private var pendingDbId: String? = null

    /** 建库成功后挂出「待一次性保存」提示（[dbId] 为新库在目录中的登记 id） */
    fun requestSave(vaultName: String, dbId: String) {
        pendingDbId = dbId
        deliveryFlow.value = KeyFileDeliveryState.PendingSave(suggestedFileName = suggestedFileName(vaultName))
    }

    /**
     * 把生成型密钥文件写入用户选定的 SAF 目标（ISSUE-P3-21 验收 2）。
     *
     * 字节来源两级：既有导出通道 [VaultRepository.exportKeyFileBytes]（会话绑定快照）→
     * 失败时回落本库收编副本（ISSUE-P2-460 AC③）。两级都取不到即显式「密钥文件已丢失」，
     * 绝不写空文件冒充成功、也不得只报一句「保存失败」。
     */
    fun saveTo(targetUri: Uri) {
        scope.launch {
            val bytes = resolveDeliveryBytes()
            if (bytes == null) {
                // AC③：两级都取不到 ⇒ 密钥文件确已丢失，显式分型提示（不得静默「保存失败」）
                publish(UiMessage(R.string.db_picker_keyfile_lost))
                return@launch
            }
            val resolver = appContext?.contentResolver
            if (resolver == null) {
                // 禁止静默失败：没有写盘上下文时如实告知用户，提示保持驻留
                bytes.fill(0)
                publish(UiMessage(R.string.db_picker_keyfile_save_failed))
                return@launch
            }
            val written = withContext(Dispatchers.IO) {
                try {
                    resolver.openOutputStream(targetUri)?.use { output ->
                        output.write(bytes)
                        output.flush()
                        true
                    } ?: false
                } catch (_: Exception) {
                    // 异常不外泄内容（可能是提供方拒绝/磁盘满），统一由下方语义化提示承接
                    false
                } finally {
                    // 密钥文件字节副本用毕即擦（会话内仍持有自己的副本供后续导出）
                    bytes.fill(0)
                }
            }
            if (written) {
                deliveryFlow.value = KeyFileDeliveryState.None
                publish(UiMessage(R.string.db_picker_keyfile_saved))
            } else {
                publish(UiMessage(R.string.db_picker_keyfile_save_failed))
            }
        }
    }

    /**
     * AC③ 分型内核：交付字节的两级解析——既有导出通道（会话绑定快照）→ 本库私有目录副本。
     * 返回 null = 两级都取不到 ⇒ 密钥文件确已丢失（调用方必须显式提示，不得静默）。
     * 返回的数组所有权移交调用方（用毕 `fill(0)` 清零）。
     * `internal` 拆出供 JVM 单测直接驱动分型（`android.net.Uri` 无 JVM 实现，无法实例化）。
     */
    internal suspend fun resolveDeliveryBytes(): ByteArray? {
        val sessionBytes = vaultRepository.exportKeyFileBytes().getOrNull()
        if (sessionBytes != null) return sessionBytes
        return vaultCopyStore?.load(pendingDbId.orEmpty())?.bytes
    }

    /** 用户显式选择「暂不保存密钥文件」：关闭一次性提示（不清会话缓存，可经设置页导出补存） */
    fun dismiss() {
        deliveryFlow.value = KeyFileDeliveryState.None
    }

    /** 建议的密钥文件名：与密码库同名（`.kdbx` → `.keyx`），与设置页导出通道命名习惯一致 */
    fun suggestedFileName(vaultName: String): String {
        val base = if (vaultName.endsWith(KEY_FILE_EXTENSION_SOURCE, ignoreCase = true)) {
            vaultName.dropLast(KEY_FILE_EXTENSION_SOURCE.length)
        } else vaultName
        return base.ifBlank { DEFAULT_KEY_FILE_BASE } + KEY_FILE_EXTENSION
    }

    private companion object {
        /** 密码库文件扩展名（用于推导密钥文件建议名） */
        const val KEY_FILE_EXTENSION_SOURCE = ".kdbx"

        /** 密钥文件扩展名：KeePass 2.x XML 密钥文件的官方惯例后缀 */
        const val KEY_FILE_EXTENSION = ".keyx"

        /** 密码库名为空时的兜底建议名（与设置页导出通道的默认名一致） */
        const val DEFAULT_KEY_FILE_BASE = "keepasskey"
    }
}
