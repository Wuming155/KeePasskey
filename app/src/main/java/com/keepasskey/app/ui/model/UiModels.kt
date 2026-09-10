package com.keepasskey.app.ui.model

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * 界面展现层群组/文件夹实体（参考 KeePassDX Group 模型）
 */
data class VaultGroup(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val iconName: String = "folder",
    val orderIndex: Int = 0,
    val updatedAt: String = "",
    val createdAt: String = "",
    val isRecycleBin: Boolean = false,
    /**
     * ISSUE-P3-22：KDBX 分组 `CustomIconUUID` 的 hex 投影，
     * 沿用条目侧 [UiVaultEntry.customIconId] 的同一形态（见本文件 UiVaultEntry）。
     * null = 该分组未绑定自定义图标，渲染侧回退 [iconName] 标准矢量图标；
     * 非 null 但不在库内图标池中时按缺图占位呈现（不谎报为标准图标）。
     */
    val customIconId: String? = null
)

/**
 * 自定义字段数据实体（支持受密码保护掩码展示）。
 *
 * F2 整改（CWE-316）：受保护字段（isProtected=true，如 Passkey 私钥/TOTP 种子/恢复码）的
 * 明文**不随条目投影下发**——[value] 在仓库投影层对受保护字段恒为空串，仅在用户显式
 * 查看/编辑时经 VaultRepository.getEntryProtectedField 按需单条解密。
 */
data class UiCustomField(
    val id: String,
    val key: String,
    val value: String,
    val isProtected: Boolean = false,
    val isVisible: Boolean = false
)

/**
 * 条目嵌入附件实体
 */
data class UiAttachment(
    val id: String,
    val fileName: String,
    val fileSizeFormatted: String,
    val mimeType: String = "application/octet-stream",
    val addedAt: String = "",
    /**
     * H4-附件整改：仅「本次编辑会话中新添加」的附件在内存中携带真实字节（data 非空），
     * 已落库附件恒为 null（按 refIndex 从库二进制池解析）。绝不持久化、不写日志。
     */
    val data: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean =
        other is UiAttachment && id == other.id && fileName == other.fileName &&
                fileSizeFormatted == other.fileSizeFormatted && mimeType == other.mimeType &&
                addedAt == other.addedAt && (data contentEquals other.data)

    override fun hashCode(): Int = listOf(id, fileName, fileSizeFormatted, mimeType, addedAt).hashCode() * 31 +
            (data?.contentHashCode() ?: 0)
}

/**
 * 条目历史修改快照版本。
 * M1 整改：不再携带密码明文（passwordPlain），历史密码须经仓库按需单条解密。
 */
data class UiEntryRevision(
    val id: String,
    val modifiedAt: String,
    val summary: String,
    val username: String,
    val notes: String = ""
)

/**
 * 密码库数据库信息实体（支持多库管理与切换）
 */
data class VaultDatabaseInfo(
    val id: String,
    val name: String,
    val path: String,
    val isRemote: Boolean = false,
    val syncType: String = "WebDAV",
    val lastOpenedAt: String = "",
    val fileSizeFormatted: String = "",
    val isActive: Boolean = false,
    val encryptionPreset: String = "ChaCha20 + Argon2id"
)

/**
 * 界面预览与交互使用的凭据数据实体。
 *
 * M1 整改（CWE-316）：不再携带密码明文（passwordPlain）——列表/详情快照整体驻留 StateFlow，
 * 明文驻留会使堆转储可读全库密码；密码仅在用户显式查看/复制时经
 * [com.keepasskey.app.data.repository.VaultRepository.getEntryPassword] 按需单条解密。
 * F2 整改：同样不再携带 TOTP 种子（totpSecret），验证码经仓库 calculateEntryTotp 按需即时计算，
 * 受保护自定义字段明文亦不在投影层下发。
 */
data class UiVaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val passwordMasked: String = "••••••••••••••••",
    val url: String,
    val isPasskey: Boolean = false,
    val passkeyRpId: String? = null,
    val totpCode: String? = null,
    val totpRemainingSeconds: Int = 30,
    val totpPeriod: Int = 30,
    val totpDigits: Int = 6,
    val totpAlgorithm: String = "SHA1",
    val category: EntryCategory = EntryCategory.LOGIN,
    // TASK-32 整改：恒硬编码 112 bit 撤销——null 表示「未计算」（投影层不解密密码，
    // 真实熵由详情页 ViewModel 按需解密估算后经 EntryDetailUiState 下发）
    val strengthBits: Int? = null,
    // TASK-34 整改：收藏状态随条目投影下发（持久化于 KDBX 条目 customData，仓库层读写）
    val isFavorite: Boolean = false,
    val notes: String = "",
    val updatedAt: String = "",
    val createdAt: String = "",
    val orderIndex: Int = 0,
    val groupId: String? = null,
    val iconName: String = "key",
    // TASK-15：KDBX 自定义图标 UUID（hex）；null=未使用自定义图标（iconName 为标准图标回退）
    val customIconId: String? = null,
    val cardNumberMasked: String? = null,
    val cardHolder: String? = null,
    val cardExpiry: String? = null,
    val cardCvv: String? = null,
    val customFields: List<UiCustomField> = emptyList(),
    val attachments: List<UiAttachment> = emptyList(),
    val revisions: List<UiEntryRevision> = emptyList(),
    // KP2A 能力补齐：标签 / AutoType 序列 / Override URL 的 UI 编辑通道（H4-断点补齐）
    val tags: List<String> = emptyList(),
    val autoTypeSequence: String = "",
    val overrideUrl: String? = null
)

/**
 * 条目分类（P3-23：显示标签资源化为 [labelRes]，Compose 层经 stringResource 解析；
 * 原中文字面量 label 无任何调用方，直接替换不破坏既有调用）
 */
enum class EntryCategory(@StringRes val labelRes: Int) {
    ALL(R.string.cat_all),
    LOGIN(R.string.cat_login),
    PASSKEY(R.string.cat_passkey),
    CARD(R.string.cat_card),
    NOTE(R.string.cat_note)
}
