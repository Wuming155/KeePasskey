package com.keepasskey.app.autofill

import android.app.assist.AssistStructure
import android.view.autofill.AutofillId
import com.keepasskey.core.log.AppLog

/**
 * 表单目标字段解析（§314 自 [KeePasskeyAutofillService] 原样拆出，规模闸门回压）：
 * 结构树扫描 + 角色 id 投影 + 字段级屏蔽判定。判定语义与拆分前逐字一致。
 *
 * ISSUE-P3-372 起在「结构树扫描」与「字段级屏蔽」之间插入三段**加性识别兜底**
 * （弱目标二次解析 → 聚焦字段补齐 → 跨请求记忆回补），三段均只影响「识别到哪些框」，
 * 屏蔽判定与后续放行闸门次序不变（回补目标同样过字段级屏蔽）。
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

    val respectImportantForAutofill = !settingsStore.isOverrideNoAutofillEnabled()
    var scanResult = AutofillFieldScanner.scan(
        scanned.scanNodes,
        respectImportantForAutofill = respectImportantForAutofill
    )
    // ISSUE-P3-372 AC①：首轮零登录目标时的弱目标二次解析（干草堆术语 + 登录上下文门）
    if (scanResult.usernameId == null && scanResult.passwordId == null) {
        scanResult = AutofillFieldFallback.runWeakReparse(
            nodes = scanned.scanNodes,
            respectImportantForAutofill = respectImportantForAutofill,
            pass1 = scanResult
        )
    }
    // ISSUE-P3-372 AC②：有密码框缺账号框时，从聚焦字段合成账号目标
    scanResult = AutofillFieldFallback.synthesizeFocusedUsername(
        result = scanResult,
        nodes = scanned.scanNodes,
        respectImportantForAutofill = respectImportantForAutofill
    )
    // ISSUE-P3-372 AC③：跨请求记忆回补（缺密码目标且同上下文记忆的全部键仍在结构中）
    scanResult = recoverLoginFieldsFromMemory(scanResult, parsedNodes, callingPkg)

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
    // ISSUE-P3-372 AC③：屏蔽判定通过后刷新记忆（只记「本次确实可用」的登录字段结构）
    rememberLoginFields(scanResult, parsedNodes, callingPkg)
    return TargetFields(
        usernameId = scannedUsernameId.takeIf { fieldDecision.allowUsername },
        passwordId = scannedPasswordId.takeIf { fieldDecision.allowPassword },
        otpId = scannedOtpId,
        scanResult = scanResult
    )
}

/**
 * ISSUE-P3-372 AC③ 回补半环：
 * 当前缺密码目标且存在登录上下文（账号目标健在）时，按「包名|域」召回记忆；
 * 记忆的全部键仍在当前结构中才整体回补（缺失侧各按可见性 / `importantForAutofill`
 * 复核），任一键失效则整体放弃（与 Monica「缓存 id 仍有效才回补」同口径）。
 */
private fun KeePasskeyAutofillService.recoverLoginFieldsFromMemory(
    scanResult: ScanResult,
    parsedNodes: List<ParsedAutofillNode>,
    callingPkg: String
): ScanResult {
    if (scanResult.passwordId != null) return scanResult
    if (scanResult.usernameId == null) return scanResult
    val contextKey = memoryContextKey(callingPkg, scanResult.webDomain)
    val remembered = loginFieldMemory.recall(contextKey) ?: return scanResult
    val keyToIndex = keyIndexMap(parsedNodes)
    val respectImportantForAutofill = !settingsStore.isOverrideNoAutofillEnabled()
    val recovery = AutofillLoginFieldRecovery.recover(
        remembered = remembered,
        availableKeys = keyToIndex.keys,
        hasCurrentUsername = true,
        hasCurrentPassword = false
    ) ?: return scanResult

    val usernameIndex = recovery.usernameKey
        ?.let { keyToIndex[it] }
        ?.takeIf { index ->
            parsedNodes.getOrNull(index)?.let {
                it.isVisible && (!respectImportantForAutofill || it.importantForAutofill)
            } == true
        }
    val passwordIndex = recovery.passwordKey
        ?.let { keyToIndex[it] }
        ?.takeIf { index ->
            parsedNodes.getOrNull(index)?.let { !respectImportantForAutofill || it.importantForAutofill } == true
        }
    if (usernameIndex == null && passwordIndex == null) return scanResult
    AppLog.d(KeePasskeyAutofillService.TAG, "登录字段从跨请求记忆回补")
    return scanResult.copy(
        usernameId = scanResult.usernameId ?: usernameIndex?.toString(),
        passwordId = passwordIndex?.toString() ?: scanResult.passwordId,
        passwordConfidence = passwordIndex?.let { FieldConfidence.MEDIUM }
            ?: scanResult.passwordConfidence
    )
}

/** ISSUE-P3-372 AC③ 记忆写入半环：有密码目标（任一识别路径产出）即刷新「包名|域」记忆 */
private fun KeePasskeyAutofillService.rememberLoginFields(
    scanResult: ScanResult,
    parsedNodes: List<ParsedAutofillNode>,
    callingPkg: String
) {
    if (scanResult.passwordId == null) return
    val keyToIndex = keyIndexMap(parsedNodes)
    val passwordKey = scanResult.passwordId.toIntOrNull()
        ?.let { parsedNodes.getOrNull(it)?.autofillId?.toString() }
        ?.takeIf { it in keyToIndex }
        ?: return
    val usernameKey = scanResult.usernameId?.toIntOrNull()
        ?.let { parsedNodes.getOrNull(it)?.autofillId?.toString() }
        ?.takeIf { it in keyToIndex }
    loginFieldMemory.remember(
        contextKey = memoryContextKey(callingPkg, scanResult.webDomain),
        usernameKey = usernameKey,
        passwordKey = passwordKey
    )
}

/** 记忆上下文键 = 调用包名 + 表单自报域（AC③「包名+域维度」）；域缺省为空段 */
private fun memoryContextKey(callingPkg: String, webDomain: String?): String =
    "$callingPkg|${webDomain?.trim().orEmpty()}"

/** 结构索引 ↔ AutofillId 字符串键映射（同结构内 toString 唯一性由系统保证） */
private fun keyIndexMap(parsedNodes: List<ParsedAutofillNode>): Map<String, Int> =
    parsedNodes.mapIndexedNotNull { index, node ->
        node.autofillId.toString()?.takeIf { it.isNotBlank() }?.let { it to index }
    }.toMap()
