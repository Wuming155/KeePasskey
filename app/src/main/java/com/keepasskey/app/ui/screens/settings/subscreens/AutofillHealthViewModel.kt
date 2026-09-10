package com.keepasskey.app.ui.screens.settings.subscreens

import androidx.lifecycle.ViewModel
import com.keepasskey.app.autofill.AutofillHealthProbe
import com.keepasskey.app.autofill.AutofillHealthReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 自动填充健康自检 ViewModel（ISSUE-P3-41）。
 *
 * 采用**独立 ViewModel** 而非并入 SettingsViewModel：健康探针只在自动填充设置页需要，
 * 独立承载可避免为 SettingsViewModel 增加构造依赖（也就不会影响其既有单测）。
 */
@HiltViewModel
class AutofillHealthViewModel @Inject constructor(
    private val probe: AutofillHealthProbe
) : ViewModel() {

    private val _report = MutableStateFlow<AutofillHealthReport?>(null)

    /** null 表示尚未探测（首帧），UI 据此不渲染任何状态文案 */
    val report: StateFlow<AutofillHealthReport?> = _report.asStateFlow()

    /** 重新探测；[appEnabled] 由 UI 从偏好状态传入（应用内开关） */
    fun refresh(appEnabled: Boolean) {
        _report.value = probe.probe(appEnabled)
    }
}
