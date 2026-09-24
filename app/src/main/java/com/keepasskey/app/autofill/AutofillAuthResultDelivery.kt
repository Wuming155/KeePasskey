package com.keepasskey.app.autofill

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.keepasskey.app.R

/**
 * 认证数据集**交付载荷**的统一构造（选择器与已解锁候选二次确认共用，ISSUE-P2-88）。
 *
 * ## 官方契约（`Dataset.Builder#setAuthentication` 原文）
 *
 * 1. 认证流程结束后应把结果设为 `RESULT_OK`，并把「fully populated dataset」经
 *    `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传——「**If you provide a dataset in the
 *    result, it will replace the authenticated dataset and will be immediately filled in**」；
 * 2. 「Do **not** make the provided pending intent immutable by using `PendingIntent.FLAG_IMMUTABLE`
 *    as the platform needs to fill in the authentication arguments」。
 *
 * 两条都是强制项：只回传「成功」而不回传数据集时，框架手上没有可写值（真机留痕
 * `onAuthenticationResult(): empty intent`），目标输入框恒为空。
 *
 * ## 为何收敛到本文件
 *
 * 选择器（[AutofillPickerActivity]）与二次确认（[AutofillConfirmActivity]）交付的是**同一形态的载荷**，
 * 原先各写一份，正是这种「同语义两处实现」让确认路径漂移成了空载荷。现两处共用本文件的构造，
 * 任何一侧再想「只回传成功」都必须先改掉这里。
 *
 * 安全约束：本文件只在**用户显式确认之后**被调用（选择器点选 / 确认页确认）；
 * 凭据明文在此只进 `Dataset`（自动填充 API 的硬约束为 `CharSequence`），不落任何状态、日志或成员变量。
 */

/**
 * 构造待回传的真实 [Dataset]（字段 id 由调用方从认证 Intent 取出）。
 *
 * @param menuTitle 下拉菜单首行文案（条目用户名或条目标题）
 * @param menuSubtitle 下拉菜单副行文案
 * @return 目标字段 id 都为 null、或凭据没有任何可写字段时返回 **null**——调用方**必须**按失败处置：
 *   `Dataset.Builder#build()` 在没有任何 `setField` 时会抛异常，而回传一个空数据集等于对框架谎报成功。
 */
internal fun buildAuthenticationResultDataset(
    packageName: String,
    menuTitle: CharSequence,
    menuSubtitle: CharSequence,
    username: String,
    password: String,
    usernameId: AutofillId?,
    passwordId: AutofillId?,
    // ISSUE-P3-298 ⑤：表单显式声明的 OTP 框 + 该条目当前 TOTP 值（仅 TOTP；由调用方判定）
    otpId: AutofillId? = null,
    otpCode: String = ""
): Dataset? {
    val views = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
        setTextViewText(R.id.tv_username, menuTitle)
        setTextViewText(R.id.tv_subtitle, menuSubtitle)
    }
    val builder = Dataset.Builder(
        Presentations.Builder()
            .setMenuPresentation(views)
            .setDialogPresentation(views)
            .build()
    )
    var fieldCount = 0
    if (usernameId != null && username.isNotEmpty()) {
        builder.setField(
            usernameId,
            Field.Builder().setValue(AutofillValue.forText(username)).build()
        )
        fieldCount++
    }
    if (passwordId != null && password.isNotEmpty()) {
        builder.setField(
            passwordId,
            Field.Builder().setValue(AutofillValue.forText(password)).build()
        )
        fieldCount++
    }
    if (otpId != null && otpCode.isNotEmpty()) {
        builder.setField(
            otpId,
            Field.Builder().setValue(AutofillValue.forText(otpCode)).build()
        )
        fieldCount++
    }
    return if (fieldCount == 0) null else builder.build()
}

/**
 * 认证**成功**的回传载荷：`setResult(RESULT_OK, 本 Intent)`。
 *
 * 双参重载是本方法存在的意义之一——官方明文：Android 12 起认证结果 Intent 的 extras 为 null 会崩溃。
 */
internal fun authenticationResultIntent(dataset: Dataset): Intent =
    Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)

/**
 * 认证**未成功**（用户取消 / 凭据取不回来 / 无可写字段 / 会话锁定）的载荷。
 *
 * 一律双参 + 非空 extras：既不谎报成功（`RESULT_CANCELED`），也不触发「extras 为 null」的崩溃口径。
 */
internal fun authenticationCanceledIntent(): Intent = Intent().putExtras(Bundle.EMPTY)
