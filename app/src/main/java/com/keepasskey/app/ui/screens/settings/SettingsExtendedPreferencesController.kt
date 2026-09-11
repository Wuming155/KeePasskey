package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.PeriodicSyncScheduler
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 进阶偏好（`ExtendedSettings`）的统一变更通道（ISSUE-P3-29：自 `SettingsViewModel.kt` 拆出，
 * 纯结构性拆分，行为零变更）。
 *
 * TASK-12 整改：内存 Flow 更新与持久化落盘**原子完成**，杜绝任何 setter 只改内存不落盘的
 * 「回显漂移」。带副作用的偏好（离线模式 / 周期同步 / 保存前备份）在同一处完成下游传导，
 * 避免散落在 ViewModel 中难以审计。
 */
internal class SettingsExtendedPreferencesController(
    private val extendedSettingsStore: ExtendedSettingsStore,
    private val syncCoordinator: SyncCoordinator,
    private val periodicSyncScheduler: PeriodicSyncScheduler,
    private val databaseSession: DatabaseSession?,
    /** 周期同步重排需读取当前 wifiOnly 生效值（由同步控制器承载） */
    private val currentWifiOnlySync: () -> Boolean,
    /** wifiOnly 生效值下发（由同步控制器承载） */
    private val updateWifiOnlySync: (Boolean) -> Unit = {},
    /** 锁屏即锁定的仓库持久化（由 ViewModel 注入仓库调用） */
    private val persistLockWhenScreenOff: (Boolean) -> Unit = {}
) {

    // TASK-12 整改：初值自持久化仓库恢复（原为纯内存回显，冷启动静默回落默认值）。
    // ISSUE-P2-21 整改：快照上移至 @Singleton 的 ExtendedSettingsStore——同一进程内的
    // 多个 ViewModel 作用域（活动域宿主 / 各导航域设置页）共享同一份内存权威状态，
    // 任一页面的偏好改动对全部读者即时可见，杜绝「改了开关、宿主域仍读旧值」的分裂。
    private val extendedSettingsFlow = extendedSettingsStore.settings

    /** 进阶偏好当前快照（UI 状态投影与副作用读取共用） */
    val settings: StateFlow<ExtendedSettings> = extendedSettingsFlow

    /**
     * TASK-12 整改：进阶偏好统一变更通道——内存 Flow 更新与持久化落盘原子完成。
     */
    private fun updateExtended(transform: (ExtendedSettings) -> ExtendedSettings) {
        extendedSettingsStore.publish(extendedSettingsFlow.value.let(transform))
        extendedSettingsStore.save(extendedSettingsFlow.value)
    }

    // ========== 安全锁定规则 ==========
    fun setLockWhenScreenOff(enabled: Boolean) {
        updateExtended { it.copy(lockWhenScreenOff = enabled) }
        persistLockWhenScreenOff(enabled)
    }

    /** TASK-12 / TASK-08：wifiOnly 偏好持久化并令周期同步网络约束即时生效 */
    fun setWifiOnlySync(enabled: Boolean) {
        updateWifiOnlySync(enabled)
        extendedSettingsStore.saveWifiOnlySync(enabled)
        reschedulePeriodicSyncIfNeeded()
    }

    fun setLockWhenNavigateBack(enabled: Boolean) = updateExtended { it.copy(lockWhenNavigateBack = enabled) }

    fun setClearPasswordOnLeave(enabled: Boolean) = updateExtended { it.copy(clearPasswordOnLeave = enabled) }

    fun setRememberRecentFiles(enabled: Boolean) = updateExtended { it.copy(rememberRecentFiles = enabled) }

    fun setRememberKeyFileLocation(enabled: Boolean) = updateExtended { it.copy(rememberKeyFileLocation = enabled) }

    fun setShowKillAppOption(enabled: Boolean) = updateExtended { it.copy(showKillAppOption = enabled) }

    // ========== KP2A 扩展：表单自动填充与体验 ==========
    fun setOfferSaveCredentials(enabled: Boolean) = updateExtended { it.copy(offerSaveCredentials = enabled) }

    fun setInlineSuggestionsEnabled(enabled: Boolean) = updateExtended { it.copy(inlineSuggestionsEnabled = enabled) }

    fun setAutoReturnFromQuery(enabled: Boolean) = updateExtended { it.copy(autoReturnFromQuery = enabled) }

    fun setAutofillCopyTotp(enabled: Boolean) = updateExtended { it.copy(autofillCopyTotp = enabled) }

    fun setAutofillShowTotpNotification(enabled: Boolean) =
        updateExtended { it.copy(autofillShowTotpNotification = enabled) }

    fun setSkipDalVerification(enabled: Boolean) = updateExtended { it.copy(skipDalVerification = enabled) }

    fun setOverrideNoAutofill(enabled: Boolean) = updateExtended { it.copy(overrideNoAutofill = enabled) }

    /** ISSUE-P3-42：会话授权宽限开关（默认关闭；关闭时每次下发前仍需二次确认） */
    fun setAutofillSessionGrantEnabled(enabled: Boolean) =
        updateExtended { it.copy(autofillSessionGrantEnabled = enabled) }

    // ========== KP2A 扩展：显示与外观交互 ==========
    fun setMaskPasswordsDefault(enabled: Boolean) = updateExtended { it.copy(maskPasswordsDefault = enabled) }

    fun setMaskTotpDefault(enabled: Boolean) = updateExtended { it.copy(maskTotpDefault = enabled) }

    fun setShowUnlockedNotification(enabled: Boolean) = updateExtended { it.copy(showUnlockedNotification = enabled) }

    fun setShowGroupInSearchResult(enabled: Boolean) = updateExtended { it.copy(showGroupInSearchResult = enabled) }

    fun setShowGroupInEntry(enabled: Boolean) = updateExtended { it.copy(showGroupInEntry = enabled) }

    fun setListDensity(density: ListDensity) = updateExtended { it.copy(listDensity = density) }

    fun setAutoActivateSearchOnOpen(enabled: Boolean) = updateExtended { it.copy(autoActivateSearchOnOpen = enabled) }

    fun setIconSet(iconSet: IconSetOption) = updateExtended { it.copy(iconSet = iconSet) }

    // ========== KP2A 扩展：文件处理与高级同步策略 ==========
    fun setUseOfflineCache(enabled: Boolean) {
        updateExtended { it.copy(useOfflineCache = enabled) }
        // 离线开关联动：实时传导至同步引擎决策树（SyncEngine.isOffline）
        syncCoordinator.setOfflineMode(enabled)
    }

    fun setPeriodicBackgroundSyncEnabled(enabled: Boolean) {
        updateExtended { it.copy(periodicBackgroundSyncEnabled = enabled) }
        // TASK-08 整改：开关接入 WorkManager 唯一周期任务（开启注册 / 关闭取消）
        periodicSyncScheduler.reschedule(
            enabled = enabled,
            intervalMinutes = extendedSettingsFlow.value.periodicBackgroundSyncIntervalMinutes,
            wifiOnly = currentWifiOnlySync()
        )
    }

    fun setPeriodicBackgroundSyncInterval(minutes: Int) {
        updateExtended { it.copy(periodicBackgroundSyncIntervalMinutes = minutes) }
        // TASK-08 整改：间隔变更经 UPDATE 策略原子更新周期任务
        reschedulePeriodicSyncIfNeeded()
    }

    /** TASK-08：周期同步开启时按最新偏好重排任务（wifiOnly/间隔变更共用入口） */
    fun reschedulePeriodicSyncIfNeeded() {
        val settings = extendedSettingsFlow.value
        if (settings.periodicBackgroundSyncEnabled) {
            periodicSyncScheduler.reschedule(
                enabled = true,
                intervalMinutes = settings.periodicBackgroundSyncIntervalMinutes,
                wifiOnly = currentWifiOnlySync()
            )
        }
    }

    fun setAllowedWifiSsids(ssids: String) = updateExtended { it.copy(allowedWifiSsids = ssids) }

    fun setCreateBackupBeforeSave(enabled: Boolean) {
        updateExtended { it.copy(createBackupBeforeSave = enabled) }
        // ISSUE-P2-11 (ZT-16)：设置即下发到唯一会话实例，下一次写盘立即遵循新偏好
        // （关闭时不再生成 .bak，并清理历史遗留 .bak）。
        databaseSession?.createBackupBeforeSave = enabled
    }

    fun setCheckRemoteChangesBeforeSave(enabled: Boolean) =
        updateExtended { it.copy(checkRemoteChangesBeforeSave = enabled) }

    fun setConflictResolution(resolution: ConflictResolution) =
        updateExtended { it.copy(conflictResolution = resolution) }

    fun setUseFileTransactions(enabled: Boolean) = updateExtended { it.copy(useFileTransactions = enabled) }

    fun setWebdavChunkedUpload(enabled: Boolean) = updateExtended { it.copy(webdavChunkedUpload = enabled) }

    fun setWebdavChunkSizeMb(sizeMb: Int) = updateExtended { it.copy(webdavChunkSizeMb = sizeMb) }

    fun setPreloadDatabaseEnabled(enabled: Boolean) = updateExtended { it.copy(preloadDatabaseEnabled = enabled) }

    // ========== KP2A 扩展：TOTP 规范映射 ==========
    /**
     * 更新 TOTP 字段映射。
     *
     * **注意（ISSUE-P3-29 如实标注）**：与原实现一致，本方法**只更新内存 Flow、不落盘**
     * （未走 [updateExtended]）——这是拆分前的既有行为，本次拆分严格逐字保留，未借机改变语义。
     */
    fun updateTotpFieldMapping(seedField: String, settingsField: String, stepSeconds: Int, digits: Int) {
        extendedSettingsStore.publish(
            extendedSettingsFlow.value.copy(
                totpSeedFieldName = seedField,
                totpSettingsFieldName = settingsField,
                defaultTotpStepSeconds = stepSeconds,
                defaultTotpDigits = digits
            )
        )
    }

    // ========== TASK-47：已泄露密码检测（联网，默认关闭） ==========
    /**
     * 开启 / 关闭已泄露密码检测（默认关闭）。
     *
     * 关闭态健康度扫描**不发起任何网络请求**，「已泄露密码」指标无值（UI 如实展示「未启用」）；
     * 开启后重新扫描才会向公开泄露库发起 k-匿名范围查询（仅上送密码 SHA-1 前 5 位）。
     */
    fun setBreachCheckEnabled(enabled: Boolean) = updateExtended { it.copy(breachCheckEnabled = enabled) }

    // ========== KP2A 扩展：调试日志开关 ==========
    fun setDebugLogEnabled(enabled: Boolean) = updateExtended { it.copy(debugLogEnabled = enabled) }

    fun setVerboseSyncLog(enabled: Boolean) = updateExtended { it.copy(verboseSyncLog = enabled) }
}
