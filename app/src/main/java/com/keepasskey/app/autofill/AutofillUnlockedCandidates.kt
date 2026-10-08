package com.keepasskey.app.autofill

import android.view.autofill.AutofillId
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.MAX_DATASET_COUNT
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.TAG
import com.keepasskey.core.log.AppLog

/**
 * 已解锁分支的**候选集解析**（ISSUE-P3-528 自 [AutofillDatasetBuilders] 按职责拆出——
 * 原文件随本批新增越入 tier1(>500) 行数闸门；拆分**零行为变更**，只做结构搬运）。
 *
 * 本文件承载「哪些条目可成为候选 + 该批候选是否需要逐条二次确认」，
 * 数据集装配（值解析 / 呈现 / 认证挂接）仍留在 [AutofillDatasetBuilders]。
 */

/** 已解锁分支的候选集：域名维度 + `android://` 包名维度双路打分后的截断结果 */
internal data class UnlockedCandidates(
    val webDomain: String?,
    val ranked: List<AutofillCandidateRanker.Ranked>
)

/**
 * 解析本轮可用候选（域归属校验 + 包名维度首次绑定校验 + 打分排序）。
 */
internal suspend fun KeePasskeyAutofillService.resolveUnlockedCandidates(
    callingPkg: String,
    scanResult: ScanResult
): UnlockedCandidates {
    // 库已解锁：查找匹配凭据
    // ISSUE-P2-07：webDomain 参与匹配前必须通过归属校验（受信浏览器白名单或 DAL 归属声明）；
    // 无法验证时按 null 处理（不下发该域候选），绝不静默放行
    // ISSUE-P3-170：调用方证书摘要**只读一次**并由下述两处共用（归属解析 + `android://` 维度
    // 首次绑定校验）——原实现各读一次，各含一次 `getPackageInfo` + 逐签名者 SHA-256 + 逐字节 hex 格式化；
    // 顺带消除「两次读取之间调用方身份变化」造成的判定不一致
    val callerCertDigests = autofillOriginResolver.callingAppCertDigests(callingPkg)
    val webDomain = autofillOriginResolver.resolveUsableWebDomain(callingPkg, scanResult.webDomain, callerCertDigests)
    if (scanResult.webDomain != null && webDomain == null) {
        AppLog.w(TAG, "webDomain 归属无法验证，已忽略该域候选（fail-closed）")
    }
    // ISSUE-P2-46：`android://` 包名维度必须先通过「调用方包名 + 签名摘要」首次绑定校验。
    // 未绑定 / 签名不可读时该维度一律不命中（fail-closed），条目仍可经域名维度入选；
    // 未绑定调用方的补救路径是选择器显式指认（该动作即首次绑定写入，见 AutofillPickerActivity）。
    val packageDimensionAuthorized = AndroidPackageBindingPolicy.isPackageDimensionAuthorized(
        callingPackage = callingPkg,
        callingCertDigests = callerCertDigests,
        isTrusted = { pkg, digests -> callerTrustStore.isTrusted(pkg, digests) }
    )
    if (!packageDimensionAuthorized && callingPkg.isNotBlank()) {
        // 日志不携带包名 / 摘要等调用方标识（ISSUE-P1-10 语义）
        AppLog.i(TAG, "android:// 维度未授权（未完成包名+签名首次绑定），本次不提供包名维度候选")
    }
    // ISSUE-P3-39：候选打分排序——严格匹配（DomainMatcher）通过的条目按
    // 「精确域名 > 精确包名 > 父域」打分并截断；匹配条件一字未放宽，
    // 未通过 isDomainMatch / isPackageMatch 的条目不会进入结果。
    // ISSUE-P3-528：调用方关联记忆（键＝包名 + 签名摘要，见 AutofillCallerEntryMemory）命中时，
    // 该条目**不经匹配**即入选并置首——准入依据是用户此前在该调用方上已发生的显式交付；
    // 这是唯一的非匹配准入来源，且记忆条目仍挂强制二次确认（见 attachConfirmationAuth）。
    val rememberedEntryId = autofillCallerEntryMemory.recall(callingPkg, callerCertDigests)
    if (rememberedEntryId != null) {
        // 只记事实（不含条目名 / 包名 / 摘要等标识）
        AppLog.d(TAG, "调用方关联记忆命中，本条候选含该条目")
    }
    return UnlockedCandidates(
        webDomain = webDomain,
        ranked = AutofillCandidateRanker.rank(
            // ISSUE-P2-341：候选装配走「可用条目」读口，回收站内的凭据不得被填出去
            entries = vaultRepository.getUsableKdbxEntries(),
            callingPackage = callingPkg,
            webDomain = webDomain,
            packageDimensionAuthorized = packageDimensionAuthorized,
            lastFilledEntryId = autofillLastFilledStore.lastFilledEntryId(),
            limit = MAX_DATASET_COUNT,
            // ISSUE-P3-372 AC④：调用方应用名作排序加成（取不到即 null，仅损失一个维度）
            callingAppLabel = callerAppLabelOrNull(callingPkg),
            // ISSUE-P3-373 AC①：Wi-Fi 设置上下文加成（清单外恒 false）
            wifiContext = WifiFillBoostPolicy.isWifiSettingsPackage(callingPkg),
            // ISSUE-P3-528：关联记忆命中条目（未命中传 null，对本条零影响）
            rememberedEntryId = rememberedEntryId
        )
    )
}

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
 *
 * ISSUE-P3-528：关联记忆命中的条目**不**据此豁免——是否跳过二次确认仍完全由本函数裁决
 * （与会话授权开关同口径），记忆只影响「该条目是否成为候选」。
 */
internal suspend fun KeePasskeyAutofillService.unlockedConfirmationPolicy(
    callingPkg: String,
    candidates: UnlockedCandidates,
    passwordId: AutofillId?
): Boolean {
    val sessionGrantEnabled = settingsStore.isAutofillSessionGrantEnabled()
    val grantActive = sessionGrantEnabled && AutofillSessionGrants.isGranted(
        AutofillGrantContext(callingPkg, candidates.webDomain)
    )
    return AutofillAuthenticationPolicy.skipRepeatConfirmation(
        sessionGrantEnabled = sessionGrantEnabled,
        vaultLocked = vaultRepository.isLocked(),
        grantActive = grantActive,
        // 首个候选即代表本批数据集的口令承载面（同批候选口径一致：口令通道同为
        // passwordId + 非空口令才携带值），逐数据集计算无增量信息。
        // ISSUE-P2-539：本探测决定「能否跳过二次确认」，故**必须保守**——实例已清零
        // （正被并发擦除）时一律判「携带口令」⇒ 不跳过确认；绝不因擦除把它误判成
        // 「不携带」而放宽确认。判据落在 `cleared` 观测位（不物化明文）。
        datasetCarriesPassword = passwordId != null && candidates.ranked.any { ranked ->
            val password = ranked.entry.password
            password != null && (password.cleared || password.readStringForDisplay().isNotEmpty())
        }
    )
}
