package com.keepasskey.app.ui.screens.settings

/**
 * KP2A 进阶偏好状态集（TASK-12 整改：原为 SettingsViewModel 私有纯内存回显，
 * 现提升为模块内公共模型，由 [com.keepasskey.app.data.repository.ExtendedSettingsStore]
 * 经 SharedPreferences 持久化，冷启动不丢失）。
 *
 * 注意：本模型仅承载「用户偏好」本身；偏好是否有真实消费方属功能接线问题，
 * 已在 docs/ACTIVE_ISSUES.md ISSUE-P3-03 (TASK-43) 中规划说明
 * （skipDalVerification 已随 ISSUE-P2-02 接入 PasskeyCreateActivity 注册门控）。
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
    // ISSUE-P3-42：会话授权宽限（默认关闭）——开启后在库已解锁且短时间内已确认过的
    // 同一「包名 + 域」上跳过重复二次确认；关闭时保持「每次下发前强制二次确认」不变。
    val autofillSessionGrantEnabled: Boolean = false,
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

    // TASK-47：已泄露密码检测（联网，k-匿名范围查询）
    // 默认关闭——本项目定位「离线优先、零外联」，任何对外查询必须由用户显式开启；
    // 关闭态健康度扫描不发起任何网络请求，「已泄露密码」指标无值（不回显 0）
    val breachCheckEnabled: Boolean = false,

    // 调试日志
    val debugLogEnabled: Boolean = false,
    val verboseSyncLog: Boolean = false
)
