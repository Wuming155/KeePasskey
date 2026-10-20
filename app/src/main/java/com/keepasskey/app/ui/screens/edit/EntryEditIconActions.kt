package com.keepasskey.app.ui.screens.edit

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.KdbxErrorTexts
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 条目标准图标与库级自定义图标的编辑动作。 */
internal class EntryEditIconActions(
    private val scope: CoroutineScope,
    private val vaultRepository: VaultRepository,
    private val uiState: MutableStateFlow<EntryEditUiState>,
    private val customIconOptions: MutableStateFlow<Map<String, ByteArray>>,
    private val strings: StringsProvider
) {
    // TASK-15：标准图标与自定义图标互斥——选标准图标即清除自定义引用
    fun onIconChange(icon: String) = uiState.update {
        it.copy(iconName = icon, customIconId = null, isDirty = true)
    }

    /** TASK-15：选择/清除（null）自定义图标引用 */
    fun onCustomIconSelected(iconId: String?) = uiState.update {
        it.copy(customIconId = iconId, isDirty = it.isDirty || it.customIconId != iconId)
    }

    /** TASK-15：上传 PNG 字节为库级自定义图标并选中；失败如实上浮 */
    fun onCustomIconUploaded(pngBytes: ByteArray) {
        scope.launch {
            when (val result = vaultRepository.addCustomIcon(pngBytes)) {
                is KdbxResult.Success -> {
                    customIconOptions.value = vaultRepository.getCustomIconBytes()
                    uiState.update { it.copy(customIconId = result.data, isDirty = true) }
                }
                is KdbxResult.Failure -> uiState.update {
                    it.copy(
                        userMessage = UiMessage(
                            R.string.edit_save_failed,
                            listOf(strings.get(KdbxErrorTexts.resIdFor(result.code)))
                        )
                    )
                }
            }
        }
    }
}
