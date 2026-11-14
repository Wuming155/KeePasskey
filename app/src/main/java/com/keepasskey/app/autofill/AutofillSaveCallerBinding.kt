package com.keepasskey.app.autofill

import com.keepasskey.core.log.AppLog

/**
 * 传统自动填充保存入库成功后的「调用方首次绑定」写入（与 CM 保存通道
 * `PasswordSaveActivity` 的 ISSUE-P2-83 口径一致）。
 *
 * 背景：`saveAutofillCredential` 落库的条目 URL 是 `android://<调用包名>`，而 `android://`
 * 候选维度必须先过 `AndroidPackageBindingPolicy` 的「包名 + 签名摘要」首次绑定门
 * （ISSUE-P2-46）。此前传统 Autofill 的两条保存路径（解锁态 `onSaveRequest` 与锁定态
 * [AutofillLockedSaveActivity]）只落库、不写绑定，用户同意保存后下次仍拿不到自动候选、
 * 必须再跳转手动搜索。
 *
 * 语义：系统保存提示是用户在该调用方表单内显式同意交付凭据的动作，`callingPackage`
 * 来自系统背书的结构树 / 保存句柄；仅在签名摘要可读时写入（fail-closed），不产生
 * 「仅包名」降级键，也不改动候选放行门本身。
 */
internal fun bindCallerAfterAutofillSave(
    callingPackage: String,
    originResolver: AutofillOriginResolver,
    trustStore: AutofillCallerTrustStore
) {
    val digests = originResolver.callingAppCertDigests(callingPackage)
    if (digests.isEmpty) {
        AppLog.w(KeePasskeyAutofillService.TAG, "调用方签名摘要不可读，保存后保持未绑定（fail-closed）")
        return
    }
    val written = trustStore.trust(callingPackage, digests.primary)
    AppLog.i(KeePasskeyAutofillService.TAG, "保存入库成功后建立调用方绑定结果=$written")
}
