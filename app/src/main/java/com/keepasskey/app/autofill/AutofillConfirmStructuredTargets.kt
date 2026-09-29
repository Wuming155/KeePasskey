package com.keepasskey.app.autofill

import android.content.Intent
import android.view.autofill.AutofillId
import com.keepasskey.core.model.KdbxEntry

/**
 * ISSUE-P3-375 AC③：结构化目标角色名列表 extra（`StructuredFieldRole.name`，与框 id 列表
 * 按下标一一对应）。与 U/P/OTP 同口径——只传角色与框定位符（非敏感），**不**传任何字段值。
 * 常量与读取函数同文件收口（确认页为持有方，装配侧同包直引）。
 */
internal const val EXTRA_TARGET_STRUCTURED_ROLES =
    "com.keepasskey.app.autofill.EXTRA_CONFIRM_STRUCTURED_ROLES"

/** ISSUE-P3-375 AC③：结构化目标框 id 列表 extra（与角色列表按下标对应） */
internal const val EXTRA_TARGET_STRUCTURED_IDS =
    "com.keepasskey.app.autofill.EXTRA_CONFIRM_STRUCTURED_IDS"

/**
 * 确认页的结构化目标读取与取值（ISSUE-P3-375 AC③，自 `AutofillConfirmActivity` 按职责拆出——
 * 原文件随本批新增越入 tier2 行数闸门，拆分零行为变更）。
 *
 * 安全口径与确认页一致：本文件只在**用户确认成功之后**被调用；字段值经
 * [StructuredFieldPolicy.valuesFor] 从条目自定义字段取回，明文物化只进回传 [android.service.autofill.Dataset]，
 * 不落状态流 / 日志。
 */

/**
 * 读取结构化目标（角色名 + 框 id 按下标配对，随认证 Intent 下发）。
 * 两列表长度不齐 / 角色名非法一律截到可解析的最短公共前缀（fail-safe：宁少填不错填）。
 */
internal fun readStructuredTargetsFrom(intent: Intent): List<Pair<StructuredFieldRole, AutofillId>> {
    val roleNames = intent.getStringArrayListExtra(EXTRA_TARGET_STRUCTURED_ROLES)
        ?: return emptyList()
    val ids = intent.getParcelableArrayListExtra(
        EXTRA_TARGET_STRUCTURED_IDS,
        AutofillId::class.java
    ) ?: return emptyList()
    val size = minOf(roleNames.size, ids.size)
    return buildList {
        for (i in 0 until size) {
            val role = runCatching { StructuredFieldRole.valueOf(roleNames[i]) }.getOrNull() ?: continue
            val id = ids[i] ?: continue
            add(role to id)
        }
    }
}

/** 按条目自定义字段取回结构化值（空白值缺席；只在确认成功后的交付路径调用） */
internal fun buildStructuredFieldValues(
    targets: List<Pair<StructuredFieldRole, AutofillId>>,
    entry: KdbxEntry
): Map<AutofillId, String> = buildMap {
    for ((role, autofillId) in targets) {
        val raw = StructuredFieldPolicy.valuesFor(entry, setOf(role))[role]
        if (!raw.isNullOrEmpty()) put(autofillId, raw)
    }
}

/**
 * 从认证 Intent 读取目标输入框 id（服务端下发；缺失表示本次请求未识别到该角色）。
 * `ISSUE-P3-390` 起确认页与选择器共用一份（原各自持有 private 副本，零行为变更）。
 */
internal fun Intent.readAutofillId(key: String): AutofillId? =
    getParcelableExtra(key, AutofillId::class.java)
