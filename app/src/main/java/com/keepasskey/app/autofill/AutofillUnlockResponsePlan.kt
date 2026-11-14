package com.keepasskey.app.autofill

/**
 * 库锁定态「解锁成功后回传什么」的装配判据（纯函数，JVM 可单测）。
 *
 * 旧版按候选数分路：唯一强匹配且调用方已绑定时链确认页交付该条目，其余链选择器。
 * 该捷径在多账号应用上会「未察觉即填入另一账号」——同应用多个账号条目里只有一条
 * 被绑定时，解锁后只匹配到这一条，确认页内没有换账号入口，生物识别路径更是系统弹窗。
 * 现取消该分路：解锁成功统一回传**全部命中条目**的候选数据集 + 「搜索全部条目」入口，
 * 与库已解锁分支同一候选列表口径；每个候选仍带 `setAuthentication` 二次确认。
 *
 * ## 真值表
 *
 * | 目标框   | 候选数 | 「搜索全部条目」开关 | 响应                                      |
 * |----------|--------|----------------------|-------------------------------------------|
 * | 无       | 任意   | 任意                 | 取消（无可填充目标）                      |
 * | 有       | n      | 开                   | n 个候选 Dataset + 1 个手动选择 Dataset   |
 * | 有       | n ≥ 1  | 关                   | n 个候选 Dataset                          |
 * | 有       | 0      | 关                   | 取消（没有任何数据集可交付）              |
 *
 * 表中 **n = 1 与 n ≥ 2 完全同口径**——不存在「唯一候选直达确认页」分支。手动选择开关与
 * 库已解锁分支共用同一偏好（默认开启）；关闭后用户本就看不到兜底入口，两条链路一致。
 */
object AutofillUnlockResponsePlan {

    data class Plan(
        val candidateCount: Int,
        val includeManualPicker: Boolean,
        val canDeliver: Boolean
    )

    fun of(
        candidateCount: Int,
        manualPickerEnabled: Boolean,
        hasTargetField: Boolean
    ): Plan {
        if (!hasTargetField) return Plan(candidateCount = 0, includeManualPicker = false, canDeliver = false)
        return Plan(
            candidateCount = candidateCount.coerceAtLeast(0),
            includeManualPicker = manualPickerEnabled,
            canDeliver = candidateCount > 0 || manualPickerEnabled
        )
    }
}
