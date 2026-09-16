package com.keepasskey.app.ui.preview

import com.keepasskey.app.ui.model.BitmapEntryIcon
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.EntryDecorations
import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup

/**
 * IDE 预览（`@Preview`）专用示例数据。
 *
 * **性质声明**：本文件的全部内容都是**虚构的界面占位数据**，只服务于 Android Studio
 * 的 Compose Preview 渲染，不参与任何运行时业务流程（无 ViewModel / 无仓库 / 无 IO）。
 *
 * **安全边界（须严格遵守）**：
 * - 全部字段值均为明显的假数据（`预览…` / `demo@example.com` / 全 X 占位卡号）；
 *   严禁把真库条目、真实凭据、真实主密码或任何用户数据放进本文件。
 * - 受保护字段（TOTP 种子 / Passkey 私钥 / 恢复码）**一律不在此构造**——投影层本就
 *   不向 UI 下发其明文（见 `UiVaultEntry` KDoc 的 F2 整改说明），预览同样不得破例。
 * - 预览文案用中文，与项目「文档与代码注释使用简体中文」的约定一致。
 */

/** 预览用根分组：一个普通文件夹 */
val PreviewGroupLogins: VaultGroup = VaultGroup(
    id = "preview-group-logins",
    name = "网站登录",
    iconName = "folder",
    orderIndex = 0,
    updatedAt = "2026-01-01 10:00",
    createdAt = "2025-12-01 09:00"
)

/** 预览用分组：带自定义图标的文件夹（覆盖图标投影分支） */
val PreviewGroupCards: VaultGroup = VaultGroup(
    id = "preview-group-cards",
    name = "银行卡",
    iconName = "folder",
    orderIndex = 1,
    customIconId = "0123456789abcdef0123456789abcdef"
)

/** 预览用分组：回收站（覆盖回收站样式分支） */
val PreviewGroupRecycleBin: VaultGroup = VaultGroup(
    id = "preview-group-recycle",
    name = "回收站",
    iconName = "delete",
    orderIndex = 99,
    isRecycleBin = true
)

/** 预览用分组列表 */
val PreviewGroups: List<VaultGroup> = listOf(
    PreviewGroupLogins,
    PreviewGroupCards,
    PreviewGroupRecycleBin
)

/** 预览用登录条目（含 TOTP 与收藏标记） */
val PreviewEntryLogin: UiVaultEntry = UiVaultEntry(
    id = "preview-entry-login",
    title = "预览站点登录",
    username = "demo@example.com",
    url = "https://example.com",
    category = EntryCategory.LOGIN,
    isFavorite = true,
    totpCode = "123456",
    totpRemainingSeconds = 18,
    notes = "预览用备注文本",
    updatedAt = "2026-01-02 12:00",
    createdAt = "2025-12-01 09:30",
    groupId = PreviewGroupLogins.id,
    iconName = "key",
    customFields = listOf(
        UiCustomField(id = "preview-field-1", key = "预览自定义字段", value = "预览值"),
        UiCustomField(id = "preview-field-2", key = "预览受保护字段", value = "", isProtected = true)
    ),
    tags = listOf("预览标签")
)

/** 预览用通行密钥条目（含 Passkey 徽标分支） */
val PreviewEntryPasskey: UiVaultEntry = UiVaultEntry(
    id = "preview-entry-passkey",
    title = "预览站点通行密钥",
    username = "demo@example.com",
    url = "https://example.com",
    category = EntryCategory.PASSKEY,
    isPasskey = true,
    passkeyRpId = "example.com",
    groupId = PreviewGroupLogins.id,
    iconName = "passkey"
)

/** 预览用银行卡条目（含卡号 / 有效期 / CVV 渲染分支，全部为占位值） */
val PreviewEntryCard: UiVaultEntry = UiVaultEntry(
    id = "preview-entry-card",
    title = "预览银行卡",
    username = "预览持卡人",
    url = "",
    category = EntryCategory.CARD,
    groupId = PreviewGroupCards.id,
    iconName = "credit_card",
    cardNumberMasked = "XXXX XXXX XXXX 0000",
    cardHolder = "预览持卡人",
    cardExpiry = "12/34",
    cardCvv = "XXX"
)

/** 预览用安全便签条目 */
val PreviewEntryNote: UiVaultEntry = UiVaultEntry(
    id = "preview-entry-note",
    title = "预览安全便签",
    username = "",
    url = "",
    category = EntryCategory.NOTE,
    notes = "预览用便签正文，仅用于界面排版展示。",
    groupId = PreviewGroupLogins.id,
    iconName = "note"
)

/** 预览用条目列表（覆盖登录 / 通行密钥 / 银行卡 / 便签四类渲染分支） */
val PreviewEntries: List<UiVaultEntry> = listOf(
    PreviewEntryLogin,
    PreviewEntryPasskey,
    PreviewEntryCard,
    PreviewEntryNote
)

/** 预览用条目装饰投影：默认空装饰（不触发自定义图标解码与字段引用展开） */
val PreviewDecorations: EntryDecorations = EntryDecorations.EMPTY

/**
 * 预览用图标投影：未绑定自定义图标 → 标准矢量图标回退分支。
 * 纯内存构造，不读盘、不解码真实图标池（预览不得触发任何 IO）。
 */
val PreviewDefaultIcon: BitmapEntryIcon = EntryIcon.Default(iconName = "key")

/** 预览用图标投影：自定义图标缺失 → 缺图占位分支 */
val PreviewMissingIcon: BitmapEntryIcon = EntryIcon.Missing

/** 预览用附件条目（覆盖附件行渲染分支，不带真实字节） */
val PreviewAttachments: List<UiAttachment> = listOf(
    UiAttachment(
        id = "preview-attachment-1",
        fileName = "预览附件.txt",
        fileSizeFormatted = "1.21 KB",
        mimeType = "text/plain",
        addedAt = "2026-01-02 12:00"
    )
)

/** 预览用历史版本条目（覆盖历史快照列表渲染分支） */
val PreviewRevisions: List<UiEntryRevision> = listOf(
    UiEntryRevision(
        id = "preview-revision-1",
        modifiedAt = "2026-01-02 12:00",
        summary = "预览历史快照",
        username = "demo@example.com",
        notes = "预览用历史备注"
    )
)
