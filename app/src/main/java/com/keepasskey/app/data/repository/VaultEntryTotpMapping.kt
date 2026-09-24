package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.otp.Base32Decoder
import com.keepasskey.core.otp.OtpEngine
import com.keepasskey.core.otp.ParsedTotpConfig
import com.keepasskey.core.otp.TotpKeyUriParser
import com.keepasskey.core.security.ProtectedString

internal object VaultEntryTotpMapping {

    /**
     * 定位条目 TOTP 配置所在的字段（**ISSUE-P3-273** 单一真相源；解析、编辑页回填、
     * 修订快照三处共用本函数，禁各自再写一份优先序）。
     *
     * 优先序（**设置值优先、官方键回退**，三者缺一不可）：
     * ① 用户设置的两个字段名（种子字段名 → 设置字段名；`fields` 与 `customFields` 两处查找，
     *    忽略大小写）——用户显式配置即代表其库的真实结构；
     * ② 官方标准 `otp` 字段（KeePass / KeePassXC 通用口径）；
     * ③ `TOTP` 前缀自定义字段（KeePass2Android 系插件口径，含与 `otp` 同名的自定义字段）。
     *
     * ②③ 是官方 / 第三方兼容性底线，**不得**因 ① 存在而移除。
     */
    fun locateConfigSource(
        entry: KdbxEntry,
        preferences: TotpPreferences = TotpPreferences.DEFAULT
    ): ProtectedString? {
        listOf(preferences.seedFieldName, preferences.settingsFieldName)
            .filter { it.isNotBlank() }
            .forEach { name -> lookupByName(entry, name)?.let { return it } }
        entry.fields[KdbxConstants.Fields.OTP]?.let { return it }
        return entry.customFields.firstOrNull {
            it.key.equals(KdbxConstants.Fields.OTP, ignoreCase = true) ||
                it.key.startsWith(VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX, ignoreCase = true)
        }?.value
    }

    /** 按字段名在标准字段与自定义字段两处查找（`customFields` 走忽略大小写匹配）。 */
    private fun lookupByName(entry: KdbxEntry, name: String): ProtectedString? =
        entry.fields[name]
            ?: entry.fields.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: entry.customFields.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * 解析条目中的 TOTP 配置（字段定位见 [locateConfigSource]）。
     *
     * ISSUE-P2-12：种子读取走 [ProtectedString.readUtf8] 字节语义，解析层不物化
     * otpauth URI / 种子 String；[ProtectedString] 的明文副本用毕即擦。
     * ISSUE-P3-273：[preferences] 的缺省刷新周期 / 位数作为条目未声明时的回落值传入
     * （钳制语义仍在 `TotpKeyUriParser` 内，不绕过）。
     * 返回配置的 secret 为调用方独占的 Base32 文本字节，消费后须显式清零。
     */
    fun parseTotpConfig(
        entry: KdbxEntry,
        preferences: TotpPreferences = TotpPreferences.DEFAULT
    ): ParsedTotpConfig? {
        val source = locateConfigSource(entry, preferences) ?: return null
        val rawBytes = source.readUtf8()
        return try {
            TotpKeyUriParser.parse(rawBytes, preferences.defaultStepSeconds, preferences.defaultDigits)
        } finally {
            rawBytes.fill(0)
        }
    }

    /**
     * 按配置即时计算 TOTP 验证码，配置非法或计算失败返回 null。
     *
     * @param timestampMillis TOTP 取值时刻（HOTP 忽略本参数——其码由持久化计数器决定）。
     *   默认取当前墙钟；**ISSUE-P2-90 要求调用方与验证码缓存的周期判定共用同一时刻**，
     *   故缓存路径显式传入，避免「用 A 时刻判定周期、用 B 时刻出码」的错配。
     */
    fun computeTotpCode(config: ParsedTotpConfig, timestampMillis: Long = System.currentTimeMillis()): String? {
        // TASK-46：种子经 Base32 解码为 ByteArray 后全程字节态参与计算（绝不还原为 String）；
        // 解码产物归本函数所有，无论成功 / 失败路径均在 finally 中显式擦除（fail-clean），
        // 不因早退残留种子副本（对齐 KdbxKeyFile / SyncCredentialsStore 借用语义）
        val secretBytes = try {
            Base32Decoder.decode(config.secret)
        } catch (_: Exception) {
            return null
        }
        return try {
            val algo = when (config.algorithm.uppercase()) {
                "SHA256" -> OtpEngine.HashAlgorithm.SHA256
                "SHA512" -> OtpEngine.HashAlgorithm.SHA512
                else -> OtpEngine.HashAlgorithm.SHA1
            }
            // ISSUE-P3-49：HOTP 按持久化计数器出码（本方法**不推进**计数器；
            // 推进只在用户显式取码时经仓库写回，见 RealVaultRepository.advanceEntryHotpCounter）
            if (config.isHotp) {
                OtpEngine.calculateHotp(
                    secretKey = secretBytes,
                    counter = config.counter,
                    digits = config.digits,
                    algorithm = algo
                )
            } else {
                OtpEngine.calculateTotp(
                    secretKey = secretBytes,
                    timestampMillis = timestampMillis,
                    periodSeconds = config.period,
                    digits = config.digits,
                    algorithm = algo
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            secretBytes.fill(0)
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
    /**
     * 解析标准 OTP 或自定义字段中的 TOTP 配置并出码（列表投影用）。
     *
     * F2 整改：TOTP 种子不进 UiVaultEntry，列表展示用验证码在此即时计算；
     * 验证器页经 [RealVaultRepository.calculateEntryTotp] 按需重算。
     * ISSUE-P2-12：配置内的 Base32 种子字节用毕即擦（不再以 String 形态驻留）。
     */
    fun projectTotpFields(
        entry: KdbxEntry,
        preferences: TotpPreferences = TotpPreferences.DEFAULT
    ): Projection {
        val parsedTotp = parseTotpConfig(entry, preferences)
        // ISSUE-P3-273：缺省周期 / 位数取用户设置值（原为写死的 30 / 6）
        val period = parsedTotp?.period ?: preferences.defaultStepSeconds
        val code = try {
            parsedTotp?.let { computeTotpCode(it) }
        } finally {
            parsedTotp?.secret?.fill(0)
        }
        return Projection(
            period = period,
            digits = parsedTotp?.digits ?: preferences.defaultDigits,
            algorithm = parsedTotp?.algorithm ?: DEFAULT_TOTP_ALGORITHM,
            isHotp = parsedTotp?.isHotp == true,
            code = code,
            remainingSeconds = OtpEngine.getRemainingSeconds(periodSeconds = period),
            // ISSUE-P2-289 AC③：解析期回落诊断透传（禁静默改写）
            warnings = parsedTotp?.warnings.orEmpty()
        )
    }

    /** TOTP 条目投影（参数 + 实时码 + 周期剩余秒数 + 解析期诊断） */
    data class Projection(
        val period: Int,
        val digits: Int,
        val algorithm: String,
        val isHotp: Boolean,
        val code: String?,
        val remainingSeconds: Int,
        /** ISSUE-P2-289：解析期非致命诊断（digits / algorithm 回落），空列表 = 无回落 */
        val warnings: List<String> = emptyList()
    )

    private const val DEFAULT_TOTP_ALGORITHM = "SHA1"
}
