package com.keepasskey.app.data.repository

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.otp.HotpCounterSupport
import com.keepasskey.core.result.KdbxResult

/**
 * HOTP 计数器推进协调器（ISSUE-P3-305 自 `RealVaultRepository` 按职责拆出，逐行搬运）。
 *
 * 职责单一：兑现「先算码 → 计数器 +1 落库成功 → 才返回该码」这一**跨读写的原子序**——
 * 读侧取 OTP 配置原文（[VaultEntrySecretReader]），写侧推进计数器
 * （[VaultEntryWriteCoordinator.updateEntryOtpConfig]，不产生历史修订）。
 *
 * 之所以独立成器而非留在仓库门面：该操作横跨「读协调器 + 写协调器」，其顺序本身就是安全
 * 契约（详见 [advance] 的顺序声明），值得有单一归属，也避免仓库门面继续承担编排细节。
 */
internal class HotpAdvanceCoordinator(
    private val secretReader: VaultEntrySecretReader,
    private val entryWriter: VaultEntryWriteCoordinator,
    private val strings: StringsProvider
) {

    /**
     * ISSUE-P3-49：推进 HOTP 计数器并回传本次所出之码。
     *
     * 顺序严格为「先算码 → 计数器 +1 落库成功 → 返回该码」：任何一步失败都 fail-closed，
     * 绝不返回一个未推进的码（否则同一计数器会被重复使用）。计数器推进经
     * [VaultEntryWriteCoordinator.updateEntryOtpConfig]，**不产生历史修订**。
     */
    suspend fun advance(entryId: String): KdbxResult<EntryTotpSnapshot> {
        val snapshot = secretReader.calculateEntryTotp(entryId)
            ?: return KdbxResult.Failure(
                IllegalStateException("entry missing or no OTP configured"),
                strings.get(R.string.repo_hotp_not_applicable)
            )
        if (!snapshot.isHotp) {
            return KdbxResult.Failure(
                IllegalStateException("not an HOTP entry"),
                strings.get(R.string.repo_hotp_not_applicable)
            )
        }
        val raw = secretReader.getEntryTotpSecretChars(entryId)
            ?: return KdbxResult.Failure(
                IllegalStateException("HOTP config unreadable"),
                strings.get(R.string.repo_hotp_not_applicable)
            )
        val next = try {
            HotpCounterSupport.incrementCounter(raw)
        } finally {
            raw.fill('0')
        } ?: return KdbxResult.Failure(
            IllegalStateException("invalid HOTP counter"),
            strings.get(R.string.repo_hotp_not_applicable)
        )
        val writeResult = try {
            entryWriter.updateEntryOtpConfig(entryId, next)
        } finally {
            next.fill('0')
        }
        return when (writeResult) {
            is KdbxResult.Success -> KdbxResult.Success(snapshot)
            is KdbxResult.Failure -> KdbxResult.Failure(writeResult.error, writeResult.userMessage)
        }
    }
}
