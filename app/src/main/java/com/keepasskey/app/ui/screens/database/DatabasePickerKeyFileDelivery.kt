package com.keepasskey.app.ui.screens.database

import android.content.Context
import android.net.Uri
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.KeyFileVaultCopyStore
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.querySafDisplayName
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
    private val vaultCopyStore: KeyFileVaultCopyStore? = null,
    /** 取证日志通道（可空 = 单测未装配；只记状态与布尔值，不记库名 / Uri / 文件名） */
    private val debugLog: com.keepasskey.app.data.logger.DebugLogBuffer? = null,
    /** ISSUE-P2-460 AC②：按库登记所需的密钥文件访问契约（可空 = 单测未装配） */
    private val keyFileAccess: com.keepasskey.app.ui.screens.unlock.KeyFileAccess? = null
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
        debugLog?.info(TAG, "交付状态机: None→PendingSave（挂出一次性提示）")
    }

    /**
     * 把生成型密钥文件写入用户选定的 SAF 目标（ISSUE-P3-21 验收 2）。
     *
     * 字节来源两级：既有导出通道 [VaultRepository.exportKeyFileBytes]（会话绑定快照）→
     * 失败时回落本库收编副本（ISSUE-P2-460 AC③）。两级都取不到即显式「密钥文件已丢失」，
     * 绝不写空文件冒充成功、也不得只报一句「保存失败」。
     */
    fun saveTo(targetUri: Uri) {
        debugLog?.info(TAG, "交付状态机: saveTo 进入（写盘开始）")
        scope.launch {
            val bytes = resolveDeliveryBytes()
            if (bytes == null) {
                // AC③：两级都取不到 ⇒ 密钥文件确已丢失，显式分型提示（不得静默「保存失败」）
                debugLog?.info(TAG, "交付状态机: 两级字节源均空 → 判定丢失（PendingSave 驻留）")
                publish(UiMessage(R.string.db_picker_keyfile_lost))
                return@launch
            }
            val resolver = appContext?.contentResolver
            if (resolver == null) {
                // 禁止静默失败：没有写盘上下文时如实告知用户，提示保持驻留
                debugLog?.warn(TAG, "交付状态机: 无写盘上下文（resolver=null）→ 报保存失败（PendingSave 驻留）")
                bytes.fill(0)
                publish(UiMessage(R.string.db_picker_keyfile_save_failed))
                return@launch
            }
            val written = withContext(Dispatchers.IO) {
                val ok = try {
                    resolver.openOutputStream(targetUri)?.use { output ->
                        output.write(bytes)
                        output.flush()
                        true
                    } ?: false
                } catch (_: Exception) { // cancel-n/a: 保护段为阻塞式 SAF 写盘（无挂起点）
                    // 异常不外泄内容（可能是提供方拒绝/磁盘满），统一由下方语义化提示承接
                    debugLog?.warn(TAG, "交付状态机: SAF 写盘抛异常（细节不外泄）")
                    false
                }
                if (ok) {
                    // ISSUE-P3-462：用户在 SAF 选择器里可改名——写盘成功后、密钥字节擦除前，
                    // 按目标文档的**实际显示名**回写本库副本（同字节重封存）。建库收编时取的
                    // 是建议名（生成型因子彼时尚无真实文档名），此后解锁页提示应以用户保存的命名为准。
                    // 查询失败保持现名，不阻断保存回执（AC②）。
                    val actualName = appContext?.let { querySafDisplayName(it, targetUri) }.orEmpty()
                    if (!pendingDbId.isNullOrBlank() && vaultCopyStore != null && actualName.isNotBlank()) {
                        vaultCopyStore.save(pendingDbId!!, bytes, actualName)
                        debugLog?.info(TAG, "交付状态机: 副本显示名已按用户保存命名回写")
                    }
                    // ISSUE-P3-464 ①（KeePassDX 对齐）：记住的密钥文件＝用户实际保存的那个文件。
                    // 写盘成功即按「保存位置 + 实际显示名」登记本库记忆（尊重偏好与持久授权，
                    // 最小权限口径不变；未登记时副本仍在，恢复链不受影响）。
                    val access = keyFileAccess
                    val dbIdForMemory = pendingDbId
                    if (access != null && dbIdForMemory != null && actualName.isNotBlank()) {
                        val registered = access.isRememberEnabled() &&
                            access.persistReadPermission(targetUri.toString())
                        if (registered) {
                            access.remember(dbIdForMemory, targetUri.toString(), actualName)
                            debugLog?.info(TAG, "交付状态机: 已按保存位置登记本库密钥文件记忆")
                        }
                    }
                }
                // 密钥文件字节副本用毕即擦（成败路径都清零；会话内仍持有自己的副本供后续导出）
                bytes.fill(0)
                ok
            }
            if (written) {
                debugLog?.info(TAG, "交付状态机: 写盘成功 → PendingSave→None（提示关闭）")
                deliveryFlow.value = KeyFileDeliveryState.None
                publish(UiMessage(R.string.db_picker_keyfile_saved))
            } else {
                debugLog?.warn(TAG, "交付状态机: 写盘失败 → PendingSave 驻留（用户可重试/搁置）")
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
        if (sessionBytes != null) {
            debugLog?.info(TAG, "交付状态机: 字节源=会话导出")
            return sessionBytes
        }
        debugLog?.info(TAG, "交付状态机: 会话导出为空（会话未绑定/已失效）→ 试本库副本（dbId 在位=${!pendingDbId.isNullOrBlank()}）")
        val copy = vaultCopyStore?.load(pendingDbId.orEmpty())?.bytes
        debugLog?.info(TAG, "交付状态机: 字节源=${if (copy != null) "本库副本" else "无（判定丢失）"}")
        return copy
    }

    /** 用户显式选择「暂不保存密钥文件」：关闭一次性提示（不清会话缓存，可经设置页导出补存） */
    fun dismiss() {
        debugLog?.info(TAG, "交付状态机: 用户暂不保存 → PendingSave→None（提示关闭）")
        deliveryFlow.value = KeyFileDeliveryState.None
    }

    /**
     * ISSUE-P2-460 AC②：建库成功即收编密钥文件副本并按库登记（不再等下次解锁）。
     *
     * - 副本：经既有导出通道现取会话绑定字节（生成型与既有因子同路），失败仅留痕不阻断
     *   （库已建成，一次性交付提示仍在；副本缺失只降级恢复体验，不得谎报建库失败）；
     * - 登记：既有因子在「记住密钥文件位置」偏好开启且取得持久化读授权时，把来源 Uri 记到
     *   **本库名下**（与解锁页同口径；生成型因子无 SAF 来源，副本即其登记形态）。
     */
    suspend fun adoptOnCreate(
        dbId: String,
        vaultName: String,
        resolution: KeyFileFactorResolution.Resolved
    ) {
        val bytes = vaultRepository.exportKeyFileBytes().getOrNull()
        if (bytes != null) {
            val displayName = resolution.sourceDisplayName ?: suggestedFileName(vaultName)
            val saved = vaultCopyStore?.save(dbId, bytes, displayName)
            bytes.fill(0)
            if (saved != true) {
                debugLog?.warn(TAG, "建库密钥文件副本收编失败（库已建成，交付提示仍有效）")
            }
        } else {
            debugLog?.warn(TAG, "建库后取不到密钥文件字节，副本未收编")
        }
        val access = keyFileAccess
        val sourceUri = resolution.sourceUri
        if (access != null && sourceUri != null &&
            access.isRememberEnabled() && access.persistReadPermission(sourceUri)
        ) {
            access.remember(dbId, sourceUri, resolution.sourceDisplayName.orEmpty())
        }
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

        /** 取证日志 TAG（logcat 直出：adb logcat -s KpLog） */
        const val TAG = "KeyFileDelivery"
    }
}
