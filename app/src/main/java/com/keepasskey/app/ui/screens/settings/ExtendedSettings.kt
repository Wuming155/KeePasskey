package com.keepasskey.app.ui.screens.settings

/**
 * KP2A 进阶偏好状态集（TASK-12 整改：原为 SettingsViewModel 私有纯内存回显，
 * 现提升为模块内公共模型，由 [com.keepasskey.app.data.repository.ExtendedSettingsStore]
 * 经 SharedPreferences 持久化，冷启动不丢失）。
 *
 * 注意：本模型仅承载「用户偏好」本身；偏好是否有真实消费方属功能接线问题，
 * 已在 STATUS.md TASK-12 说明中如实区分（skipDalVerification 假开关已下架）。
 */
data class ExtendedSettings(
    // 文件处理与进阶同步
    val useOfflineCache: Boolean = true,
    val periodicBackgroundSyncEnabled: Boolean = false,
    val periodicBackgroundSyncIntervalMinutes: Int = 30,
    val allowedWifiSsids: String = "",
    val createBackupBeforeSave: Boolean = true,
    val checkRemoteChangesBeforeSave: Boolean = true,
    val conflictResolution: ConflictResolution = ConflictResolution.AUTO_MERGE,
    val useFileTransactions: Boolean = true,
    val webdavChunkedUpload: Boolean = false,
    val webdavChunkSizeMb: Int = 10,
    val preloadDatabaseEnabled: Boolean = true,

    // 安全锁定规则与环境
    val lockWhenScreenOff: Boolean = true,
    val lockWhenNavigateBack: Boolean = false,
    val clearPasswordOnLeave: Boolean = false,
    val rememberRecentFiles: Boolean = true,
    val rememberKeyFileLocation: Boolean = true,
    val showKillAppOption: Boolean = false,

    // 自动填充进阶
    val offerSaveCredentials: Boolean = true,
    val inlineSuggestionsEnabled: Boolean = true,
    val autoReturnFromQuery: Boolean = true,
    val autofillCopyTotp: Boolean = true,
    val autofillShowTotpNotification: Boolean = false,
    val skipDalVerification: Boolean = false,
    val overrideNoAutofill: Boolean = false,
    // 自动填充黑名单已由 AutofillBlocklistStore 承载（TASK-44）：
    // 原 disabledAutofillQueriesCount 计数无任何写入方，随本次整改一并下架

    // 显示与视觉进阶
    val maskPasswordsDefault: Boolean = true,
    val maskTotpDefault: Boolean = false,
    val showUnlockedNotification: Boolean = true,
    val showGroupInSearchResult: Boolean = true,
    val showGroupInEntry: Boolean = false,
    val listDensity: ListDensity = ListDensity.NORMAL,
    val autoActivateSearchOnOpen: Boolean = false,
    val iconSet: IconSetOption = IconSetOption.MATERIAL,

    // TOTP 规范字段映射
    val totpSeedFieldName: String = "TOTP Seed",
    val totpSettingsFieldName: String = "TOTP Settings",
    val defaultTotpStepSeconds: Int = 30,
    val defaultTotpDigits: Int = 6,

    // 调试日志
    val debugLogEnabled: Boolean = false,
    val verboseSyncLog: Boolean = false
)
