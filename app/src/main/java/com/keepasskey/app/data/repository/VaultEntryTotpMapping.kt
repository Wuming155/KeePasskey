package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.otp.Base32Decoder
import com.keepasskey.core.otp.OtpEngine
import com.keepasskey.core.otp.ParsedTotpConfig
import com.keepasskey.core.otp.TotpKeyUriParser

internal object VaultEntryTotpMapping {

    /**
     * 解析条目中的 TOTP 配置（标准 otp 字段优先，回退 TOTP 开头的自定义字段）。
     *
     * ISSUE-P2-12：种子读取走 [ProtectedString.readUtf8] 字节语义，解析层不物化
     * otpauth URI / 种子 String；[ProtectedString] 的明文副本用毕即擦。
     * 返回配置的 secret 为调用方独占的 Base32 文本字节，消费后须显式清零。
     */
    fun parseTotpConfig(entry: KdbxEntry): ParsedTotpConfig? {
        val source = entry.fields[KdbxConstants.Fields.OTP]
            ?: entry.customFields.firstOrNull {
                it.key.equals(KdbxConstants.Fields.OTP, ignoreCase = true) ||
                    it.key.startsWith(VaultEntryMapper.TOTP_CUSTOM_FIELD_PREFIX, ignoreCase = true)
            }?.value
            ?: return null
        val rawBytes = source.readUtf8()
        return try {
            TotpKeyUriParser.parse(rawBytes)
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
    fun projectTotpFields(entry: KdbxEntry): Projection {
        val parsedTotp = parseTotpConfig(entry)
        val period = parsedTotp?.period ?: DEFAULT_TOTP_PERIOD_SECONDS
        val code = try {
            parsedTotp?.let { computeTotpCode(it) }
        } finally {
            parsedTotp?.secret?.fill(0)
        }
        return Projection(
            period = period,
            digits = parsedTotp?.digits ?: DEFAULT_TOTP_DIGITS,
            algorithm = parsedTotp?.algorithm ?: DEFAULT_TOTP_ALGORITHM,
            isHotp = parsedTotp?.isHotp == true,
            code = code,
            remainingSeconds = OtpEngine.getRemainingSeconds(periodSeconds = period)
        )
    }

    /** TOTP 条目投影（参数 + 实时码 + 周期剩余秒数） */
    data class Projection(
        val period: Int,
        val digits: Int,
        val algorithm: String,
        val isHotp: Boolean,
        val code: String?,
        val remainingSeconds: Int
    )

    private const val DEFAULT_TOTP_PERIOD_SECONDS = 30
    private const val DEFAULT_TOTP_DIGITS = 6
    private const val DEFAULT_TOTP_ALGORITHM = "SHA1"
}
