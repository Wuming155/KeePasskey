package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.SettingsRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.ui.model.StringsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 生物识别开关的「开启前当场验证」编排（§280 自 [SettingsViewModel] 拆出，纯结构性）。
 *
 * 持有活动库 id 跟踪、开关即时状态与 [BiometricEnableCoordinator]；
 * 公开语义（开启须验证 / 关闭即撤销）原样保留在协调器。
 */
internal class SettingsBiometricGate(
    scope: CoroutineScope,
    settingsRepository: SettingsRepository,
    biometricAuthManager: BiometricAuthManager?,
    biometricCredentialStorage: BiometricCredentialStorage?,
    strings: StringsProvider,
    debugLog: DebugLogBuffer
) {
    /** 活动库 id（封印凭据就绪判定的数据源）；随仓库库列表流更新 */
    private var activeDatabaseId: String? = null

    /** 开关开启动作的即时状态（验证中 / 一次性反馈），经投影层并入 uiState */
    val toggleState = MutableStateFlow(BiometricToggleUiState())

    fun onActiveDatabaseChanged(id: String?) {
        activeDatabaseId = id
    }

    private val coordinator = BiometricEnableCoordinator(
        scope = scope,
        settingsRepository = settingsRepository,
        activeDbId = { activeDatabaseId },
        biometricAuthManager = biometricAuthManager,
        biometricCredentialStorage = biometricCredentialStorage,
        strings = strings,
        debugLog = debugLog,
        state = toggleState
    )

    fun setEnabled(enabled: Boolean, activity: FragmentActivity? = null) =
        coordinator.setEnabled(enabled, activity)
}

/**
 * 导入 + 子库两组特性控制器（§280 自 [SettingsViewModel] 装配段拆出）。
 * 只负责构造；对外 API 仍由 ViewModel 门面转发，形状不变。
 */
internal class SettingsFeatureControllers(
    vaultImportController: com.keepasskey.app.ui.screens.importer.VaultImportController?,
    childDatabaseSessionManager: com.keepasskey.app.data.childdb.ChildDatabaseSessionManager?,
    keyFileAccess: com.keepasskey.app.ui.screens.unlock.KeyFileAccess?,
    debugLogBuffer: DebugLogBuffer,
    scope: CoroutineScope,
    stateSubscribeTimeoutMillis: Long
) {
    val importPresenter = SettingsImportPresenter(vaultImportController)

    val childDatabaseController = SettingsChildDatabaseController(
        manager = childDatabaseSessionManager,
        keyFileAccess = keyFileAccess,
        debugLogBuffer = debugLogBuffer,
        scope = scope,
        stateSubscribeTimeoutMillis = stateSubscribeTimeoutMillis
    )
}
