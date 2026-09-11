package com.keepasskey.app.ui.screens.settings

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.data.childdb.ChildDatabaseFailureReason
import com.keepasskey.app.data.childdb.ChildDatabaseMountState
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
    // ISSUE-P3-20：挂载的子数据库数量。语义 = **已挂载**（含未解锁），非「已解锁数」；
    // 真实值由 SettingsViewModel 经核心层 mountedCount 下发，此处的 0 仅是
    // `uiState` 首次发射前的占位默认值（与本文件其余字段同一约定），不是硬编码真相源。
    val childDatabasesCount: Int = 0,

    // 2. 云端多协议同步与文件处理 (Cloud Sync & File Handling)
    val syncProvider: CloudSyncProvider = CloudSyncProvider.WEBDAV,
    // WebDAV 专属字段（M2 整改：默认值一律空串，杜绝示例凭据被静默保存为真实凭据）
    // Wave 15 整改：webdavPassword 明文不再进 UiState/StateFlow（经 ViewModel CharArray 预填通道承载）
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavRemotePath: String = "/keepasskey.kdbx",
    // S3 兼容协议专属字段（M2 整改：默认值一律空串）
    // Wave 15 整改：s3SecretKey 明文不再进 UiState/StateFlow（经 ViewModel CharArray 预填通道承载）；
    // ISSUE-P2-01：s3AccessKey 同步改造——AccessKey ID 亦不再以 String 驻留 UiState/StateFlow，
    // 与 SecretKey 同走 CharArray 一次性预填通道（ViewModel.s3AccessKeyPrefill）
    val s3Endpoint: String = "",
    val s3Bucket: String = "",
    val s3Region: String = "auto",
    val s3ObjectKey: String = "keepasskey.kdbx",
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
    val skipDalVerification: Boolean = false, // KP2A: 跳过数字资产链接 (DAL) 校验（ISSUE-P2-02 已接线：Passkey 注册门控）
    val overrideNoAutofill: Boolean = false, // KP2A: 强制忽略应用的禁止自动填充标记
    val autofillSessionGrantEnabled: Boolean = false, // ISSUE-P3-42: 会话授权宽限（短时免重复二次确认，默认关闭）
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
    // TASK-47：泄露检测指标——null = 未检测（未启用 / 失败），绝不回填 0 冒充「未泄露」
    val compromisedPasswordCount: Int? = null,
    val breachCheckStatus: com.keepasskey.app.data.breach.BreachCheckStatus =
        com.keepasskey.app.data.breach.BreachCheckStatus.DISABLED,
    // TASK-47：泄露检测补充文案（失败原因等），非 FAILED 态为空串
    val breachCheckMessage: String = "",
    // TASK-47：泄露检测开关（默认关闭，联网查询须显式开启）
    val breachCheckEnabled: Boolean = false,
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
    val kdbxFormat: String = "KDBX 4.1 (Argon2id + ChaCha20)",

    // ISSUE-P2-08（ZT-13）：运行环境完整性扫描快照——风险提示卡片数据源。
    // null = 尚未接入 / 未注入（不渲染风险卡片，也绝不回填「安全」假值）
    val integrityReport: com.keepasskey.app.security.RuntimeIntegrityReport? = null
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

// ===================== ISSUE-P3-20：子库挂载（UI 接线）状态与文案映射 =====================

/**
 * 单条子库挂载的**展示态**。
 *
 * 只承载非敏感信息（别名 + 状态 + 是否有凭据重试入口）：子库凭据与条目明文
 * 一律不进入本类型，也不进入任何 `StateFlow`（核心层投影只暴露非敏感展示字段）。
 */
data class ChildDatabaseMountUiState(
    /** 挂载身份（仅用于触发解锁 / 卸载，无密钥含义） */
    val mountId: String,
    /** 用户可见别名（核心层已归一化，1..64 可见字符） */
    val alias: String,
    /** 状态呈现（文案或不确定进度） */
    val status: ChildDatabaseStatus,
    /**
     * 是否提供「重新输入凭据解锁」入口。
     *
     * [ChildDatabaseMountState.Opened]（已打开）与 [ChildDatabaseMountState.Opening]（进行中）为 false，
     * 其余状态为 true——**「已挂载」不等于「已解锁」**，未解锁时必须给出真实的重试入口。
     */
    val canRetryWithCredentials: Boolean
)

/**
 * 子库运行时状态的**呈现方式**。
 *
 * [Opening] 刻意不映射任何文案：解密打开是瞬时态，用「未解锁」或「已解锁」表达都会失真，
 * UI 以不确定进度指示器呈现「正在打开」这一事实本身。
 */
sealed interface ChildDatabaseStatus {

    /** 有明确文案的状态；文案参数（如已解锁的条目数）由 [UiMessage] 承载 */
    data class Text(val message: UiMessage) : ChildDatabaseStatus

    /** 正在解密打开（无文案：不用「未解锁」谎报进度，也不用「已解锁」提前报喜） */
    data object Opening : ChildDatabaseStatus
}

/** 子库操作反馈：成功与失败以 [isError] 显式区分配色，不靠猜测文案内容 */
data class ChildDatabaseFeedback(val message: UiMessage, val isError: Boolean)

/**
 * 子库挂载对话框整体状态。
 *
 * [available] 为 false 表示会话管理器未注入（仅单测 / 异常装配场景）：UI 如实呈现**不可用**
 * （控件整体禁用），不呈现任何点得动却没反应的假入口。
 */
data class ChildDatabaseUiState(
    val available: Boolean = false,
    val mounts: List<ChildDatabaseMountUiState> = emptyList(),
    val feedback: ChildDatabaseFeedback? = null
)

/**
 * 子库**状态 / 失败分型 → 文案**映射（纯函数，无 Android 依赖，可 JVM 直测）。
 *
 * 领域层只报键（`ChildDatabaseMountState` / `ChildDatabaseFailureReason` 枚举），
 * 用户文案一律在此收敛到 `strings.xml` 资源 ID；失败分型经
 * [ChildDatabaseFailureReason.of] 从失败结果取回（自动穿透 `cause` 链）。
 *
 * 两处 `when` 均为**穷尽分支**：新增状态或分型会在此处编译失败，
 * 杜绝「新分型静默套用未知错误文案」。
 */
internal object ChildDatabaseStatusText {

    /** 运行时状态 → 呈现方式 */
    fun of(state: ChildDatabaseMountState): ChildDatabaseStatus = when (state) {
        ChildDatabaseMountState.Closed ->
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_locked))

        ChildDatabaseMountState.Opening -> ChildDatabaseStatus.Opening

        is ChildDatabaseMountState.Opened -> ChildDatabaseStatus.Text(
            UiMessage(
                R.string.dbset_child_db_opened_summary,
                listOf(state.snapshot.entries.size)
            )
        )

        ChildDatabaseMountState.CredentialRejected ->
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_rejected))

        ChildDatabaseMountState.SourceUnavailable ->
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_unavailable))

        is ChildDatabaseMountState.Failed -> ChildDatabaseStatus.Text(UiMessage(of(state.reason)))
    }

    /** 13 种失败分型 → 专用文案资源 */
    @StringRes
    fun of(reason: ChildDatabaseFailureReason): Int = when (reason) {
        ChildDatabaseFailureReason.INVALID_ALIAS -> R.string.dbset_child_db_err_invalid_alias
        ChildDatabaseFailureReason.INVALID_SOURCE -> R.string.dbset_child_db_err_invalid_source
        ChildDatabaseFailureReason.DUPLICATE_MOUNT -> R.string.dbset_child_db_err_duplicate
        ChildDatabaseFailureReason.MOUNT_NOT_FOUND -> R.string.dbset_child_db_err_mount_not_found
        ChildDatabaseFailureReason.CREDENTIAL_MISSING -> R.string.dbset_child_db_err_credential_missing
        ChildDatabaseFailureReason.CREDENTIAL_REJECTED -> R.string.dbset_child_db_err_credential_rejected
        ChildDatabaseFailureReason.THROTTLED -> R.string.dbset_child_db_err_throttled
        ChildDatabaseFailureReason.SOURCE_UNAVAILABLE -> R.string.dbset_child_db_err_source_unavailable
        ChildDatabaseFailureReason.CORRUPT_FILE -> R.string.dbset_child_db_err_corrupt
        ChildDatabaseFailureReason.UNSUPPORTED_VERSION -> R.string.dbset_child_db_err_unsupported_version
        ChildDatabaseFailureReason.IO_ERROR -> R.string.dbset_child_db_err_io
        ChildDatabaseFailureReason.TERMINATED -> R.string.dbset_child_db_err_terminated
        ChildDatabaseFailureReason.UNKNOWN -> R.string.dbset_child_db_err_unknown
    }

    /** 是否提供凭据重试入口：仅「已打开」与「正在打开」不提供 */
    fun allowsCredentialRetry(state: ChildDatabaseMountState): Boolean =
        state !is ChildDatabaseMountState.Opened && state !is ChildDatabaseMountState.Opening
}

/** 挂载成功反馈（仅在核心层真实返回成功后才使用，不预置、不乐观上报） */
internal fun childDatabaseMountedFeedback(): ChildDatabaseFeedback =
    ChildDatabaseFeedback(UiMessage(R.string.dbset_child_db_mounted), isError = false)

/** 失败反馈：分型经 `cause` 链自动穿透后映射到专用文案 */
internal fun childDatabaseFailureFeedback(error: Throwable): ChildDatabaseFeedback =
    ChildDatabaseFeedback(
        UiMessage(ChildDatabaseStatusText.of(ChildDatabaseFailureReason.of(error))),
        isError = true
    )

/**
 * 来源展示名（非敏感元数据）：取 Uri / 路径末段，供挂载表单回显「已选哪个文件」。
 *
 * 纯字符串处理（**不引用 `android.net.Uri`**），故可在 JVM 单测直接断言；
 * 空串或全空白一律回落为原串，绝不产出空标签。
 */
internal fun childDatabaseSourceDisplayName(sourceUri: String): String {
    val trimmed = sourceUri.trim()
    return trimmed.substringAfterLast('/').ifBlank { trimmed }
}
