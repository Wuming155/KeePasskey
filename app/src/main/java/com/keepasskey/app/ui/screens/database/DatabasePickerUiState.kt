package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultDatabaseInfo

/**
 * 来源类型：本地存储、WebDAV 云端、S3 兼容对象存储
 *
 * P3-23 说明：[label] 是经 `importExternalDatabase(syncType = ...)` 落库并随数据库卡片
 * 回显的持久化标签（DatabasePickerViewModel 消费），值需保持稳定，不资源化。
 * ISSUE-P3-330（整改中新增发现）：原 descRes（各来源说明）声明后从未被渲染，已连同
 * 字符串一并移除——对话框来源行实际渲染 picker_chip_*，不得再加回死声明。
 */
enum class OpenVaultSourceType(
    val label: String
) {
    LOCAL("本地设备存储"),
    WEBDAV("WebDAV 云存储"),
    S3_COMPATIBLE("兼容 S3 对象存储")
}

/**
 * 「打开已有 KDBX 密码库」对话框的提交载荷（ISSUE-P2-399）。
 *
 * 本地库只带路径；云端库携带完整连接凭据（[com.keepasskey.app.sync.CloudVaultImportRequest]，
 * 凭据 `CharArray` 为借用语义：对话框传副本后即交出，由导入链路用毕擦除）。
 */
sealed interface OpenVaultSubmission {

    /** 本地库：SAF 选择器选定的 uri 或文件路径 */
    data class Local(val name: String, val path: String) : OpenVaultSubmission

    /** 云端库：WebDAV / S3 完整凭据，由 [com.keepasskey.app.sync.CloudVaultImporter] 消费 */
    data class Cloud(val request: com.keepasskey.app.sync.CloudVaultImportRequest) : OpenVaultSubmission
}

/**
 * 生成型密钥文件的一次性交付状态（ISSUE-P3-21 验收 2；ISSUE-P3-400 批次自 ViewModel 同包迁入）。
 *
 * 勾选「生成附属密钥文件」建库时，密钥文件是复合密钥的第二因子：**丢失即永久无法解锁**，
 * 且它不会再次被生成（会话锁定后内存缓存即刻清零），故建库成功必须立即强制交付。
 * 本状态只承载**非密钥元数据**（建议文件名）；密钥文件字节始终留在数据层与 SAF 写入端，
 * 绝不进入 UiState / StateFlow / 日志。
 */
sealed interface KeyFileDeliveryState {

    /** 无待交付密钥文件 */
    data object None : KeyFileDeliveryState

    /** 库已按「主密码 + 密钥文件」建成，密钥文件尚未交付用户——必须显式保存或显式放弃 */
    data class PendingSave(val suggestedFileName: String) : KeyFileDeliveryState
}

/**
 * 云同步配置快照（ISSUE-P3-425）：新建向导「云端」位置的可用性与目标提示。
 *
 * 只承载**非敏感摘要**（Provider 种类 + 远端目标路径 / ObjectKey），不含任何凭据字节；
 * 由 ViewModel 自 `SyncCredentialsStore` 读取（密码解封后立即擦除，不入快照）。
 */
data class CloudSyncSnapshot(
    /** 配置读取是否已完成（false = 仍在读取，UI 显示读取中而非「未配置」） */
    val loaded: Boolean = false,
    /** 是否已配置可用的云同步账号（决定「云端」位置可选与否） */
    val ready: Boolean = false,
    /** 已配置的 Provider 种类（云端直建登记 syncType 与打开对话框来源共用同一枚举） */
    val kind: OpenVaultSourceType? = null,
    /** 远端目标提示（WebDAV = 远端路径；S3 = `bucket/objectKey`） */
    val targetHint: String = ""
) {
    companion object {
        /** 初始态：尚未读取配置 */
        val IDLE = CloudSyncSnapshot()
    }
}

/**
 * 已配置云账号的「打开已有库」预填载荷（ISSUE-P2-424 AC③）。
 *
 * 凭据 `CharArray` 为**借用语义的所有权移交**：ViewModel 自 `SyncCredentialsStore`
 * 解封后装入载荷，对话框把数组**原引用**写入表单快照态（其离场 `wipeSensitive` 统一擦除）
 * 并回调消费确认；ViewModel 侧随即弃持。任何路径下数组都恰好被擦一次，绝不落地 String。
 */
class OpenVaultWebDavPrefill(
    val url: String,
    val username: String,
    val passwordChars: CharArray,
    val remotePath: String
) {
    /** 未被对话框消费时的兜底擦除（关窗 / 换路） */
    fun wipe() {
        passwordChars.fill('0')
    }
}

/** S3 兼容账号的预填载荷（语义同 [OpenVaultWebDavPrefill]） */
class OpenVaultS3Prefill(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKeyChars: CharArray,
    val secretKeyChars: CharArray,
    val objectKey: String,
    val usePathStyle: Boolean
) {
    fun wipe() {
        accessKeyChars.fill('0')
        secretKeyChars.fill('0')
    }
}

/** 打开对话框的预填包：上次使用的来源 + 已配置云账号（均可为空） */
class OpenVaultPrefill(
    val lastSource: OpenVaultSourceType,
    val webdav: OpenVaultWebDavPrefill?,
    val s3: OpenVaultS3Prefill?
) {
    fun wipe() {
        webdav?.wipe()
        s3?.wipe()
    }
}

/**
 * 密码库选择与管理页面 UI 状态
 */
data class DatabasePickerUiState(
    val databases: List<VaultDatabaseInfo> = emptyList(),
    /**
     * ISSUE-P2-354 AC①：建库进行中。曾是「仅预览置值、无渲染点」的死字段，
     * 现由 `DatabasePickerViewModel.isCreatingFlow` 驱动：`DatabasePickerScreen`
     * 据此渲染进度并禁用入口，建库向导据此进入 busy（禁提交 / 禁关闭）。
     */
    val isLoading: Boolean = false,
    val showCreateDialog: Boolean = false,
    val showOpenSourceDialog: Boolean = false,
    /** ISSUE-P3-425：云同步配置快照（新建向导「云端」位置的可用性与目标提示） */
    val cloudSnapshot: CloudSyncSnapshot = CloudSyncSnapshot.IDLE,
    val userMessage: UiMessage? = null
)
