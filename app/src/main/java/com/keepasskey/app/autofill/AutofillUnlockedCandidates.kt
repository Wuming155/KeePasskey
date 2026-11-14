package com.keepasskey.app.autofill

import android.view.autofill.AutofillId

/**
 * 已解锁分支的候选集与逐条二次确认策略（候选解析已收敛至 [AutofillCandidateResolver]）。
 * 数据集装配（值解析 / 呈现 / 认证挂接）留在 [AutofillDatasetBuilders]。
 */

/** 域名维度 + `android://` 包名维度双路打分后的截断结果；已解锁与解锁后两条链路共用。 */
data class UnlockedCandidates(
    val webDomain: String?,
    val ranked: List<AutofillCandidateRanker.Ranked>
)

/**
 * 已解锁分支的候选集解析入口：委托 [AutofillCandidateResolver] ——锁定态解锁后分支调用同一实例，
 * 两条链路的域归属校验、P2-46 首次绑定门、关联记忆、签名摘要快照与截断上限因此不可能漂移。
 *
 * `ENG-20261028-11`：返回的 [UnlockedCandidates.ranked] 携带**活的** `KdbxEntry`，
 * 调用方在后续装配（值解析 / TOTP 直填 / 结构化字段）中还要读它们的字段。因此读租约**必须由调用方**
 * 跨「解析 → 装配」整段持有（见 [appendUnlockedDatasets] 与解锁落地页的调用点）。
 */
internal suspend fun KeePasskeyAutofillService.resolveUnlockedCandidates(
    callingPkg: String,
    scanResult: ScanResult
): UnlockedCandidates = candidateResolver.resolve(callingPkg, scanResult.webDomain)

/**
 * TASK-11 整改（审核报告 P2-24）：已解锁分支的每个数据集必须携带 setAuthentication
 * 二次确认——否则任何前台应用都可静默拉起候选并完成明文密码填充（用户无感知泄露）。
 * 用户点选数据集 → 拉起 AutofillConfirmActivity（生物识别/锁屏凭据或受保护窗口内
 * 手动确认）→ RESULT_OK 后框架才将该数据集的值真正写入目标表单。
 *
 * ISSUE-P3-42：会话授权宽限（默认关闭）。本分支库已解锁；仅当开关开启且存在与
 * 「包名 + 域」严格匹配的有效授权（30 秒 TTL，由上次确认写入）时跳过重复二次确认。
 * 开关关闭时不查询授权存储，行为与既有「每次强制确认」完全一致。
 * ISSUE-P1-24 AC③：宽限**不得**作用于携带口令值的数据集——口令仅在显式确认后下发，
 * 用户名字段可例外（见 [AutofillAuthenticationPolicy.skipRepeatConfirmation]）。
 * ISSUE-P3-577：宽限同样**不得**作用于携带当前 TOTP 直填值的数据集——交付面「仅用户名 +
 * OTP 框」的数据集（无口令框）不挂 `setAuthentication` 时，框架会把当前验证码直填表单且
 * 无确认 UI。故敏感值判据（口令 **或** TOTP）由本函数同口径统计后交策略裁决。
 *
 * ISSUE-P3-528：关联记忆命中的条目**不**据此豁免——是否跳过二次确认仍完全由本函数裁决
 * （与会话授权开关同口径），记忆只影响「该条目是否成为候选」。
 */
internal suspend fun KeePasskeyAutofillService.unlockedConfirmationPolicy(
    callingPkg: String,
    candidates: UnlockedCandidates,
    passwordId: AutofillId?,
    otpId: AutofillId?
): Boolean {
    val sessionGrantEnabled = settingsStore.isAutofillSessionGrantEnabled()
    val grantActive = sessionGrantEnabled && AutofillSessionGrants.isGranted(
        AutofillGrantContext(callingPkg, candidates.webDomain)
    )
    return AutofillAuthenticationPolicy.skipRepeatConfirmation(
        sessionGrantEnabled = sessionGrantEnabled,
        vaultLocked = vaultRepository.isLocked(),
        grantActive = grantActive,
        datasetCarriesSensitiveValue = AutofillAuthenticationPolicy.carriesSensitiveValue(
            // 首个候选即代表本批数据集的口令承载面（同批候选口径一致：口令通道同为
            // passwordId + 非空口令才携带值），逐数据集计算无增量信息。
            // ISSUE-P2-539：本探测决定「能否跳过二次确认」，故**必须保守**——实例已清零
            // （正被并发擦除）时一律判「携带口令」⇒ 不跳过确认；绝不因擦除把它误判成
            // 「不携带」而放宽确认。判据落在 `cleared` 观测位（不物化明文）。
            hasDeliverablePassword = passwordId != null && candidates.ranked.any { ranked ->
                val password = ranked.entry.password
                password != null && (password.cleared || password.readStringForDisplay().isNotEmpty())
            },
            // ISSUE-P3-577：显式声明 OTP 框且任一候选将直填当前 TOTP 码时，同样判「携带敏感值」。
            // 快照判定与交付面共用 [AutofillAuthenticationPolicy.totpIsDeliverable]（非 HOTP 且
            // 码非空），杜绝两处口径漂移；未配 OTP / 计算失败（快照 null）一律不判携带——与交付面
            // `setField(otpId, …)` 的触发条件逐字一致，不作额外放宽。
            hasDeliverableTotp = otpId != null && candidates.ranked.any { ranked ->
                AutofillAuthenticationPolicy.totpIsDeliverable(
                    vaultRepository.calculateEntryTotp(ranked.entry.id.toHexString())
                )
            }
        )
    )
}
