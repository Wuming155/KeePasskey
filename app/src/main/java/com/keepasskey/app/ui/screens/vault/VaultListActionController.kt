package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.data.repository.parseKdbxUuidOrNull
import com.keepasskey.app.passkey.PasskeyImportDraft
import com.keepasskey.app.passkey.PasskeyImportDraftHost
import com.keepasskey.app.passkey.PasskeyImportFactory
import com.keepasskey.app.security.ClipboardSecurityChannel
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.otp.TotpKeyUriParser
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.keepasskey.app.ui.model.textArg

/**
 * 密码库列表页的**写操作编排**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 承接三类写动作，原实现逐字迁移：
 * 1. **批量选择**：本类持有批量模式与选中集合两个 `StateFlow`（原先在 ViewModel 内），
 *    对外只读暴露供 UI 状态流 combine，写入口收敛为下列方法；
 * 2. **分组 / 条目写操作**：新建 / 重命名 / 改图标 / 删除分组、还原 / 彻底删除条目、清空回收站；
 * 3. **剪贴板复制**：执行体下沉至 [VaultListClipboardCopy]（ISSUE-P2-353 AC①：只在实际写入
 *    成功后才报成功），本类保留门面与 HOTP 第二道防线（ISSUE-P3-184，理由见 [copyTotpCode]）。
 *
 * 只读会话（H4）下 2 的写操作一律硬拒绝；`isReadOnly` / `currentGroupId` / `currentGroups` /
 * `currentEntryIds` 均以回调形式从 ViewModel 取当前快照，避免本类反向持有 ViewModel。
 * §284：五条宿主回调收拢为 [VaultListActionHost]，摘除 `LongParameterList` 压制；行为零变化。
 */
/** 宿主快照回调集（§284 参数对象化；不反向持有 ViewModel） */
internal data class VaultListActionHost(
    val isReadOnly: () -> Boolean,
    val currentGroupId: () -> String?,
    val currentGroups: () -> List<VaultGroup>,
    val currentEntryIds: () -> List<String>,
    val onMessage: (UiMessage) -> Unit
)

internal class VaultListActionController(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    private val strings: StringsProvider,
    private val clipboardSecurityManager: ClipboardSecurityChannel?,
    private val host: VaultListActionHost
) {
    private val isReadOnly get() = host.isReadOnly
    private val currentGroupId get() = host.currentGroupId
    private val currentGroups get() = host.currentGroups
    private val currentEntryIds get() = host.currentEntryIds
    private val onMessage get() = host.onMessage

    private val isBatchModeFlow = MutableStateFlow(false)
    private val selectedEntryIdsFlow = MutableStateFlow<Set<String>>(emptySet())

    /** 是否处于批量选择模式 */
    val isBatchMode: StateFlow<Boolean> = isBatchModeFlow

    /** 当前选中的条目 id 集合 */
    val selectedEntryIds: StateFlow<Set<String>> = selectedEntryIdsFlow

    // ---------------------------------------------------------------------
    // 剪贴板复制（ISSUE-P2-353 AC①：执行体下沉至 VaultListClipboardCopy——
    // 只在剪贴板**实际写入成功**后才报成功，通道缺失 / 写入异常如实报失败）
    // ---------------------------------------------------------------------

    private val clipboardCopy = VaultListClipboardCopy(
        repository = repository,
        scope = scope,
        clipboard = clipboardSecurityManager,
        onMessage = { onMessage(it) }
    )

    fun copyPassword(entry: UiVaultEntry) = clipboardCopy.copyPassword(entry)

    fun copyUsername(entry: UiVaultEntry) = clipboardCopy.copyUsername(entry)

    /**
     * ISSUE-P3-184：复制条目**当前 TOTP 验证码**——列表行徽标一次点击即可，无需进详情页。
     *
     * 契约一（**HOTP 硬拒绝**，`entry.isHotp`）留在本门面：HOTP 之码由持久化计数器决定，
     * 「复制而不推进」会让同一计数器被重复用，故只提供详情页的「取下一个码」（`advanceHotp`）
     * 而无复制入口；调用侧亦按 `isHotp` 不渲染可点徽标，此处是第二道防线。
     * 取值、写入与「失败不谎报」（无 TOTP / 写入失败一律如实提示）由
     * [VaultListClipboardCopy.copyTotpCode] 执行（ISSUE-P2-353 AC①）。
     */
    fun copyTotpCode(entry: UiVaultEntry) {
        if (entry.isHotp) return
        clipboardCopy.copyTotpCode(entry)
    }

    // ---------------------------------------------------------------------
    // 批量选择
    // ---------------------------------------------------------------------

    /**
     * 进入批量选择模式。
     * ISSUE-P3-360 AC④a：[initialEntryId] 为空串表示**不预选**任何条目
     * （溢出菜单「选择」入口；长按入口恒传真实条目 id）——空串不是合法条目 id，
     * 故不会进入选中集合污染批量操作。
     */
    fun startBatchMode(initialEntryId: String) {
        isBatchModeFlow.value = true
        selectedEntryIdsFlow.value =
            if (initialEntryId.isEmpty()) emptySet() else setOf(initialEntryId)
    }

    fun toggleEntrySelection(entryId: String) {
        val current = selectedEntryIdsFlow.value.toMutableSet()
        if (entryId in current) {
            current.remove(entryId)
            if (current.isEmpty()) {
                isBatchModeFlow.value = false
            }
        } else {
            current.add(entryId)
        }
        selectedEntryIdsFlow.value = current
    }

    fun selectAllEntries() {
        selectedEntryIdsFlow.value = currentEntryIds().toSet()
    }

    fun clearBatchSelection() {
        isBatchModeFlow.value = false
        selectedEntryIdsFlow.value = emptySet()
    }

    fun batchMoveSelected(targetGroupId: String?) {
        val selected = selectedEntryIdsFlow.value
        if (selected.isEmpty()) return
        scope.launch {
            val result = repository.batchMoveEntries(selected, targetGroupId)
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_batch_moved, listOf(selected.size)))
                clearBatchSelection()
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    /**
     * ISSUE-P2-357 AC②：最近一次批量软删除的待撤销批次（条目 id）。删除成功时写入，
     * 消息标记 [UiMessage.undoable]，Snackbar 的撤销动作经 [undoBatchDelete] 消费并置空——
     * 过期批次不可再被触发（撤销出口只随该消息的 ActionPerformed 存在）。
     */
    private val pendingUndoIdsFlow = MutableStateFlow<List<String>?>(null)

    fun batchDeleteSelected() {
        val selected = selectedEntryIdsFlow.value
        if (selected.isEmpty()) return
        scope.launch {
            val result = repository.batchDeleteEntries(selected)
            if (result is KdbxResult.Success) {
                pendingUndoIdsFlow.value = selected.toList()
                onMessage(UiMessage(R.string.vault_batch_deleted, listOf(selected.size), undoable = true))
                clearBatchSelection()
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    /**
     * ISSUE-P2-357 AC②：撤销上一次批量软删除——把待撤销批次逐条经回收站恢复入口还原；
     * 无待撤销批次（从未删除 / 已撤销过 / 非可撤销消息）时为空操作，不产生任何消息。
     * 批量删除本身是软删除（移入回收站），故撤销可安全逆放；彻底删除无此通道。
     */
    fun undoBatchDelete() {
        val ids = pendingUndoIdsFlow.value ?: return
        pendingUndoIdsFlow.value = null
        scope.launch {
            var restored = 0
            ids.forEach { id ->
                if (repository.restoreEntry(id) is KdbxResult.Success) restored++
            }
            onMessage(UiMessage(R.string.vault_batch_restored, listOf(restored)))
        }
    }

    // ---------------------------------------------------------------------
    // 分组 / 条目写操作（只读会话一律拒绝）
    // ---------------------------------------------------------------------

    fun createGroup(name: String, iconName: String = "folder") {
        if (name.isBlank() || isReadOnly()) return
        scope.launch {
            val newGroup = VaultGroup(
                id = "group_${System.currentTimeMillis()}",
                name = name.trim(),
                parentId = currentGroupId(),
                iconName = iconName,
                orderIndex = (currentGroups().maxOfOrNull { it.orderIndex } ?: 0) + 1,
                updatedAt = strings.get(R.string.time_just_now),
                createdAt = strings.get(R.string.time_just_now)
            )
            val result = repository.saveGroup(newGroup)
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_group_created, listOf(name.trim())))
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    fun renameGroup(group: VaultGroup, newName: String) {
        if (newName.isBlank() || isReadOnly()) return
        scope.launch {
            val result = repository.saveGroup(
                group.copy(name = newName.trim(), updatedAt = strings.get(R.string.time_just_now))
            )
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_group_renamed, listOf(newName.trim())))
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    fun changeGroupIcon(group: VaultGroup, newIcon: String) {
        if (isReadOnly()) return
        scope.launch {
            repository.saveGroup(
                group.copy(iconName = newIcon, updatedAt = strings.get(R.string.time_just_now))
            )
            onMessage(UiMessage(R.string.vault_group_icon_updated))
        }
    }

    fun deleteGroup(groupId: String) {
        if (isReadOnly()) return
        scope.launch {
            val result = repository.deleteGroup(groupId)
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_group_deleted))
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    fun restoreEntry(entryId: String) {
        if (isReadOnly()) return
        scope.launch {
            val result = repository.restoreEntry(entryId)
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_entry_restored))
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    fun purgeEntry(entryId: String) {
        if (isReadOnly()) return
        scope.launch {
            val result = repository.deleteEntry(entryId)
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_entry_purged))
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    fun emptyRecycleBin() {
        if (isReadOnly()) return
        scope.launch {
            val result = repository.emptyRecycleBin()
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_recycle_emptied))
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
            }
        }
    }

    // ---------------------------------------------------------------------
    // 顶栏扫码：otpauth 二维码 → 直接创建验证码条目
    // ---------------------------------------------------------------------

    /**
     * 顶栏「扫码」入口：解码得到的 otpauth URI → 解析校验 → 在当前分组直接创建验证码条目。
     *
     * [decoded] 为 `TotpScanDialog` 上行的解码原文（框架边界 String 即刻转出的 CharArray，
     * 含种子语义，本方法承担其擦除义务）：
     * - 只读会话 / 非 otpauth 前缀 / 解析失败：本方法同步 `fill('0')` 后如实提示，不落库；
     * - 保存路径：所有权移交 [repository.saveEntry] 的 `totpSecretChars` 擦除契约
     *   （任何结果路径清零），协程体内再兜底一次（幂等，覆盖协程体异常路径）。
     *
     * **前缀强校验**：本入口只收 `otpauth://`（忽略大小写）。解析器的宽容口径还会接受
     * 纯 Base32 文本（编辑页手填种子的兼容通道），但扫码面对的是任意二维码——
     * 不加前缀校验时，扫到一个纯字母单词（全在 Base32 字母表内）也会被当成种子落库。
     *
     * 解析走字节语义（ISSUE-P2-12 口径）：CharArray → UTF-8 字节 → [TotpKeyUriParser]，
     * 工作副本与解析产物 `ParsedTotpConfig.secret` 用毕即擦；
     * 落库存**原始 otpauth URI**（period / digits / algorithm / counter 全参数保真）。
     */
    fun addEntryFromScannedOtpauth(decoded: CharArray) {
        if (isReadOnly()) {
            decoded.fill('0')
            onMessage(UiMessage(R.string.readonly_save_rejected))
            return
        }
        if (!hasOtpauthPrefix(decoded)) {
            decoded.fill('0')
            onMessage(UiMessage(R.string.vault_scan_invalid_qr))
            return
        }
        val bytes = CharBuffer.wrap(decoded).let { buffer ->
            val utf8 = StandardCharsets.UTF_8.encode(buffer)
            ByteArray(utf8.remaining()).also { out ->
                utf8.get(out)
                // 编码器内部 ByteBuffer 承载过明文，一并清零（P0-7 同口径）
                if (utf8.hasArray()) utf8.array().fill(0)
            }
        }
        val config = try {
            TotpKeyUriParser.parse(bytes)
        } finally {
            bytes.fill(0)
        }
        if (config == null) {
            decoded.fill('0')
            onMessage(UiMessage(R.string.vault_scan_invalid_qr))
            return
        }
        // 非敏感元数据（issuer / account 标签）先取出；种子副本不参与后续链路，即刻擦除
        val issuer = config.issuer
        val account = config.account
        config.secret.fill(0)
        val title = issuer ?: account ?: strings.get(R.string.vault_scan_default_title)
        val username = account?.takeIf { it != title } ?: ""
        val entry = UiVaultEntry(
            id = UUID.randomUUID().toString(),
            title = title,
            username = username,
            url = "",
            groupId = currentGroupId(),
            updatedAt = strings.get(R.string.time_just_now),
            createdAt = strings.get(R.string.time_just_now)
        )
        scope.launch {
            try {
                val result = repository.saveEntry(entry, totpSecretChars = decoded)
                if (result is KdbxResult.Success) {
                    onMessage(UiMessage(R.string.vault_scan_entry_created, listOf(title)))
                } else {
                    onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).textArg(strings))))
                }
            } finally {
                // 兜底擦除（仓库契约已清零；幂等双保险，覆盖协程体异常路径）
                decoded.fill('0')
            }
        }
    }

    /** 前缀强校验：跳过前导空白后是否以 `otpauth://` 开头（忽略大小写）。 */
    private fun hasOtpauthPrefix(chars: CharArray): Boolean {
        val prefix = OTPAUTH_PREFIX
        var start = 0
        while (start < chars.size && chars[start].isWhitespace()) start++
        if (chars.size - start < prefix.length) return false
        return prefix.indices.all { chars[start + it].lowercaseChar() == prefix[it] }
    }

    // ---------------------------------------------------------------------
    // 顶栏扫码：通行密钥 (CXF) 载荷分支（ISSUE-P3-337 口径 1 / 4，Q2 确认在先）
    // ---------------------------------------------------------------------

    /**
     * 草案宿主（解析 + 承载 + 静态文案，与编辑页 Q1 入口同一份实现，见该类 KDoc）；
     * 本类只保留顶栏特有的两件事：**落当前分组建条目**与**导入成功后的一次性导航意图**。
     */
    private val passkeyDrafts = PasskeyImportDraftHost(
        isReadOnly = { isReadOnly() },
        onNotice = { res -> onMessage(UiMessage(res)) }
    )

    /** 待确认的通行密钥导入草案（非空即确认对话框可见）；仅内存持有，见 `PasskeyImportDraft`。 */
    val pendingPasskeyImport: StateFlow<PasskeyImportDraft?> = passkeyDrafts.pending

    private val openEntryEditIdFlow = MutableStateFlow<String?>(null)

    /** 导入成功后待打开的条目 id（UI 消费一次即清空；不承载任何凭据值）。 */
    val openEntryEditId: StateFlow<String?> = openEntryEditIdFlow

    fun consumeOpenEntryEditId() {
        openEntryEditIdFlow.value = null
    }

    /**
     * 通行密钥分支：解析载荷 → 成功则**只把草案交给 [passkeyDrafts]**，等用户在确认对话框里点头。
     *
     * 与 [addEntryFromScannedOtpauth] 同族的字节通道口径、以及「任何路径都不回显载荷内容」
     * 的纪律都在宿主内实现（口径 4；编辑页 Q1 复用同一入口，禁复制第二份）。
     *
     * @return 是否已进入确认环节（false 表示已按静态文案拒绝）
     */
    fun beginPasskeyImportFromScan(decoded: CharArray): Boolean = passkeyDrafts.begin(decoded)

    /**
     * 用户在确认对话框点「导入」：草案 → [PasskeyData] → 落库到**当下所在分组**，
     * 成功后按 id 打开该条目编辑页（Q2：确认在先、不静默建条目；口径 4：私钥不跨页承载）。
     *
     * 草案在所有路径（含保存失败）都被擦除并置空——它是明文私钥的唯一持有者。
     */
    fun confirmPasskeyImport() {
        val draft = passkeyDrafts.takeForImport() ?: return
        if (isReadOnly()) {
            draft.wipe()
            onMessage(UiMessage(R.string.readonly_save_rejected))
            return
        }
        val data = PasskeyImportFactory.toPasskeyData(draft.credential)
        draft.wipe()
        scope.launch {
            val groupId = currentGroupId()?.let { parseKdbxUuidOrNull(it) }
            val saved = repository.saveNewPasskeyEntry(data, boundPackage = null, parentGroupId = groupId)
            // 落盘失败由协调器内部留痕（PasskeyEntryCoordinator 的既有契约：条目已在会话内生效，
            // 序列化失败只记日志），故此处按「已建条目」如实提示并交出导航意图，不再二次探测。
            onMessage(UiMessage(R.string.passkey_import_created, listOf(passkeyImportTitle(data))))
            openEntryEditIdFlow.value = saved.id.toHexString()
        }
    }

    /** 用户取消：擦除草案、不落库、不导航（AC③ 的取消路径断言即打在这里）。 */
    fun dismissPasskeyImport() = passkeyDrafts.dismiss()

    /** ViewModel 销毁兜底：确认环节未走完就离开本页时，草案里的私钥明文不得滞留。 */
    fun wipePendingPasskeyImport() = passkeyDrafts.wipeAll()

    /**
     * 两条链都不是（既非 `otpauth:` 也非 JSON 形态）：擦除后按现键 `vault_scan_invalid_qr`
     * 如实提示，**不回显内容、不落库**（口径 1 的 `Unknown` 分支）。
     */
    fun rejectUnknownScannedQr(decoded: CharArray) {
        decoded.fill('0')
        onMessage(UiMessage(R.string.vault_scan_invalid_qr))
    }

    /** 条目标题口径与注册路径同源（`PasskeyEntryCoordinator.passkeyTitle`）：`用户名@rpId` */
    private fun passkeyImportTitle(data: PasskeyData): String =
        "${data.userName}@${data.relyingPartyId}"

    private companion object {
        /** 本扫码入口只接受的 URI 方案前缀（全小写，比较前逐字符 lowercase）。 */
        const val OTPAUTH_PREFIX = "otpauth://"
    }
}
