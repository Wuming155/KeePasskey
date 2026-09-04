package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.VaultGroup

/**
 * 凭据编辑/添加页面的不可变 UI 状态
 */
data class EntryEditUiState(
    val entryId: String? = null,
    val groupId: String? = null,
    val availableGroups: List<VaultGroup> = emptyList(),
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val isPasskey: Boolean = false,
    val totpSecret: String = "",
    val selectedCategory: EntryCategory = EntryCategory.LOGIN,
    val isPasswordVisible: Boolean = false,
    val showGenerator: Boolean = false,
    val passLength: Float = 20f,
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigits: Boolean = true,
    val useSymbols: Boolean = true,
    val excludeConfusing: Boolean = true,
    val userMessage: String? = null,
    val isSaved: Boolean = false
)
