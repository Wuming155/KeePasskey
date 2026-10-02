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
 * 持有活动库 id 跟踪、开关即时状态、[BiometricEnableCoordinator] 与改密重封印协调器
 * （ISSUE-P2-398，装配收敛于此以守行数分档闸门）；公开语义（开启须验证 / 关闭即撤销 /
 * 改密成功后重封印）原样保留在各协调器。
 */
internal class SettingsBiometricGate(
    scope: CoroutineScope,
    settingsRepository: SettingsRepository,
    biometricAuthManager: BiometricAuthManager?,
    biometricCredentialStorage: BiometricCredentialStorage?,
    strings: StringsProvider,
    debugLog: DebugLogBuffer,
    /** ISSUE-P3-430：仅改绑密钥文件（密码分量未变）重封印的会话主密码快照来源（null 仅单测注入） */
    private val sessionPasswordChars: () -> CharArray? = { null }
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

    // ISSUE-P2-398：改密成功后以新密码重封印活动库快速解锁凭据（复用本类的活动库跟踪）
    private val resealCoordinator = BiometricResealCoordinator(
        settingsRepository = settingsRepository,
        activeDbId = { activeDatabaseId },
        sessionPasswordChars = sessionPasswordChars,
        biometricAuthManager = biometricAuthManager,
        biometricCredentialStorage = biometricCredentialStorage,
        strings = strings,
        debugLog = debugLog
    )

    fun setEnabled(enabled: Boolean, activity: FragmentActivity? = null) =
        coordinator.setEnabled(enabled, activity)

    /**
     * ISSUE-P2-398：改密任务成功后的重封印挂点（前置不满足即空操作，详见协调器 KDoc）；
     * ISSUE-P3-430：[newPasswordChars] 为 null = 密码分量未变（仅改绑密钥文件），
     * 由协调器自会话取当前主密码快照封印。
     */
    suspend fun resealAfterMasterKeyChange(activity: FragmentActivity?, newPasswordChars: CharArray?) =
        resealCoordinator.resealAfterMasterKeyChange(activity, newPasswordChars)
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
    stateSubscribeTimeoutMillis: Long,
    appContext: android.content.Context? = null
) {
    val importPresenter = SettingsImportPresenter(vaultImportController, appContext)

    val childDatabaseController = SettingsChildDatabaseController(
        manager = childDatabaseSessionManager,
        keyFileAccess = keyFileAccess,
        debugLogBuffer = debugLogBuffer,
        scope = scope,
        stateSubscribeTimeoutMillis = stateSubscribeTimeoutMillis
    )

    /**
     * ISSUE-P3-428：密钥文件读取通道（改密对话框「绑定/更换」用）——全仓唯一 SAF 读取
     * （ISSUE-P3-04 口径）；通道缺失（单测注入 null）时按 `Unreadable` fail-closed，绝不静默当成功。
     */
    val keyFileReader: suspend (String) -> com.keepasskey.app.ui.screens.unlock.KeyFileReadResult =
        { uri -> keyFileAccess?.read(uri) ?: com.keepasskey.app.ui.screens.unlock.KeyFileReadResult.Unreadable }
}
