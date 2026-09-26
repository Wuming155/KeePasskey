package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.otp.TotpKeyUriParser
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 密码库列表页的**写操作编排**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 承接三类写动作，原实现逐字迁移：
 * 1. **批量选择**：本类持有批量模式与选中集合两个 `StateFlow`（原先在 ViewModel 内），
 *    对外只读暴露供 UI 状态流 combine，写入口收敛为下列方法；
 * 2. **分组 / 条目写操作**：新建 / 重命名 / 改图标 / 删除分组、还原 / 彻底删除条目、清空回收站；
 * 3. **剪贴板复制**：密码走 CharArray 借用通道并用毕清零（ISSUE-P2-15），用户名走明文通道；
 *    TOTP 走受保护文本通道且**硬拒绝 HOTP**（ISSUE-P3-184，理由见 [copyTotpCode]）。
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
    private val clipboardSecurityManager: ClipboardSecurityManager?,
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
    // 剪贴板复制
    // ---------------------------------------------------------------------

    fun copyPassword(entry: UiVaultEntry) {
        // M1 整改：列表投影不携带密码明文，复制时按需单条解密
        // ISSUE-P2-15：读取与写入全程走 CharArray 借用通道，不经不可擦 String 中转
        scope.launch {
            val chars = repository.getEntryPasswordChars(entry.id)
            if (chars != null) {
                try {
                    clipboardSecurityManager?.copySensitiveChars(entry.title, chars)
                } finally {
                    chars.fill('0')
                }
            }
            onMessage(UiMessage(R.string.vault_copy_password_done, listOf(entry.title)))
        }
    }

    fun copyUsername(entry: UiVaultEntry) {
        if (entry.username.isNotBlank()) {
            clipboardSecurityManager?.copyPlainText(entry.title, entry.username)
            onMessage(UiMessage(R.string.vault_copy_username_done, listOf(entry.username)))
        } else {
            onMessage(UiMessage(R.string.vault_copy_username_missing))
        }
    }

    /**
     * ISSUE-P3-184：复制条目**当前 TOTP 验证码**——列表行徽标一次点击即可，无需进详情页。
     *
     * 三条契约：
     * 1. **HOTP 硬拒绝**（`entry.isHotp`）：HOTP 之码由持久化计数器决定，「复制而不推进」会让同一
     *    计数器被重复使用，故只提供详情页的「取下一个码」（`advanceHotp`）而无复制入口；
     *    调用侧亦按 `isHotp` 不渲染可点徽标，此处是第二道防线。
     * 2. **取值走仓库按需通道**（ISSUE-P2-90：命中周期缓存时不触碰会话），仅在其不可用时
     *    回退到列表投影的码，避免复制到过期值。
     * 3. **失败不谎报**：无 TOTP / 计算失败一律经 [onMessage] 如实提示，不显示「已复制」。
     */
    fun copyTotpCode(entry: UiVaultEntry) {
        if (entry.isHotp) return
        scope.launch {
            val code = repository.calculateEntryTotp(entry.id)?.code?.takeIf { it.isNotBlank() }
                ?: entry.totpCode?.takeIf { it.isNotBlank() }
            if (code == null) {
                onMessage(UiMessage(R.string.vault_copy_totp_missing))
                return@launch
            }
            clipboardSecurityManager?.copySensitiveText(entry.title, code)
            onMessage(UiMessage(R.string.vault_copy_totp_done, listOf(entry.title)))
        }
    }

    // ---------------------------------------------------------------------
    // 批量选择
    // ---------------------------------------------------------------------

    fun startBatchMode(initialEntryId: String) {
        isBatchModeFlow.value = true
        selectedEntryIdsFlow.value = setOf(initialEntryId)
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
            }
        }
    }

    fun batchDeleteSelected() {
        val selected = selectedEntryIdsFlow.value
        if (selected.isEmpty()) return
        scope.launch {
            val result = repository.batchDeleteEntries(selected)
            if (result is KdbxResult.Success) {
                onMessage(UiMessage(R.string.vault_batch_deleted, listOf(selected.size)))
                clearBatchSelection()
            } else {
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
            }
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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
                    onMessage(UiMessage(R.string.op_failed, listOf((result as KdbxResult.Failure).message)))
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

    private companion object {
        /** 本扫码入口只接受的 URI 方案前缀（全小写，比较前逐字符 lowercase）。 */
        const val OTPAUTH_PREFIX = "otpauth://"
    }
}
