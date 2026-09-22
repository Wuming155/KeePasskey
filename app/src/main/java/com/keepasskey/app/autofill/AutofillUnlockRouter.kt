package com.keepasskey.app.autofill

import com.keepasskey.core.model.KdbxEntry

/**
 * 库锁定填充「解锁成功后链去哪」的路由判据（PD-05 候选 C，ISSUE-P3-256；纯函数，JVM 可单测）。
 *
 * 先例：`VaultRemovalKind.of(...)`（ISSUE-P1-241）——判据单点化，[AutofillUnlockActivity]
 * 只消费结论，不在 Activity 内散写判定。
 *
 * ## 判据取自哪里（不得自造新匹配语义）
 *
 * - **候选集合**由既有 [AutofillCandidateRanker.rank] 产出——与服务端已解锁分支同一套
 *   「匹配判定 + 打分 + 排序 + 截断」，匹配条件一字未放宽；
 * - **「强匹配」= 既有 [AutofillCandidateRanker.MatchReason] 的 exact 语义**：
 *   [AutofillCandidateRanker.MatchReason.EXACT_DOMAIN]（条目域名与目标域完全相等）或
 *   [AutofillCandidateRanker.MatchReason.EXACT_PACKAGE]（条目 `android://` 严格精确绑定，
 *   无父子关系）任一命中；仅 [AutofillCandidateRanker.MatchReason.PARENT_DOMAIN]（父域）不算强匹配。
 *   该 exact / parent 二分是 [AutofillCandidateRanker] 既有分类（打分 140 / 130 vs 120 的来源），
 *   本判据只消费、不新造；
 * - **调用方绑定状态** = [AndroidPackageBindingPolicy.isPackageDimensionAuthorized] 的结论
 *   （ISSUE-P2-46：「包名 + 签名摘要」首次绑定）。该绑定的**补救写入点**是选择器
 *   [AutofillPickerActivity.bindCallerForPackageDimension]（P2-46 指定的唯一补救入口；
 *   确认页 P1-24「记住此应用」勾选虽写同一信任存储，但那是授权语义的副产品、非 P2-46 补救入口）
 *   ——故**未绑定调用方必须继续走 PICKER**（fail-closed）：否则未绑定者永远不会经过选择器，
 *   `android://` 首次绑定便无从写入；
 * - **表单上下文** = 本次请求是否识别到至少一个目标输入框（[hasTargetField]）；
 *   无目标框时无可填充对象，回落 PICKER（由调用方按既有语义处置）。
 *
 * ## 真值表（PD-05 候选 C，2026-09-22 裁决）
 *
 * | 候选集合            | 已绑定 | 有目标框 | 路由     |
 * |---------------------|--------|----------|----------|
 * | 恰 1 条且强匹配     | 是     | 是       | CONFIRM  |
 * | 恰 1 条但仅父域弱匹配 | 任意   | 是       | PICKER   |
 * | 恰 1 条强匹配       | 否     | 是       | PICKER   |
 * | 多条（≥2）          | 任意   | 是       | PICKER   |
 * | 零条                | 任意   | 是       | PICKER   |
 * | 任意                | 任意   | 否       | PICKER   |
 *
 * CONFIRM 分支交付由 [AutofillConfirmActivity] 承担（归属展示 + 单次生物识别 / 手动确认 +
 * Dataset 回传）；解锁页**不自建 Dataset**（PD-05 对候选 B 的禁令延续）。
 */
object AutofillUnlockRouter {

    /** 路由结论：[Confirm] 携带唯一强匹配条目；[Picker] 维持现状链入手动选择器 */
    sealed interface Route {
        data class Confirm(val entry: KdbxEntry) : Route
        data object Picker : Route
    }

    /**
     * 计算解锁成功后的链路路由。
     *
     * @param candidates [AutofillCandidateRanker.rank] 的既有产出（已匹配 + 已打分 + 已截断）
     * @param packageDimensionAuthorized [AndroidPackageBindingPolicy.isPackageDimensionAuthorized]
     *   的结论（ISSUE-P2-46 首次绑定门）
     * @param hasTargetField 本次请求是否识别到至少一个目标输入框（表单上下文）
     * @return 唯一强匹配 + 已绑定 + 有目标框 ⇒ [Route.Confirm]；其余一律 [Route.Picker]
     *   （任何不确定分支都落在 PICKER 一侧，fail-closed）
     */
    fun route(
        candidates: List<AutofillCandidateRanker.Ranked>,
        packageDimensionAuthorized: Boolean,
        hasTargetField: Boolean
    ): Route {
        if (!hasTargetField) return Route.Picker
        // ISSUE-P2-46 fail-closed：未绑定调用方必须经选择器完成首次绑定补救
        if (!packageDimensionAuthorized) return Route.Picker
        val only = candidates.singleOrNull() ?: return Route.Picker
        if (!isStrongMatch(only)) return Route.Picker
        return Route.Confirm(only.entry)
    }

    /** 强匹配 = 既有 exact 语义（EXACT_DOMAIN / EXACT_PACKAGE 任一命中；仅父域不算） */
    private fun isStrongMatch(ranked: AutofillCandidateRanker.Ranked): Boolean =
        AutofillCandidateRanker.MatchReason.EXACT_DOMAIN in ranked.reasons ||
            AutofillCandidateRanker.MatchReason.EXACT_PACKAGE in ranked.reasons
}
