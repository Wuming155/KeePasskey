package com.keepasskey.app.autofill

import android.content.Context
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.MAX_DATASET_COUNT
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.TAG

/**
 * 自动填充候选集解析的**唯一事实源**：已解锁分支与锁定态解锁后分支都调用本类，
 * 避免两条链路各自拼装 [AutofillCandidateRanker.rank] 入参而漂移。
 *
 * 承载的既有闸门一字未放宽：域归属校验（自报域不可归属即按 null）、P2-46「包名 + 签名摘要」
 * 首次绑定门、回收站子树排除、调用方关联记忆的域隔离，以及 `AndroidApp*` 字段绑定所需的
 * **本次签名摘要快照**（不显式下传会退化为 `CallerCertDigests.EMPTY`，字段绑定一律不命中）。
 *
 * `ENG-20261028-11`：返回的 [UnlockedCandidates.ranked] 携带活的 `KdbxEntry`，调用方在后续
 * 装配中还要读取展示字段，故读租约必须由调用方跨「解析 → 装配」整段持有；本类不登记租约。
 */
class AutofillCandidateResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val autofillOriginResolver: AutofillOriginResolver,
    private val callerTrustStore: AutofillCallerTrustStore,
    private val autofillLastFilledStore: AutofillLastFilledStore,
    private val autofillCallerEntryMemory: AutofillCallerEntryMemory
) {
    /**
     * 解析本轮可用候选。
     *
     * @param callingPackage 系统背书的调用方包名
     * @param reportedWebDomain 表单自报域（未通过归属校验时按无域处理）
     */
    suspend fun resolve(callingPackage: String, reportedWebDomain: String?): UnlockedCandidates {
        // ISSUE-P3-170：调用方证书摘要只读一次，归属解析、`android://` 首次绑定校验与字段绑定
        // 的签名归一比对共用同一份快照（两次读取之间调用方身份变化会造成判定不一致）。
        val callerCertDigests = autofillOriginResolver.callingAppCertDigests(callingPackage)
        val webDomain = autofillOriginResolver.resolveUsableWebDomain(
            callingPackage,
            reportedWebDomain,
            callerCertDigests
        )
        if (reportedWebDomain != null && webDomain == null) {
            AppLog.w(TAG, "webDomain 归属无法验证，已忽略该域候选（fail-closed）")
        }
        // ISSUE-P2-46：`android://` 包名维度必须先通过「调用方包名 + 签名摘要」首次绑定校验。
        val packageDimensionAuthorized = AndroidPackageBindingPolicy.isPackageDimensionAuthorized(
            callingPackage = callingPackage,
            callingCertDigests = callerCertDigests,
            isTrusted = { pkg, digests -> callerTrustStore.isTrusted(pkg, digests) }
        )
        if (!packageDimensionAuthorized && callingPackage.isNotBlank()) {
            AppLog.i(TAG, "android:// 维度未授权（未完成包名+签名首次绑定），本次不提供包名维度候选")
        }
        // ISSUE-P3-528：召回键含包名 + 签名摘要 + 归属校验后域；浏览器上下文另有排序器第二道门。
        val rememberedEntryId = autofillCallerEntryMemory.recall(callingPackage, callerCertDigests, webDomain)
        if (rememberedEntryId != null) {
            AppLog.i(TAG, "调用方关联记忆命中，本条候选含该条目")
        }
        return UnlockedCandidates(
            webDomain = webDomain,
            ranked = AutofillCandidateRanker.rank(
                entries = vaultRepository.getUsableKdbxEntries(),
                callingPackage = callingPackage,
                webDomain = webDomain,
                packageDimensionAuthorized = packageDimensionAuthorized,
                lastFilledEntryId = autofillLastFilledStore.lastFilledEntryId(),
                limit = MAX_DATASET_COUNT,
                callingAppLabel = context.callerAppLabelOrNull(callingPackage),
                wifiContext = WifiFillBoostPolicy.isWifiSettingsPackage(callingPackage),
                rememberedEntryId = rememberedEntryId,
                callingCertDigests = callerCertDigests
            )
        )
    }
}
