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
 * 综合 KeePassDX 与 KeePass2Android 的系统设置状态模型
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

    // 2. 云端多协议同步 (Cloud Sync - WebDAV & S3 Compatible)
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

    // 3. 表单自动填充与 Passkey (Autofill & Passkey)
    val credentialProviderEnabled: Boolean = true,
    val passkeySupportEnabled: Boolean = true,
    val autofillServiceEnabled: Boolean = true,

    // 4. 设备解锁与安全策略 (Device Unlock & Security) - 完全可调
    val biometricEnabled: Boolean = true,
    val autoLockBackground: Boolean = true,
    val flagSecureEnabled: Boolean = true,
    val autoClearClipboard: Boolean = true,
    val autoLockTimeoutSeconds: Int = 0, // 0 = 立即, 30, 60, 300, 900, -1 = 永不
    val autoLockTimeoutLabel: String = "立即锁定",
    val clipboardTimeoutSeconds: Int = 30, // 15, 30, 60, 120, -1 = 不清空
    val clipboardTimeoutLabel: String = "30 秒",

    // 5. 外观与显示偏好 (Appearance & Display)
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val oledBlackOptimization: Boolean = false,
    val showUsernameInList: Boolean = true,
    val showOtpInList: Boolean = true,
    val showPasskeyBadge: Boolean = true,

    // 6. 密码库健康度检查与审计 (Audit & Health Check)
    val healthScore: Int = 94,
    val healthStatus: String = "优秀",
    val healthMessage: String = "发现 1 个密码重复使用，未发现已知泄露",
    val weakPasswordCount: Int = 0,
    val reusedPasswordCount: Int = 1,
    val compromisedPasswordCount: Int = 0,
    val lastHealthScanTime: String = "今天 10:20",
    val isHealthScanning: Boolean = false,

    // 7. 关于与系统信息 (About & Info)
    val appVersion: String = "v1.0.0-Preview (2026 Edition)",
    val buildNumber: String = "Build 2026.09.04",
    val kdbxFormat: String = "KDBX 4.1 (Argon2id + ChaCha20)"
)
