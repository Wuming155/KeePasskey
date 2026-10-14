package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.ui.model.UiMessage

/** 更换主密钥任务局部状态，经设置投影并入页面状态；页面重组时仍可显示忙态与回执。 */
internal data class MasterKeyChangeTaskState(
    val isChanging: Boolean = false,
    val feedback: UiMessage? = null
)
