package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.ui.theme.AppThemeMode

/**
 * 云端同步协议提供商类型
 */
enum class CloudSyncProvider(val label: String, val desc: String) {
    WEBDAV("WebDAV", "标准 WebDAV 协议 (Nextcloud / ownCloud / 坚果云 / 群晖 NAS)"),
    S3_COMPATIBLE("兼容 S3 存储", "兼容 AWS S3 协议对象存储 (Cloudflare R2 / AWS / MinIO / 阿里云 OSS)")
}

/**
 * 列表视图展示密度
 */
enum class ListDensity(val label: String, val desc: String) {
    COMPACT("紧凑", "缩小行高与间距，单屏展示更多条目"),
    NORMAL("标准", "平衡的可读性与触控舒适度"),
    COMFORTABLE("宽松", "更大间距与字号，适合大屏与长文本")
}

/**
 * 图标集风格
 */
enum class IconSetOption(val label: String, val desc: String) {
    MATERIAL("Material 3 现代矢量", "贴合现代 Material You 设计语言的矢量图标"),
    KEEPASS_CLASSIC("KeePass 经典", "KeePass 2.x 经典 16x16 拟物图标集"),
    MINIMAL_MONOCHROME("极简单色", "高对比度单色线条图标")
}

/**
 * 冲突解决策略
 */
enum class ConflictResolution(val label: String, val desc: String) {
    AUTO_MERGE("自动三方合并", "优先自动保留最新字段，不中断流程 (推荐)"),
    PROMPT_USER("询问用户", "检测到冲突时弹出合并/另存对比对话框"),
    KEEP_REMOTE("以云端版本为准", "放弃本地未同步修改，拉取云端副本"),
    KEEP_LOCAL("以本地版本为准", "强制将本地数据库覆盖到云端")
}

/**
 * 综合 KeePassDX 与 KeePass2Android 的系统设置状态模型 (2026 现代全功能规范)
 */
data class SettingsUiState(
    // 1. 密码库与加密设置 (Database & Encryption) - 完全可调
    val databaseName: String = "master_vault.kdbx",
    val databasePath: String = "/storage/emulated/0/Documents/master_vault.kdbx",
    val databaseDefaultUsername: String = "user@keepasskey.com",
    val encryptionAlgorithm: String = "ChaCha20-Poly1305 (256-bit)",
    val kdfAlgorithm: String = "Argon2id",
    val argon2Iterations: Long = 3L,
    val argon2MemoryMb: Long = 64L,
    val argon2Parallelism: Int = 4,
    val compressionAlgorithm: String = "GZip 压缩",
    val recycleBinEnabled: Boolean = true,
    val tanExpiresOnUse: Boolean = true, // KP2A: TAN 一次性凭证使用后自动过期
    val checkForDuplicateUuids: Boolean = true, // KP2A: 自动校验并修复重复 UUID
    val childDatabasesCount: Int = 0, // KP2A: 挂载的子数据库数量

    // 2. 云端多协议同步与文件处理 (Cloud Sync & File Handling)
    val syncProvider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
    // WebDAV 专属字段
    val webdavUrl: String = "https://cloud.example.com/remote.php/dav/files/user/",
    val webdavUsername: String = "vault_master",
    val webdavPassword: String = "mypassword123",
    val webdavPasswordMasked: String = "••••••••••••",
    val webdavRemotePath: String = "/Passkeys/keepasskey.kdbx",
    // S3 兼容协议专属字段
    val s3Endpoint: String = "https://<account_id>.r2.cloudflarestorage.com",
    val s3Bucket: String = "my-secure-vault",
    val s3Region: String = "auto",
    val s3AccessKey: String = "AKIAIOSFODNN7EXAMPLE",
    val s3SecretKey: String = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    val s3SecretKeyMasked: String = "••••••••••••••••••••••••",
    val s3ObjectKey: String = "passwords/master_vault.kdbx",
    // 通用同步状态
    val syncLastTime: String = "今天 10:20 (本地原子写盘已通过校验)",
    val syncStatusText: String = "已连接并保持同步",
    val autoSyncEnabled: Boolean = true,
    val wifiOnlySync: Boolean = true,
    val isSyncing: Boolean = false,
    val syncFeedbackMessage: String? = null,
    // KP2A 进阶文件与同步机制
    val useOfflineCache: Boolean = true, // KP2A: 离线本地安全缓存副本
    val periodicBackgroundSyncEnabled: Boolean = false, // KP2A: 周期性定时后台同步
    val periodicBackgroundSyncIntervalMinutes: Int = 30, // KP2A: 定时同步周期 (分钟)
    val allowedWifiSsids: String = "", // KP2A: 仅在指定 SSID Wi-Fi 下允许同步
    val createBackupBeforeSave: Boolean = true, // KP2A: 保存覆盖前生成 .bak 备份
    val checkRemoteChangesBeforeSave: Boolean = true, // KP2A: 保存前检查远端修改
    val conflictResolution: ConflictResolution = ConflictResolution.AUTO_MERGE, // KP2A: 冲突解决策略
    val useFileTransactions: Boolean = true, // KP2A: 原子事务写盘
    val acceptAllCertificates: Boolean = false, // KP2A: 接受自签名 SSL/TLS 证书
    val cleartextTrafficPermitted: Boolean = false, // KP2A: 允许 HTTP 明文流量
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
    val skipDalVerification: Boolean = false, // KP2A: 跳过数字资产链接 (DAL) 校验
    val overrideNoAutofill: Boolean = false, // KP2A: 强制忽略应用的禁止自动填充标记
    val disabledAutofillQueriesCount: Int = 0, // KP2A: 已禁用的自动填充黑名单数量

    // 4. 设备解锁与安全策略 (Device Unlock & Security) - 完全可调
    val biometricEnabled: Boolean = true,
    val autoLockBackground: Boolean = true,
    val flagSecureEnabled: Boolean = true,
    val autoClearClipboard: Boolean = true,
    val autoLockTimeoutSeconds: Int = 0, // 0 = 立即, 30, 60, 300, 900, -1 = 永不
    val autoLockTimeoutLabel: String = "立即锁定",
    val clipboardTimeoutSeconds: Int = 30, // 15, 30, 60, 120, -1 = 不清空
    val clipboardTimeoutLabel: String = "30 秒",
    // KP2A 核心安全机制
    val quickUnlockEnabled: Boolean = true, // KP2A: 快速解锁主开关
    val quickUnlockLength: Int = 3, // KP2A: 快速解锁 PIN 截取长度 (默认3位)
    val quickUnlockObscureInput: Boolean = true, // KP2A: 隐蔽输入模式 (无点号/字符回显防旁窥)
    val quickUnlockHideLength: Boolean = false, // KP2A: 隐藏所需 PIN 位数
    val quickUnlockRequireDeviceLock: Boolean = true, // KP2A: 设备未设锁屏密码时禁用快速解锁
    val quickUnlockUseDedicatedKey: Boolean = false, // KP2A: 使用库内专用 PIN 而非主密码后缀
    val lockWhenScreenOff: Boolean = true, // KP2A: 熄屏时立即锁定
    val lockWhenNavigateBack: Boolean = false, // KP2A: 返回退出应用时锁定
    val clearPasswordOnLeave: Boolean = false, // KP2A: 离开密码页清空已输入字符
    val rememberRecentFiles: Boolean = true, // KP2A: 记住最近打开的数据库
    val rememberKeyFileLocation: Boolean = true, // KP2A: 记住密钥文件关联位置
    val showKillAppOption: Boolean = false, // KP2A: 提供彻底杀死/终止应用进程入口

    // 5. 外观与显示偏好 (Appearance & Display)
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val appLanguage: com.keepasskey.app.data.repository.AppLanguage = com.keepasskey.app.data.repository.AppLanguage.SYSTEM,
    val oledBlackOptimization: Boolean = false,
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true,
    val maskPasswordsDefault: Boolean = true, // KP2A: 详情页默认遮掩密码
    val maskTotpDefault: Boolean = false, // KP2A: 默认遮掩 TOTP 动态码
    val showUnlockedNotification: Boolean = true, // KP2A: 通知栏已解锁常驻快捷方式
    val showGroupInSearchResult: Boolean = true, // KP2A: 搜索结果中展示完整分组路径
    val showGroupInEntry: Boolean = false, // KP2A: 条目详情页标识所属分组
    val listDensity: ListDensity = ListDensity.NORMAL, // KP2A: 列表显示紧凑度
    val autoActivateSearchOnOpen: Boolean = false, // KP2A: 打开数据库后自动聚焦搜索栏
    val iconSet: IconSetOption = IconSetOption.MATERIAL, // KP2A: 图标集风格

    // 6. 两步验证与 TOTP 高级规范映射 (Tray TOTP / Custom Fields)
    val totpSeedFieldName: String = "TOTP Seed", // KP2A: 密钥种子字段名
    val totpSettingsFieldName: String = "TOTP Settings", // KP2A: 设置参数字段名
    val defaultTotpStepSeconds: Int = 30, // 默认步长 (30秒)
    val defaultTotpDigits: Int = 6, // 默认位数 (6位)

    // 7. 密码库健康度检查与审计 (Audit & Health Check)
    val healthScore: Int = 94,
    val healthStatus: String = "优秀",
    val healthMessage: String = "发现 1 个密码重复使用，未发现已知泄露",
    val weakPasswordCount: Int = 0,
    val reusedPasswordCount: Int = 1,
    val compromisedPasswordCount: Int = 0,
    val lastHealthScanTime: String = "今天 10:20",
    val isHealthScanning: Boolean = false,

    // 8. 调试日志与系统诊断 (Debug & Diagnostics)
    val debugLogEnabled: Boolean = false, // KP2A: 启用调试日志
    val verboseSyncLog: Boolean = false, // KP2A: 详细同步与网络日志
    val debugLogRecordsCount: Int = 128,

    // 9. 关于与系统信息 (About & Info)
    val appVersion: String = "v1.0.0-Preview (2026 Edition)",
    val buildNumber: String = "Build 2026.09.04",
    val kdbxFormat: String = "KDBX 4.1 (Argon2id + ChaCha20)"
)
