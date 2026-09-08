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
import com.keepasskey.core.otp.OtpEngine
import com.keepasskey.core.otp.ParsedTotpConfig
import com.keepasskey.core.otp.TotpKeyUriParser
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * KdbxEntry ↔ UiVaultEntry 双向映射器（TASK-21 拆分自 RealVaultRepository）。
 * 职责单一：投影映射、图标名↔KDBX 标准 iconId 反演、TOTP 配置解析与即时计算、
 * MIME 推断与 UUID 解析；不持有会话状态，可被仓库与协调器复用。
 */
internal class VaultEntryMapper(private val strings: StringsProvider) {

    /**
     * KDBX 条目 → UI 投影。
     * M1 整改：不再将密码明文读入 UI 投影（全库明文驻留 StateFlow / 堆内存），
     * 密码仅在用户显式查看/复制时经 [RealVaultRepository.getEntryPassword] 按需单条解密
     * F2 整改：受保护自定义字段（Passkey 私钥/TOTP 种子/恢复码等）同样不进投影，
     * 仅在用户显式查看/编辑时按需单条解密
     */
    fun mapKdbxEntryToUi(entry: KdbxEntry, db: KdbxDatabase?): UiVaultEntry {
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

        val binaryPool = db?.binaries?.map { it.data } ?: emptyList()
        val uiAttachments = entry.attachments.map { att ->
            val dataBytes = att.resolveData(binaryPool)
            val sizeKb = (dataBytes.size / 1024).coerceAtLeast(if (dataBytes.isNotEmpty()) 1 else 0)
            val sizeFormatted = if (dataBytes.size < 1024) "${dataBytes.size} B" else "$sizeKb KB"
            UiAttachment(
                id = "${entry.id.toHexString()}_${att.name}",
                fileName = att.name,
                fileSizeFormatted = sizeFormatted,
                mimeType = determineMimeType(att.name),
                addedAt = formatInstant(entry.times.creationTime)
            )
        }

        // 解析标准 OTP 或自定义字段中的 TOTP 配置
        val parsedTotp = parseTotpConfig(entry)

        val totpPeriod = parsedTotp?.period ?: 30
        val totpDigits = parsedTotp?.digits ?: 6
        val totpAlgorithm = parsedTotp?.algorithm ?: "SHA1"

        val currentRemaining = OtpEngine.getRemainingSeconds(periodSeconds = totpPeriod)
        // F2 整改：TOTP 种子不进 UiVaultEntry（种子 String 仅在本函数内瞬时存在，随 GC 回收），
        // 列表展示用验证码在此即时计算；验证器页经 [RealVaultRepository.calculateEntryTotp] 按需重算
        val liveTotpCode = parsedTotp?.let { computeTotpCode(it) }

        val passkeyData = PasskeyData.fromCustomFields(entry.customFields)
        val icon = mapIconIdToName(entry.iconId)

        // TASK-35 整改：识别银行卡条目并映射卡面字段——模板「信用卡」以自定义字段
        // 存放卡信息（卡号/持卡人/有效期/CVV），此前一律按普通登录展示。
        // 仅读取未加保护字段进投影（受保护字段不物化明文，F2 语义不变）；
        // 受保护的卡号/CVV 保持 null，由 UI 渲染整卡掩码兜底。
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
        val cardHolderValue = unprotectedValue(CARD_FIELD_HOLDER_ZH, CARD_FIELD_HOLDER_EN)
        val cardExpiryValue = unprotectedValue(CARD_FIELD_EXPIRY_ZH, CARD_FIELD_EXPIRY_EN, CARD_FIELD_EXPIRY_EN_ALT)
        val isCardEntry = cardNumber != null || cardHolderValue != null ||
                cardExpiryValue != null || entry.iconId == 27
        val cardNumberMasked = cardNumber?.let { raw ->
            if (raw.length >= 4) "•••• •••• •••• ${raw.takeLast(4)}" else "••••"
        }

        return UiVaultEntry(
            id = entry.id.toHexString(),
            title = entry.title,
            username = entry.userName,
            passwordMasked = if (entry.password == null) "" else "••••••••••••••••",
            url = entry.url,
            isPasskey = passkeyData != null,
            passkeyRpId = passkeyData?.relyingPartyId,
            totpCode = liveTotpCode,
            totpRemainingSeconds = currentRemaining,
            totpPeriod = totpPeriod,
            totpDigits = totpDigits,
            totpAlgorithm = totpAlgorithm,
            category = if (isCardEntry) EntryCategory.CARD else EntryCategory.LOGIN,
            isFavorite = entry.customData[RealVaultRepository.FAVORITE_CUSTOM_DATA_KEY] == "true",
            notes = entry.notes,
            groupId = entry.parentGroupId?.toHexString(),
            iconName = icon,
            updatedAt = formatInstant(entry.times.lastModificationTime),
            createdAt = formatInstant(entry.times.creationTime),
            cardNumberMasked = cardNumberMasked,
            cardHolder = cardHolderValue,
            cardExpiry = cardExpiryValue,
            customFields = uiCustomFields,
            attachments = uiAttachments,
            revisions = uiRevisions,
            tags = entry.tags,
            autoTypeSequence = entry.autoType?.defaultSequence.orEmpty(),
            overrideUrl = entry.overrideUrl
        )
    }

    /** 解析条目中的 TOTP 配置（标准 otp 字段优先，回退 TOTP 开头的自定义字段） */
    fun parseTotpConfig(entry: KdbxEntry): ParsedTotpConfig? {
        val otpRaw = entry.fields["otp"]?.readString()
            ?: entry.customFields.firstOrNull {
                it.key.equals("otp", ignoreCase = true) || it.key.startsWith("TOTP", ignoreCase = true)
            }?.value?.readString()
        return TotpKeyUriParser.parse(otpRaw)
    }

    /** 按配置即时计算 TOTP 验证码，配置非法或计算失败返回 null */
    fun computeTotpCode(config: ParsedTotpConfig): String? {
        return try {
            val algo = when (config.algorithm.uppercase()) {
                "SHA256" -> OtpEngine.HashAlgorithm.SHA256
                "SHA512" -> OtpEngine.HashAlgorithm.SHA512
                else -> OtpEngine.HashAlgorithm.SHA1
            }
            OtpEngine.calculateTotp(
                secretKeyBase32 = config.secret,
                periodSeconds = config.period,
                digits = config.digits,
                algorithm = algo
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * KDBX 标准图标 ID → UI 图标名。
     * ID 以官方 KeePass PwIcon 枚举为准（格式层裁决者）：0=Key 1=World 3=NetworkServer 5=UserCommunication
     * 7=Notepad 13=MultiKeys 19=EMail 20=Configuration 26=Disk 29=TerminalEncrypted 30=Console
     * 32=ProgramIcons 35=WorldComputer 37=Homebanking 43=TrashBin 44=Note 48=Folder 51=LockOpen
     * 52=PaperLocked 58=UserKey 66=Money 67=Certificate 68=BlackBerry。
     * 原实现把 2(Warning) 误译为 email，本轮已纠正。
     */
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
                fields[KdbxConstants.Fields.OTP] = ProtectedString(trimmedTotp, isProtected = false)
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
            fields = fields,
            customFields = customFields,
            tags = entry.tags,
            attachments = attachments,
            autoType = mergeAutoType(null, entry.autoTypeSequence),
            overrideUrl = entry.overrideUrl?.takeIf { it.isNotBlank() }
        )
    }

    fun formatInstant(instant: Instant): String {
        val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
        return dtf.format(instant.atZone(ZoneId.systemDefault()))
    }

    companion object {
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
