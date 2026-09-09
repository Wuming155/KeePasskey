package com.keepasskey.app.passkey

import com.keepasskey.crypto.passkey.PasskeyCryptoEngine

/**
 * WebAuthn AuthenticatorData flags 组装策略（ISSUE-P0-03 / ZT-03）。
 *
 * 修复前 [PasskeyAssertionActivity] / [PasskeyCreateActivity] 无条件置位 `UP|UV|BE|BS`，
 * 与实际是否发生用户验证完全解耦——设备无可用强生物识别 / 锁屏凭据时，仍向依赖方 (RP)
 * 谎报 `UV=1`，RP 据此放宽风控（如免密支付、敏感操作放行），构成跨系统信任伪造。
 *
 * 依据 W3C WebAuthn Level 2 §6.1 Authenticator Data flags 语义：
 * - **UP（User Present）**：用户在场。只要在受保护窗口内完成了一次显式意图确认即置位；
 * - **UV（User Verified）**：**仅当本次实际发生了强用户验证**（系统级生物识别 /
 *   锁屏凭据认证成功）才可置位；手动点选确认只能证明「用户在场」，
 *   不足以支撑「用户已验证」声明，故投影为 `UV=0`（如实降级并交由 UI 告知）；
 * - **BE / BS**：凭据备份能力与状态（本实现凭据生成即视为可备份、已备份）；
 * - **AT**：仅注册（Create / Attested Credential Data）路径置位。
 *
 * 未发生任何验证、验证失败或取消一律返回 `null`（fail-closed，拒绝签发），
 * 杜绝「门控被绕过仍产出断言/注册」的路径。本对象刻意保持纯 Kotlin 无 Android 依赖，
 * 便于纯 JVM 单测直接断言两条关键分支：「无强验证 → UV=0」与「强验证通过 → UV=1」。
 */
internal object PasskeyAuthFlags {

    /** 构造断言（Get）路径的 flags；验证结果不可签发时返回 null */
    fun forAssertion(verification: CredentialUserVerification): Byte? =
        compose(verification, attested = false)

    /** 构造注册（Create）路径的 flags；验证结果不可签发时返回 null */
    fun forRegistration(verification: CredentialUserVerification): Byte? =
        compose(verification, attested = true)

    private fun compose(verification: CredentialUserVerification, attested: Boolean): Byte? {
        val userVerified = when (verification) {
            CredentialUserVerification.BiometricSucceeded -> true
            CredentialUserVerification.ManualConfirmed -> false
            // 未发生任何验证 / 认证失败 / 用户取消：不得产出任何签发材料
            else -> return null
        }

        var flags = PasskeyCryptoEngine.FLAG_UP.toInt() or
            PasskeyCryptoEngine.FLAG_BE.toInt() or
            PasskeyCryptoEngine.FLAG_BS.toInt()
        if (userVerified) {
            flags = flags or PasskeyCryptoEngine.FLAG_UV.toInt()
        }
        if (attested) {
            flags = flags or PasskeyCryptoEngine.FLAG_AT.toInt()
        }
        return flags.toByte()
    }
}
