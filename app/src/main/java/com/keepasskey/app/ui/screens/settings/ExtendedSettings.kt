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
    // ISSUE-P3-272：自动同步总开关真实接线——控制「解锁后进入列表页自动同步一次」
    // （VaultListViewModel init 消费点）；关闭后仅手动下拉刷新 / 设置页手动同步触发。
    val autoSyncEnabled: Boolean = true,
    val createBackupBeforeSave: Boolean = true,
    val checkRemoteChangesBeforeSave: Boolean = true,
    val conflictResolution: ConflictResolution = ConflictResolution.AUTO_MERGE,
    val webdavChunkedUpload: Boolean = false,
    val webdavChunkSizeMb: Int = 10,

    // 安全锁定规则与环境
    val lockWhenScreenOff: Boolean = true,
    val lockWhenNavigateBack: Boolean = false,
    val clearPasswordOnLeave: Boolean = false,
    val rememberKeyFileLocation: Boolean = true,
    val showKillAppOption: Boolean = false,

    // 自动填充进阶
    val offerSaveCredentials: Boolean = true,
    // ISSUE-P2-71（审计 E2）：默认 **关闭**。内联建议会把**候选的用户名 / 条目标题**
    // 作为文案交给系统 IME（`AutofillInlinePresentationFactory.build(title = username
    // ifBlank entry.title, subtitle = entry.title)`），而 IME 可能是第三方 / 云端联想键盘，
    // 该通道一旦开启即等于把条目名与账号名持续送出应用边界。故按「安全默认不放松」改为
    // 显式开启（关闭时不影响自动填充本身——数据集仍经下拉 / 填充对话框呈现）。
    val inlineSuggestionsEnabled: Boolean = false,
    val autoReturnFromQuery: Boolean = true,
    // ISSUE-P2-43：默认 **关闭**。开启后每次自动填充确认都会把该条目的 TOTP 动态码写入
    // 系统剪贴板（`AutofillConfirmActivity` → `AutofillTotpCopyPolicy` → `copySensitiveText`）：
    // 剪贴板在擦除窗口内可被前台应用读取，而用户可关闭定时擦除（见 ISSUE-P3-84）——
    // 与口令泄露组合即可在有效期内完成第二因素绕过。故改为用户显式开启。
    val autofillCopyTotp: Boolean = false,
    val autofillShowTotpNotification: Boolean = false,
    /**
     * 设置页「跳过通行密钥站点归属校验」开关（`ISSUE-P2-240` 前的界面文案误写为
     * 「跳过浏览器兼容层」，与真实语义无关，已更正）。
     *
     * 唯一生产消费方是 `PasskeyCreateActivity` 的 `PasskeyRegistrationGate`——开启即跳过
     * `DigitalAssetLinksVerifier` 的远程归属声明校验，默认关闭且只作用于通行密钥注册侧。
     */
    val skipDalVerification: Boolean = false,
    val overrideNoAutofill: Boolean = false,
    // ISSUE-P3-42：会话授权宽限（默认关闭）——开启后在库已解锁且短时间内已确认过的
    // 同一「包名 + 域」上跳过重复二次确认；关闭时保持「每次下发前强制二次确认」不变。
    val autofillSessionGrantEnabled: Boolean = false,
    // ISSUE-P2-228：三条**通道总开关**自 `SettingsPreferencesController` 的纯内存态迁入持久化。
    // 迁移前它们是「无写入方持久化、无生产消费方」的假开关（关掉不影响任何行为、重启即回 true），
    // 且其中「旧版自动填充服务」的副标题声称走无障碍通道——本应用**没有任何 AccessibilityService**。
    // 现由 `KeePasskeyAutofillService` / `KeePasskeyCredentialProviderService` 在每次请求时求值：
    // 关闭即该通道不下发任何凭据、不接受保存（默认 true = 与迁移前的实际可观察行为一致）。
    val credentialProviderEnabled: Boolean = true,
    val passkeySupportEnabled: Boolean = true,
    val autofillServiceEnabled: Boolean = true,
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
    // ISSUE-P3-309：全文搜索匹配档（默认维持子串口径，行为零变更；分词档见 SearchMatchMode KDoc）
    val searchMatchMode: SearchMatchMode = SearchMatchMode.CONTAINS,

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
