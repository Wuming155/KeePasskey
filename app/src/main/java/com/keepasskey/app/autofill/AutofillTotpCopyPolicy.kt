package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.EntryTotpSnapshot

/**
 * 「填充后自动复制 TOTP」的纯决策层（ISSUE-P3-03 43b：`autofillCopyTotp`）。
 *
 * 把决策从 Activity 的异步流程中抽出为可单测的纯函数，Verification 不依赖 UI 交互：
 * 只有「用户开启开关」且「该条目确实存在可用 TOTP」时才复制——不满足时绝不触碰剪贴板。
 *
 * 安全约定：
 * - 复制经 `ClipboardSecurityManager.copySensitiveText`（`EXTRA_IS_SENSITIVE` + 定时自动擦除），
 *   绝不直连系统剪贴板；
 * - 验证码不进入任何日志、不写入 StateFlow、不驻留成员变量。
 */
object AutofillTotpCopyPolicy {

    /**
     * @param copyTotpEnabled 用户偏好（设置页「填充后复制 TOTP」）
     * @param snapshot 条目的 TOTP 即时快照；null 表示该条目无 TOTP 或计算失败
     */
    fun shouldCopy(copyTotpEnabled: Boolean, snapshot: EntryTotpSnapshot?): Boolean =
        copyTotpEnabled && snapshot != null && snapshot.code.isNotBlank()
}
