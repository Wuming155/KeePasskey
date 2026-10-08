package com.keepasskey.app.autofill

import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.keepasskey.app.R
import com.keepasskey.app.autofill.KeePasskeyAutofillService.Companion.TAG
import com.keepasskey.core.log.AppLog
import com.keepasskey.core.model.KdbxEntry

/**
 * 结构化数据集装配（ISSUE-P3-375 AC②③，自 [AutofillDatasetBuilders] 按职责拆出——
 * 原文件随本批新增曾越入 tier1(>500) 行数闸门，拆分零行为变更）。
 *
 * 与登录候选装配共用 [UnlockedDatasetContext]（同包私有类型）与
 * [attachConfirmationAuth] 认证挂接；本文件只承载「卡 / 地址」结构化数据集一条支线。
 */
/**
 * ISSUE-P3-375 AC②③：按「全部所需字段齐备」供给结构化数据集。
 *
 * 与登录候选的域匹配**正交**（卡 / 地址是跨站点上下文，见 `StructuredFieldPolicy` KDoc）；
 * 每个数据集**恒挂确认页认证**（不参与会话授权跳过——结构化字段的敏感度不低于口令，
 * 每次交付都要过「归属展示 + 生物识别 / 手动确认」）。
 */
internal suspend fun KeePasskeyAutofillService.appendStructuredDatasets(
    responseBuilder: FillResponse.Builder,
    ctx: UnlockedDatasetContext,
    structuredTargetIds: Map<StructuredFieldRole, AutofillId>
) {
    if (structuredTargetIds.isEmpty()) return
    val entries = vaultRepository.getUsableKdbxEntries()
    val candidates = StructuredFieldPolicy.selectCandidates(entries, structuredTargetIds.keys)
    // 只记数量（调试留痕与登录候选同口径）
    AppLog.d(TAG, "结构化候选数据集数量=${candidates.size}")
    for (entry in candidates) {
        buildStructuredDataset(ctx, entry, structuredTargetIds)?.let { responseBuilder.addDataset(it) }
    }
}

/**
 * 单条结构化候选 → 数据集：字段值 + 恒挂确认页认证（绝不走免确认路径）。
 *
 * `ISSUE-P2-539`：**返回 null 表示该候选已被并发擦除**，调用方须跳过——结构化字段分属多个
 * 自定义字段，条目在「检索 → 下发」窗口内被就地擦除时只会读到部分字段，写出**残缺的卡 / 地址**
 * （比整条失败更糟）。判据与登录候选同一出口：`hasClearedFields()`（现已覆盖 `customFields`）。
 */
private fun KeePasskeyAutofillService.buildStructuredDataset(
    ctx: UnlockedDatasetContext,
    entry: KdbxEntry,
    structuredTargetIds: Map<StructuredFieldRole, AutofillId>
): Dataset? {
    if (entry.hasClearedFields()) {
        AppLog.w(TAG, "结构化候选条目在检索与下发之间已被擦除，本轮跳过该候选（不交付半份）")
        return null
    }
    // 展示面只出条目标题 + 固定副标题（不暴露字段值）
    val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
        // ISSUE-P2-534：结构化数据集菜单是**展示面**，与会话整树替换的就地擦除并发 ⇒ 走展示面读口
        setTextViewText(R.id.tv_username, entry.displayTitle())
        setTextViewText(R.id.tv_subtitle, getString(R.string.structured_dataset_subtitle))
    }
    val dsBuilder = Dataset.Builder(
        Presentations.Builder()
            .setMenuPresentation(views)
            .setDialogPresentation(views)
            .build()
    )
    // 只写非空值；值此刻仅进入 Dataset（带认证，框架在认证完成前不写入表单）
    for ((role, autofillId) in structuredTargetIds) {
        val value = StructuredFieldPolicy.valuesFor(entry, setOf(role))[role]
        if (!value.isNullOrEmpty()) {
            dsBuilder.setField(autofillId, Field.Builder().setValue(AutofillValue.forText(value)).build())
        }
    }
    // 恒挂认证：结构化字段每次交付强制确认（调用方以 ctx 表达，skip 分支不适用于本路径）
    attachConfirmationAuth(dsBuilder, entry.id.toHexString(), entry.displayTitle(), ctx)
    return dsBuilder.build()
}
