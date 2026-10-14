package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import kotlinx.coroutines.CoroutineScope

/**
 * `ISSUE-P3-528`：`SettingsViewModel` 侧的**唯一装配点**（自 VM 下沉，使该文件回到行数档沿之下）。
 *
 * 各参数语义与 [SettingsMasterKeyChangeController] 的 ctor KDoc 逐项一致，此处只记装配方三条口径：
 * - [strings]：`ISSUE-P3-453` 的「错误码 → 本地化文案」通道，命名置末位以不打断既有位置传参序列；
 * - [residualBackupProbe]：`ISSUE-P2-520` 的换密回执 `.bak` 残余读数（真会话 stat 挂 IO；可空会话短路）；
 * - [onCredentialsRotated]：`ISSUE-P3-528` 的「云端副本待替换」登记（生产 = `SyncCoordinator.markLocalVaultRecrypted`）。
 */
internal fun masterKeyChangeControllerOf(
    repository: VaultRepository,
    scope: CoroutineScope,
    resealAfterChange: suspend (FragmentActivity?, CharArray?) -> Unit,
    keyFileAccess: KeyFileAccess?,
    vaultCopyStore: com.keepasskey.app.security.KeyFileVaultCopyStore?,
    strings: StringsProvider,
    residualBackupProbe: suspend () -> Boolean,
    onCredentialsRotated: suspend () -> Unit,
    runtime: CredentialRotationRuntime? = null,
    completion: CredentialRotationCompletion? = null
): SettingsMasterKeyChangeController = SettingsMasterKeyChangeController(
    repository = repository,
    scope = scope,
    resealAfterChange = resealAfterChange,
    keyFileAccess = keyFileAccess,
    vaultCopyStore = vaultCopyStore,
    strings = strings,
    residualBackupProbe = residualBackupProbe,
    onCredentialsRotated = onCredentialsRotated,
    runtime = runtime,
    completionWithRequest = completion?.let { owner -> owner::complete },
    credentialSession = completion?.session,
    captureSyncTarget = completion?.let { owner -> owner::captureTarget },
    onUncertainWrite = completion?.let { owner -> owner::invalidate }
)
