package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.ClipboardSecurityManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 密码库列表页的**写操作编排**（ISSUE-P3-29：自 `VaultListViewModel.kt` 拆出）。
 *
 * 承接三类写动作，原实现逐字迁移：
 * 1. **批量选择**：本类持有批量模式与选中集合两个 `StateFlow`（原先在 ViewModel 内），
 *    对外只读暴露供 UI 状态流 combine，写入口收敛为下列方法；
 * 2. **分组 / 条目写操作**：新建 / 重命名 / 改图标 / 删除分组、还原 / 彻底删除条目、清空回收站；
 * 3. **剪贴板复制**：密码走 CharArray 借用通道并用毕清零（ISSUE-P2-15），用户名走明文通道。
 *
 * 只读会话（H4）下 2 的写操作一律硬拒绝；`isReadOnly` / `currentGroupId` / `currentGroups` /
 * `currentEntryIds` 均以回调形式从 ViewModel 取当前快照，避免本类反向持有 ViewModel。
 */
@Suppress("LongParameterList")
internal class VaultListActionController(
    private val repository: VaultRepository,
    private val scope: CoroutineScope,
    private val strings: StringsProvider,
    private val clipboardSecurityManager: ClipboardSecurityManager?,
    private val isReadOnly: () -> Boolean,
    private val currentGroupId: () -> String?,
    private val currentGroups: () -> List<VaultGroup>,
    private val currentEntryIds: () -> List<String>,
    private val onMessage: (UiMessage) -> Unit
) {

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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
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
                onMessage(UiMessage(R.string.vault_op_failed, listOf((result as KdbxResult.Failure).message)))
            }
        }
    }
}
