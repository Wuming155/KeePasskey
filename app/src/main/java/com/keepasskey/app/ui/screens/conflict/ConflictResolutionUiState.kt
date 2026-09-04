package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.ui.model.UiMessage

enum class FieldChoice {
    LOCAL,
    REMOTE
}

data class ConflictedField(
    val fieldName: String,
    val localValue: String,
    val remoteValue: String,
    val selectedChoice: FieldChoice = FieldChoice.LOCAL,
    val isSensitive: Boolean = false
)

data class ConflictedEntryItem(
    val id: String,
    val title: String,
    val groupPath: String,
    val fields: List<ConflictedField>
)

data class ConflictResolutionUiState(
    val localModifiedTime: String = "今天 10:25 (本地)",
    val remoteModifiedTime: String = "今天 10:22 (WebDAV 云端)",
    val entries: List<ConflictedEntryItem> = emptyList(),
    val isResolving: Boolean = false,
    val userMessage: UiMessage? = null
)
