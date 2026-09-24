package com.keepasskey.app.autofill

import android.app.assist.AssistStructure
import android.view.autofill.AutofillId
import com.keepasskey.core.log.AppLog

/**
 * 表单目标字段解析（§314 自 [KeePasskeyAutofillService] 原样拆出，规模闸门回压）：
 * 结构树扫描 + 角色 id 投影 + 字段级屏蔽判定。判定语义与拆分前逐字一致。
 */

/** 表单目标框 + 字段级屏蔽后的可用 id；无可填充目标（或未通过屏蔽判定）时为 null */
internal data class TargetFields(
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    // ISSUE-P3-298 ⑤：显式声明的 OTP 框（直填当前 TOTP 值；不参与字段级屏蔽角色判定）
    val otpId: AutofillId?,
    val scanResult: ScanResult
)

internal fun KeePasskeyAutofillService.resolveTargetFields(
    structure: AssistStructure,
    callingPkg: String
): TargetFields? {
    val scanned = AutofillStructureScanner.scan(structure, callingPkg)
    val parsedNodes = scanned.viewNodes

    val scanResult = AutofillFieldScanner.scan(
        scanned.scanNodes,
        respectImportantForAutofill = !settingsStore.isOverrideNoAutofillEnabled()
    )
    val usernameParsed = scanResult.usernameId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }
    val passwordParsed = scanResult.passwordId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }
    val otpParsed = scanResult.otpId?.toIntOrNull()?.let { parsedNodes.getOrNull(it) }

    val scannedUsernameId: AutofillId? = usernameParsed?.autofillId
    val scannedPasswordId: AutofillId? = passwordParsed?.autofillId
    val scannedOtpId: AutofillId? = otpParsed?.autofillId
    if (scannedUsernameId == null && scannedPasswordId == null) return null

    // ISSUE-P3-43 ②：字段签名级屏蔽——判定先于「库锁定引导」与任何数据集构建，
    // 因此被屏蔽的框连解锁引导都不会收到（更保守）。签名的域取表单**自报**的
    // scanResult.webDomain（用户屏蔽的是他当时看到的那个表单），
    // 与后续用于凭据匹配的「归属校验后 webDomain」是两个独立用途，不可互替。
    val fieldDecision = AutofillFieldBlockPolicy.decide(
        hasUsernameField = scannedUsernameId != null,
        hasPasswordField = scannedPasswordId != null
    ) { role ->
        autofillFieldBlocklistStore.isBlocked(callingPkg, scanResult.webDomain, role)
    }
    if (fieldDecision.blocksEntireForm) {
        AppLog.i(KeePasskeyAutofillService.TAG, "本表单字段已被用户逐字段屏蔽，拒绝下发数据集")
        return null
    }
    return TargetFields(
        usernameId = scannedUsernameId.takeIf { fieldDecision.allowUsername },
        passwordId = scannedPasswordId.takeIf { fieldDecision.allowPassword },
        otpId = scannedOtpId,
        scanResult = scanResult
    )
}
