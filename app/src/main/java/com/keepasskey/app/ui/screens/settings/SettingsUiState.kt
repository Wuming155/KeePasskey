package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.theme.AppThemeMode

/**
 * 云端同步协议提供商类型
 */
enum class CloudSyncProvider(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    val protocol: String
) {
    WEBDAV(R.string.sync_provider_webdav, R.string.sync_provider_webdav_desc, "WebDAV"),
    S3_COMPATIBLE(R.string.sync_provider_s3, R.string.sync_provider_s3_desc, "S3")
}

/**
 * 列表视图展示密度
 */
enum class ListDensity(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int
) {
    COMPACT(R.string.theme_density_compact, R.string.theme_density_compact_desc),
    NORMAL(R.string.theme_density_normal, R.string.theme_density_normal_desc),
    COMFORTABLE(R.string.theme_density_comfortable, R.string.theme_density_comfortable_desc)
}

/**
 * 图标集风格
 */
enum class IconSetOption(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int
) {
    MATERIAL(R.string.theme_iconset_material, R.string.theme_iconset_material_desc),
    KEEPASS_CLASSIC(R.string.theme_iconset_classic, R.string.theme_iconset_classic_desc),
    MINIMAL_MONOCHROME(R.string.theme_iconset_mono, R.string.theme_iconset_mono_desc)
}

/**
 * 冲突解决策略
 */
enum class ConflictResolution(
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int
) {
    AUTO_MERGE(R.string.sync_conflict_auto_merge, R.string.sync_conflict_auto_merge_desc),
    PROMPT_USER(R.string.sync_conflict_prompt, R.string.sync_conflict_prompt_desc),
    KEEP_REMOTE(R.string.sync_conflict_keep_remote, R.string.sync_conflict_keep_remote_desc),
    KEEP_LOCAL(R.string.sync_conflict_keep_local, R.string.sync_conflict_keep_local_desc)
}

/**
 * 综合 KeePassDX 与 KeePass2Android 的系统设置状态模型 (2026 现代全功能规范)
 */
data class SettingsUiState(
    // 1. 密码库与加密设置 (Database & Encryption) - 完全可调
    // P3-24 整改：库名/路径/默认用户名默认空串（未加载前如实展示，不再预置演示数据），
    // 真实值由 ViewModel 活动库加载后覆盖
    val databaseName: String = "",
    val databasePath: String = "",
    val databaseDefaultUsername: String = "",
    val encryptionAlgorithm: String = "ChaCha20-Poly1305 (256-bit)",
    val kdfAlgorithm: String = "Argon2id",
    val argon2Iterations: Long = 3L,
    val argon2MemoryMb: Long = 64L,
    val argon2Parallelism: Int = 4,
    // P3-23：展示型默认值，无任何生产者覆写（DatabaseSettingsScreen 直读）；
    // 资源化需先在 SettingsViewModel 补 strings 生产者（超出本任务改动面），保留原样
    val compressionAlgorithm: String = "GZip 压缩",
    val recycleBinEnabled: Boolean = true,
    val tanExpiresOnUse: Boolean = true, // KP2A: TAN 一次性凭证使用后自动过期
    val checkForDuplicateUuids: Boolean = true, // KP2A: 自动校验并修复重复 UUID
    val childDatabasesCount: Int = 0, // KP2A: 挂载的子数据库数量

    // 2. 云端多协议同步与文件处理 (Cloud Sync & File Handling)
    val syncProvider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
    // WebDAV 专属字段（M2 整改：默认值一律空串，杜绝示例凭据被静默保存为真实凭据）
    // Wave 15 整改：webdavPassword 明文不再进 UiState/StateFlow（经 ViewModel CharArray 预填通道承载）
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavRemotePath: String = "/keepasskey.kdbx",
    // S3 兼容协议专属字段（M2 整改：默认值一律空串）
    // Wave 15 整改：s3SecretKey 明文不再进 UiState/StateFlow（经 ViewModel CharArray 预填通道承载）；
    // AccessKey ID 属标识符（随请求头明文传输），保留 String 投影
    val s3Endpoint: String = "",
    val s3Bucket: String = "",
    val s3Region: String = "auto",
    val s3AccessKey: String = "",
    val s3ObjectKey: String = "passwords/master_vault.kdbx",
    val s3UsePathStyle: Boolean = false,
    // 通用同步状态（H1 整改：默认值不再写死演示时间戳/假状态文案，由真实同步结果填充；
    // P3-23：syncLastTime 空串占位，真实值由 SettingsViewModel 经 strings.get(sync_last_time_never) 填充）
    val syncLastTime: String = "",
    // P3-23：展示型默认值，无任何生产者覆写（CloudSyncComponents 直读）；资源化需先在
    // SettingsViewModel 补 strings 生产者（超出本任务改动面），保留原样
    val syncStatusText: String = "未验证",
    val autoSyncEnabled: Boolean = true,
    val wifiOnlySync: Boolean = true,
    val isSyncing: Boolean = false,
    val syncFeedbackMessage: UiMessage? = null,
    // KP2A 进阶文件与同步机制
    val useOfflineCache: Boolean = true, // KP2A: 离线本地安全缓存副本
    val syncOnColdStart: Boolean = true, // 软件杀死后重新启动时自动与云端同步 (冷启动自动同步)
    val periodicBackgroundSyncEnabled: Boolean = false, // KP2A: 周期性定时后台同步
    val periodicBackgroundSyncIntervalMinutes: Int = 30, // KP2A: 定时同步周期 (分钟)
    val allowedWifiSsids: String = "", // KP2A: 仅在指定 SSID Wi-Fi 下允许同步
    val createBackupBeforeSave: Boolean = true, // KP2A: 保存覆盖前生成 .bak 备份
    val checkRemoteChangesBeforeSave: Boolean = true, // KP2A: 保存前检查远端修改
    val conflictResolution: ConflictResolution = ConflictResolution.AUTO_MERGE, // KP2A: 冲突解决策略
    val useFileTransactions: Boolean = true, // KP2A: 原子事务写盘
    // Wave 12 传输加固：「允许明文流量」与「信任自签名证书」假开关已整体移除——
    // 同步客户端恒定 TLS-only（SyncHttpClientFactory），二者均属无消费者的空实现且语义不安全
    val webdavChunkedUpload: Boolean = false, // KP2A: WebDAV 分块传输
    val webdavChunkSizeMb: Int = 10, // KP2A: 分块大小
    val preloadDatabaseEnabled: Boolean = true, // KP2A: 预加载数据库加速解锁

    // 3. 表单自动填充与 Passkey (Autofill & Passkey)
    val credentialProviderEnabled: Boolean = true,
    val passkeySupportEnabled: Boolean = true,
    val autofillServiceEnabled: Boolean = true,
    val offerSaveCredentials: Boolean = true, // KP2A: 提示保存新登录凭证
    val inlineSuggestionsEnabled: Boolean = true, // KP2A: 键盘上方内联候选条 (Android 11+)
    val autoReturnFromQuery: Boolean = true, // KP2A: 填充或选定条目后自动返回原应用
    val autofillCopyTotp: Boolean = true, // KP2A: 填充后自动将 TOTP 动态码复制到剪贴板
    val autofillShowTotpNotification: Boolean = false, // KP2A: 填充后在通知栏显示 TOTP 验证码
    val skipDalVerification: Boolean = false, // KP2A: 跳过数字资产链接 (DAL) 校验（预留功能，见 STATUS TASK-43）
    val overrideNoAutofill: Boolean = false, // KP2A: 强制忽略应用的禁止自动填充标记
    // 自动填充黑名单（TASK-44）：改为真实包名条目，由 AutofillBlocklistStore 经独立
    // StateFlow 下发（SettingsViewModel.autofillBlockedPackages）；原无写入方的
    // disabledAutofillQueriesCount 计数已下架

    // 4. 设备解锁与安全策略 (Device Unlock & Security) - 生物识别与锁定策略
    val biometricEnabled: Boolean = true, // 生物识别 / 指纹验证
    val autoLockBackground: Boolean = true,
    val flagSecureEnabled: Boolean = true,
    val autoClearClipboard: Boolean = true,
    val autoLockTimeoutSeconds: Int = 0, // 0 = 立即, 30, 60, 300, 900, -1 = 永不
    val clipboardTimeoutSeconds: Int = 30, // 15, 30, 60, 120, -1 = 不清空
    // 核心安全锁定规则
    val lockWhenScreenOff: Boolean = true, // 熄屏时立即锁定
    val lockWhenNavigateBack: Boolean = false, // 返回退出应用时锁定
    val clearPasswordOnLeave: Boolean = false, // 离开密码页清空已输入字符
    val rememberRecentFiles: Boolean = true, // 记住最近打开的数据库
    val rememberKeyFileLocation: Boolean = true, // 记住密钥文件关联位置
    val showKillAppOption: Boolean = false, // 提供彻底杀死/终止应用进程入口

    // 5. 外观与显示偏好 (Appearance & Display)
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themePalette: com.keepasskey.app.ui.theme.AppThemePalette = com.keepasskey.app.ui.theme.AppThemePalette.SAPPHIRE,
    val appLanguage: com.keepasskey.app.data.repository.AppLanguage = com.keepasskey.app.data.repository.AppLanguage.SYSTEM,
    val oledBlackOptimization: Boolean = false,
    val dynamicColorEnabled: Boolean = false, // Material You 动态取色 (Android 12+)
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true,
    val showUrlInList: Boolean = true, // Monica 灵感: 列表中展示条目关联网址/域名
    val hideFabOnScroll: Boolean = false, // Monica 灵感: 列表滚动时自动隐藏新建悬浮按钮
    val hapticFeedbackEnabled: Boolean = true, // Monica 灵感: 复制与安全操作触觉震动反馈
    val maskPasswordsDefault: Boolean = true, // KP2A: 详情页默认遮掩密码
    val maskTotpDefault: Boolean = false, // KP2A: 默认遮掩 TOTP 动态码
    val showUnlockedNotification: Boolean = true, // KP2A: 通知栏已解锁常驻快捷方式
    val showGroupInSearchResult: Boolean = true, // KP2A: 搜索结果中展示完整分组路径
    val showGroupInEntry: Boolean = false, // KP2A: 条目详情页标识所属分组
    val listDensity: ListDensity = ListDensity.NORMAL, // KP2A: 列表显示紧凑度
    val autoActivateSearchOnOpen: Boolean = false, // KP2A: 打开数据库后自动聚焦搜索栏
    val iconSet: IconSetOption = IconSetOption.MATERIAL, // KP2A: 图标集风格
    val showAuthenticatorTab: Boolean = true, // 是否在底部导航栏显示「验证码」
    val showGeneratorTab: Boolean = true, // 是否在底部导航栏显示「密码生成器」

    // 6. 两步验证与 TOTP 高级规范映射 (Tray TOTP / Custom Fields)
    val totpSeedFieldName: String = "TOTP Seed", // KP2A: 密钥种子字段名
    val totpSettingsFieldName: String = "TOTP Settings", // KP2A: 设置参数字段名
    val defaultTotpStepSeconds: Int = 30, // 默认步长 (30秒)
    val defaultTotpDigits: Int = 6, // 默认位数 (6位)

    // 7. 密码库健康度检查与审计 (Audit & Health Check)
    // P3-23：默认空串占位——真实初值由 SettingsHealthController 经 strings.get
    // （health_status_not_scanned / health_scan_hint_idle）填充
    val healthScore: Int = 0,
    val healthStatus: String = "",
    val healthMessage: String = "",
    val weakPasswordCount: Int = 0,
    val reusedPasswordCount: Int = 0,
    val compromisedPasswordCount: Int = 0,
    val lastHealthScanTime: String = "",
    val isHealthScanning: Boolean = false,

    // 8. 调试日志与系统诊断 (Debug & Diagnostics)
    val debugLogEnabled: Boolean = false, // KP2A: 启用调试日志
    val debugLogLines: List<String> = emptyList(), // KP2A: 进程内真实调试日志环形缓冲快照
    val verboseSyncLog: Boolean = false, // KP2A: 详细同步与网络日志
    val debugLogRecordsCount: Int = 128,

    // 9. 关于与系统信息 (About & Info)
    val appVersion: String = "v1.0.0-Preview (2026 Edition)",
    val buildNumber: String = "Build 2026.09.04",
    val kdbxFormat: String = "KDBX 4.1 (Argon2id + ChaCha20)"
)

/**
 * KDF 设备自适应基准状态（M6 整改：KdfBenchmark 真实接线，替代原假基准按钮）。
 * [recommendedIterations]/[recommendedMemoryMb]/[recommendedParallelism] 非空时表示基准完成，
 * UI 应将推荐值填入 Argon2 参数调节项。
 */
data class KdfBenchmarkUiState(
    val isRunning: Boolean = false,
    val recommendedIterations: Long? = null,
    val recommendedMemoryMb: Long? = null,
    val recommendedParallelism: Int? = null,
    val errorMessage: String? = null
)
