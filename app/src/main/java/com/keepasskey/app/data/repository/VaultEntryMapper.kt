package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.EntryCategory
import com.keepasskey.app.ui.model.UiAttachment
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiEntryRevision
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.otp.ParsedTotpConfig
import com.keepasskey.core.security.ProtectedString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * KdbxEntry ↔ UiVaultEntry 双向映射器（TASK-21 拆分自 RealVaultRepository）。
 * 职责单一：投影映射、图标名↔KDBX 标准 iconId 反演、TOTP 配置解析与即时计算、
 * MIME 推断与 UUID 解析；不持有会话状态，可被仓库与协调器复用。
 */
internal class VaultEntryMapper(
    private val strings: StringsProvider,
    /**
     * TOTP 解析参数读取通道（ISSUE-P3-273）：字段名映射与默认步长 / 位数。
     * 缺省为 [TotpPreferences.DEFAULT]（纯 JVM 单测未注入时的回落，与「用户从未改动 TOTP 设置」同义）。
     */
    private val totpPreferences: () -> TotpPreferences = { TotpPreferences.DEFAULT }
) {

    /**
     * KDBX 条目 → UI 投影。
     * M1 整改：不再将密码明文读入 UI 投影（全库明文驻留 StateFlow / 堆内存），
     * 密码仅在用户显式查看/复制时经 [RealVaultRepository.getEntryPassword] 按需单条解密
     * F2 整改：受保护自定义字段（Passkey 私钥/TOTP 种子/恢复码等）同样不进投影，
     * 仅在用户显式查看/编辑时按需单条解密
     */
    fun mapKdbxEntryToUi(entry: KdbxEntry): UiVaultEntry {
        val uiCustomFields = entry.customFields.map { cf ->
            UiCustomField(
                id = "${entry.id.toHexString()}_${cf.key}",
                key = cf.key,
                value = if (cf.isProtected) "" else cf.value.readString(),
                isProtected = cf.isProtected
            )
        }

        val uiRevisions = entry.history.map { h ->
            UiEntryRevision(
                id = h.id.toHexString(),
                modifiedAt = formatInstant(h.times.lastModificationTime),
                summary = strings.get(R.string.repo_revision_summary),
                username = h.userName,
                notes = h.notes
            )
        }

        // ISSUE-P2-24：投影只需字节数，直接取 attachment.size——
        // 不再把整个二进制池 map 成字节数组（那会令落盘大附件被整批读回内存）。
        val uiAttachments = entry.attachments.map { att ->
            UiAttachment(
                id = "${entry.id.toHexString()}_${att.name}",
                fileName = att.name,
                fileSizeFormatted = formatAttachmentSize(att.size),
                mimeType = determineMimeType(att.name),
                addedAt = formatInstant(entry.times.creationTime)
            )
        }

        val totp = projectTotpFields(entry)

        val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
        val icon = mapIconIdToName(entry.iconId)

        val card = projectCardFields(entry)

        return UiVaultEntry(
            id = entry.id.toHexString(),
            title = entry.title,
            username = entry.userName,
            passwordMasked = if (entry.password == null) "" else PASSWORD_MASK,
            url = entry.url,
            isPasskey = passkeyData != null,
            passkeyRpId = passkeyData?.relyingPartyId,
            totpCode = totp.code,
            totpRemainingSeconds = totp.remainingSeconds,
            totpPeriod = totp.period,
            totpDigits = totp.digits,
            totpAlgorithm = totp.algorithm,
            // ISSUE-P2-289 AC③：解析期回落诊断透传（禁静默改写）
            totpWarnings = totp.warnings,
            isHotp = totp.isHotp,
            category = if (card.isCardEntry) EntryCategory.CARD else EntryCategory.LOGIN,
            isFavorite = entry.customData[RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY] == "true",
            notes = entry.notes,
            groupId = entry.parentGroupId?.toHexString(),
            iconName = icon,
            customIconId = entry.customIconId?.toHexString(),
            updatedAt = formatInstant(entry.times.lastModificationTime),
            createdAt = formatInstant(entry.times.creationTime),
            cardNumberMasked = card.cardNumberMasked,
            cardHolder = card.cardHolder,
            cardExpiry = card.cardExpiry,
            customFields = uiCustomFields,
            attachments = uiAttachments,
            revisions = uiRevisions,
            tags = entry.tags,
            autoTypeSequence = entry.autoType?.defaultSequence.orEmpty(),
            overrideUrl = entry.overrideUrl
        )
    }

    /** 附件大小展示：不足 1 KiB 以字节计，否则折算 KiB（向上至少 1）。 */
    private fun formatAttachmentSize(byteCount: Long): String =
        if (byteCount < BYTES_PER_KIB) "$byteCount B"
        else "${(byteCount / BYTES_PER_KIB).coerceAtLeast(1L)} KB"

    /**
     * TASK-35 整改：识别银行卡条目并映射卡面字段——模板「信用卡」以自定义字段
     * 存放卡信息（卡号/持卡人/有效期/CVV），此前一律按普通登录展示。
     * 仅读取未加保护字段进投影（受保护字段不物化明文，F2 语义不变）；
     * 受保护的卡号/CVV 保持 null，由 UI 渲染整卡掩码兜底。
     */
    private fun projectCardFields(entry: KdbxEntry): CardProjection {
        val cfByKey = entry.customFields.associateBy { it.key }
        fun unprotectedValue(vararg keys: String): String? {
            for (key in keys) {
                val field = cfByKey[key] ?: continue
                if (!field.isProtected) {
                    val raw = field.value.readString()
                    if (raw.isNotBlank()) return raw
                }
            }
            return null
        }
        val cardNumber = unprotectedValue(CARD_FIELD_NUMBER_ZH, CARD_FIELD_NUMBER_EN)
        val cardHolder = unprotectedValue(CARD_FIELD_HOLDER_ZH, CARD_FIELD_HOLDER_EN)
        val cardExpiry =
            unprotectedValue(CARD_FIELD_EXPIRY_ZH, CARD_FIELD_EXPIRY_EN, CARD_FIELD_EXPIRY_EN_ALT)
        val isCardEntry = cardNumber != null || cardHolder != null ||
            cardExpiry != null || entry.iconId == CARD_ICON_ID
        val cardNumberMasked = cardNumber?.let { raw ->
            if (raw.length >= CARD_LAST_VISIBLE_DIGITS) {
                "$CARD_SHORT_MASK ${raw.takeLast(CARD_LAST_VISIBLE_DIGITS)}"
            } else {
                CARD_FULL_MASK
            }
        }
        return CardProjection(isCardEntry, cardNumberMasked, cardHolder, cardExpiry)
    }

    /** 银行卡条目投影（分类判定 + 三个展示字段） */
    private data class CardProjection(
        val isCardEntry: Boolean,
        val cardNumberMasked: String?,
        val cardHolder: String?,
        val cardExpiry: String?
    )

    /** TOTP 面（**§163 下沉**至 [VaultEntryTotpMapping]：三者是无状态纯函数，不读仓库与会话状态；
     * 种子与解码产物的 `finally` 擦除语义随代码同迁，未放宽）。
     * **ISSUE-P3-273**：解析参数（字段名 / 默认步长 / 位数）经构造注入的通道现读，
     * 不再是「写死 30 / 6 且与设置无关」。 */
    fun parseTotpConfig(
        entry: KdbxEntry,
        preferences: TotpPreferences = totpPreferences()
    ): ParsedTotpConfig? = VaultEntryTotpMapping.parseTotpConfig(entry, preferences)

    fun computeTotpCode(config: ParsedTotpConfig, timestampMillis: Long = System.currentTimeMillis()): String? =
        VaultEntryTotpMapping.computeTotpCode(config, timestampMillis)

    private fun projectTotpFields(entry: KdbxEntry): VaultEntryTotpMapping.Projection =
        VaultEntryTotpMapping.projectTotpFields(entry, totpPreferences())

    fun mapIconIdToName(iconId: Int): String {
        return when (iconId) {
            0, 58 -> "key"
            1, 8, 16 -> "public"
            3 -> "wifi"
            5 -> "forum"
            4, 27, 47, 48, 49, 50 -> "folder"
            7, 22, 41, 44 -> "description"
            13 -> "vpn_key"
            19, 25, 40 -> "email"
            20, 34 -> "dns"
            26, 36 -> "database"
            29, 30, 33 -> "terminal"
            32 -> "code"
            35 -> "cloud"
            37, 66 -> "credit_card"
            43 -> "delete"
            51 -> "lock"
            52 -> "security"
            67 -> "work"
            68 -> "phone"
            else -> "key"
        }
    }

    /** UI 图标名 → KDBX 标准 iconId（与 [mapIconIdToName] 互为反演；未知名回退 [fallbackId]） */
    fun mapIconNameToId(iconName: String, fallbackId: Int): Int {
        return when (iconName) {
            "key" -> 0
            "public" -> 1
            "wifi" -> 3
            "forum" -> 5
            "folder" -> 48
            "description" -> 44
            "vpn_key" -> 13
            "email" -> 19
            "dns" -> 20
            "database" -> 26
            "terminal" -> 29
            "code" -> 32
            "cloud" -> 35
            "credit_card" -> 37
            "lock" -> 51
            "security" -> 52
            "work" -> 67
            "phone" -> 68
            "account_balance" -> 66
            "delete" -> RealVaultRepository.ICON_TRASH_BIN
            else -> fallbackId
        }
    }

    /**
     * 合并 AutoType 序列（KP2A 能力补齐）。
     * UI 仅编辑条目级默认序列；既有 associations/混淆配置原样保留；
     * 归约到全默认值时置 null，避免为空配置生成冗余节点。
     */
    fun mergeAutoType(existing: com.keepasskey.core.model.KdbxAutoType?, uiSequence: String): com.keepasskey.core.model.KdbxAutoType? {
        val base = existing ?: return if (uiSequence.isBlank()) null else com.keepasskey.core.model.KdbxAutoType(defaultSequence = uiSequence.trim())
        val newSequence = uiSequence.trim()
        if (newSequence == base.defaultSequence) return base
        val merged = base.copy(defaultSequence = newSequence)
        return if (merged.enabled && merged.defaultSequence.isEmpty() &&
            merged.dataTransferObfuscation == 0 && merged.associations.isEmpty()
        ) null else merged
    }

    fun determineMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    /** 新建条目路径：UI 投影 → KDBX 条目（附件携带真实字节，保存时经去重器入池） */
    fun mapUiEntryToKdbx(
        entry: UiVaultEntry,
        passwordChars: CharArray?,
        totpSecretChars: CharArray?,
        protectedFieldChars: Map<String, CharArray>
    ): KdbxEntry {
        val fields = mutableMapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(entry.title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(entry.username, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(passwordChars ?: CharArray(0), isProtected = true),
            KdbxConstants.Fields.URL to ProtectedString(entry.url, isProtected = false),
            KdbxConstants.Fields.NOTES to ProtectedString(entry.notes, isProtected = false)
        )
        // TASK-10：TOTP 配置以 CharArray 提交（空数组=无 TOTP），中间副本即时擦除
        if (totpSecretChars != null) {
            val trimmedTotp = totpSecretChars.trimmedCopy()
            if (trimmedTotp.isNotEmpty()) {
                fields[KdbxConstants.Fields.OTP] = ProtectedString(trimmedTotp, isProtected = true)
            }
            trimmedTotp.fill('0')
        }

        val customFields = entry.customFields.map { cf ->
            val submittedChars = if (cf.isProtected) protectedFieldChars[cf.id] else null
            KdbxCustomField(
                key = cf.key,
                value = when {
                    // TASK-10：用户显式编辑的受保护字段明文以 CharArray 提交（副本由 saveEntry finally 擦除）
                    submittedChars != null -> ProtectedString(submittedChars, isProtected = true)
                    else -> ProtectedString(cf.value, isProtected = cf.isProtected)
                }
            )
        }

        // 断点1-2 整改：新建路径同样消费 UI 附件（携带真实字节，保存时经去重器入池）
        val attachments = entry.attachments.mapNotNull { ui ->
            ui.data?.let { KdbxAttachment(name = ui.fileName, data = it, isProtected = false) }
        }

        return KdbxEntry(
            id = parseKdbxUuidOrRandom(entry.id),
            parentGroupId = entry.groupId?.let { parseKdbxUuidOrNull(it) },
            iconId = mapIconNameToId(entry.iconName, fallbackId = RealVaultRepository.ICON_KEY),
            customIconId = entry.customIconId?.let { parseKdbxUuidOrNull(it) },
            fields = fields,
            customFields = customFields,
            tags = entry.tags,
            attachments = attachments,
            autoType = mergeAutoType(null, entry.autoTypeSequence),
            overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * 时间戳 → 列表 / 详情页展示文案（`yyyy-MM-dd HH:mm`）。
     *
     * ISSUE-P3-149：格式化器**按 Locale 缓存复用**。原实现每次调用都
     * `DateTimeFormatter.ofPattern(...)` 重新编译模式串，而投影路径上每个条目固定调用 2 次
     * （更新时间 + 创建时间，历史修订与附件再各叠加），构成 O(N) 次纯浪费的模式编译。
     * `DateTimeFormatter` 不可变且线程安全，故可安全共享；以 Locale 为键是为了避免
     * 系统语言变更后仍沿用旧 Locale 的格式化器。
     */
    fun formatInstant(instant: Instant): String =
        formatterFor(Locale.getDefault()).format(instant.atZone(ZoneId.systemDefault()))

    private fun formatterFor(locale: Locale): DateTimeFormatter =
        INSTANT_FORMATTERS.computeIfAbsent(locale) { DateTimeFormatter.ofPattern(INSTANT_PATTERN, it) }

    companion object {
        /** 展示用时间格式（纯数值模式，跨 Locale 语义一致）。 */
        private const val INSTANT_PATTERN = "yyyy-MM-dd HH:mm"

        /** `Locale → 已编译格式化器`（进程级复用；键集合随系统语言数量有界） */
        private val INSTANT_FORMATTERS = java.util.concurrent.ConcurrentHashMap<Locale, DateTimeFormatter>()

        /**
         * 银行卡模板自定义字段键（与 [VaultTemplateFactory] 信用卡模板写入的键一致）。
         * 这些键作为 KDBX 条目数据持久化并跨端同步，属格式契约而非 UI 文案，禁止本地化。
         */
        const val CARD_FIELD_NUMBER_ZH = "卡号"
        const val CARD_FIELD_NUMBER_EN = "Card Number"
        const val CARD_FIELD_HOLDER_ZH = "持卡人"
        const val CARD_FIELD_HOLDER_EN = "Card Holder"
        const val CARD_FIELD_EXPIRY_ZH = "有效期"
        const val CARD_FIELD_EXPIRY_EN = "Expiry"
        const val CARD_FIELD_EXPIRY_EN_ALT = "Expiry Date"

        /** 回退读取的 TOTP 自定义字段前缀（KDBX 自定义字段键，属格式契约不可本地化） */
        const val TOTP_CUSTOM_FIELD_PREFIX = "TOTP"

        /** 模板「信用卡」条目的 KDBX 标准图标 id（无卡面字段时按图标识别） */
        private const val CARD_ICON_ID = 27

        /** 卡号展示保留的末位位数 */
        private const val CARD_LAST_VISIBLE_DIGITS = 4

        /** 卡号掩码前缀（末四位可见形态）与整卡掩码兜底 */
        private const val CARD_SHORT_MASK = "•••• •••• ••••"
        private const val CARD_FULL_MASK = "••••"

        /** 密码遮罩（长度即占位宽度，不反映真实长度） */
        private const val PASSWORD_MASK = "••••••••••••••••"

        /** 附件大小折算基数（1 KiB） */
        private const val BYTES_PER_KIB = 1024L

        /** TOTP 配置缺省参数（RFC 6238 默认值） */
        private const val DEFAULT_TOTP_PERIOD_SECONDS = 30
        private const val DEFAULT_TOTP_DIGITS = 6
        private const val DEFAULT_TOTP_ALGORITHM = "SHA1"
    }
}

/** 解析 KDBX UUID hex 字符串；非法输入返回 null（不抛异常） */
internal fun parseKdbxUuidOrNull(id: String): KdbxUuid? {
    return try {
        KdbxUuid.fromHexString(id)
    } catch (_: Exception) {
        null
    }
}

/** 解析 KDBX UUID hex 字符串；非法输入生成随机 UUID（新建条目/分组路径） */
internal fun parseKdbxUuidOrRandom(id: String): KdbxUuid {
    return parseKdbxUuidOrNull(id) ?: KdbxUuid.random()
}

/**
 * CharArray 去除首尾空白并返回新副本（TASK-10：TOTP 配置 CharArray 化的 trim 等价物）。
 * 原数组归调用方所有并按擦除契约清零；返回的新副本由调用点用毕立即擦除。
 */
internal fun CharArray.trimmedCopy(): CharArray {
    var start = 0
    var end = size
    while (start < end && this[start].isWhitespace()) start++
    while (end > start && this[end - 1].isWhitespace()) end--
    return copyOfRange(start, end)
}
