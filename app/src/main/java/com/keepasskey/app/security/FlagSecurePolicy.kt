package com.keepasskey.app.security

/**
 * FLAG_SECURE 守卫状态机内核（ISSUE-P2-09 / ZT-14）。
 *
 * 语义演进：
 * - 2026-09-12「开关即生效」修订：解锁态只看用户开关；
 * - **2026-10-02 用户裁决（§411 走查）**：撤销「锁定态无条件强制遮蔽」——
 *   截屏防护开关关闭后，解锁页（主密码输入）也必须**真实解除遮蔽**，
 *   用户报告「关闭截屏限制后解锁页依然无法截屏」即本条强制语义所致。
 *   最终口径：**FLAG_SECURE 只由用户开关决定**（风险确认对话框仍保留于设置页，
 *   确认后才真正写偏好）；首帧保守遮蔽仍保留（守卫 attach 时先加旗、Flow 抵达后按开关修正）。
 * 纯函数实现，状态机可被 JVM 单测完整覆盖。
 */
object FlagSecurePolicy {

    /**
     * 裁决当前是否应施加 FLAG_SECURE。
     *
     * @param userEnabled 用户设置中的「截屏防护」开关
     * @return true 表示施加 FLAG_SECURE
     */
    fun shouldApplySecure(userEnabled: Boolean): Boolean = userEnabled
}
