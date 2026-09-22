package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * CM 通道特权浏览器白名单的「安装扫描 / 启停」编排器
 * （ISSUE-P3-257：自 `SettingsViewModel` 下沉，形态仿 [SettingsChildDatabaseController]：
 * 持 scope + 可空 store + `MutableStateFlow`，ViewModel 侧仅保留委托）。
 *
 * store 缺失（仅单测注入）时恒为空列表并早退——如实「未检测到」，不谎报；
 * 全部 IO 均在 `Dispatchers.IO` 上执行，不占用主线程。
 */
internal class SettingsPrivilegedBrowserController(
    private val store: PasskeyPrivilegedBrowserStore?,
    private val scope: CoroutineScope
) {

    private val privilegedBrowsersState = MutableStateFlow(
        emptyList<PasskeyPrivilegedBrowserStore.BrowserApp>()
    )

    /** 已安装浏览器候选 + 启用状态；独立于 [SettingsUiState] 下发（避免 combine 元组膨胀）。 */
    val privilegedBrowsers: StateFlow<List<PasskeyPrivilegedBrowserStore.BrowserApp>> =
        privilegedBrowsersState.asStateFlow()

    /** 重新扫描已安装浏览器（进入设置页时调用；读取失败的应用不会被虚构出来） */
    fun refresh() {
        val store = store ?: return
        scope.launch(Dispatchers.IO) {
            privilegedBrowsersState.value = store.installedBrowsers()
        }
    }

    /** 启用 / 停用某个浏览器的特权资格（启用时指纹取自该应用自身签名，读取失败则不启用） */
    fun setEnabled(packageName: String, enabled: Boolean) {
        val store = store ?: return
        scope.launch(Dispatchers.IO) {
            store.setEnabled(packageName, enabled)
            privilegedBrowsersState.value = store.installedBrowsers()
        }
    }
}
