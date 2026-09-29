package com.keepasskey.app.autofill

import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import com.keepasskey.core.log.AppLog

/**
 * 确认页认证回传数据集组装（ISSUE-P2-384 复检后收口；纯结构性拆分自
 * [AutofillConfirmActivity.resolveAuthResultIntent]，规模闸门回压）。
 *
 * 职责单一：按复检后的可交付框 id + 条目凭据构造待回传 Dataset；
 * 复检闸门（字段级屏蔽）在调用方完成，本文件**不再**做屏蔽判定。
 */
internal object AutofillConfirmAuthDelivery {

    /**
     * 构造确认页回传数据集。
     *
     * @param deliverable 复检后的可交付框 id（被屏蔽字段已置 null）
     * @return 无可写字段时返回 null（调用方按取消处置，不构造空数据集谎报成功）
     */
    fun build(
        packageName: String,
        menuTitle: CharSequence,
        menuSubtitle: CharSequence,
        username: String,
        password: String,
        deliverable: AutofillAuthDeliveryBlockPolicy.DeliverableFields,
        otpId: AutofillId?,
        otpCode: String,
        structuredFields: Map<AutofillId, String>
    ): Dataset? = buildAuthenticationResultDataset(
        packageName = packageName,
        menuTitle = menuTitle,
        menuSubtitle = menuSubtitle,
        username = username,
        password = password,
        usernameId = deliverable.usernameId,
        passwordId = deliverable.passwordId,
        otpId = otpId,
        otpCode = otpCode,
        structuredFields = structuredFields
    )

    /** 只记录「哪些字段真的有值」，不含任何凭据内容 / 用户名 / 条目名 / 包名 */
    fun logDelivery(
        tag: String,
        usernameHasValue: Boolean,
        passwordHasValue: Boolean,
        otpHasValue: Boolean,
        structuredCount: Int,
        usernameIdPresent: Boolean,
        passwordIdPresent: Boolean,
        otpIdPresent: Boolean
    ) {
        AppLog.d(
            tag,
            "确认后回传数据集：用户名有值=$usernameHasValue" +
                " 口令有值=$passwordHasValue" +
                " 验证码有值=$otpHasValue" +
                " 结构化字段数=$structuredCount" +
                " 用户名框=$usernameIdPresent 密码框=$passwordIdPresent 验证码框=$otpIdPresent"
        )
    }
}
