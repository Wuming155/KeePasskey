package com.keepasskey.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * ISSUE-P2-378：外部修改三选对话框挂点（自 KeePasskeyApp 拆出，控文件行数）。
 */
@Composable
internal fun ExternalModificationDialogHost(
    pending: com.keepasskey.app.security.VaultFileDriftPrompt?,
    onChoice: suspend (com.keepasskey.app.security.ExternalModificationChoice) -> com.keepasskey.core.result.KdbxResult<Unit>
) {
    if (pending == null) return
    val scope = rememberCoroutineScope()
    com.keepasskey.app.ui.screens.settings.subscreens.ExternalModificationDialog(
        onChoice = { choice ->
            scope.launch { onChoice(choice) }
        }
    )
}
