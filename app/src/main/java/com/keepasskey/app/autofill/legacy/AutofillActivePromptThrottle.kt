package com.keepasskey.app.autofill.legacy

/**
 * 主动填充提示节流（ISSUE-P3-374 AC②，对齐 Monica `ActiveFillPromptThrottle`）。
 *
 * 取代原服务内「全局 2 秒去抖」（`NOTIFY_DEBOUNCE_MS`）：内容变化事件对同一目标窗口
 * 高频触发，2 秒窗口只挡瞬时毛刺、挡不住「停留页面上反复提示」。现改为**按包名冷却**——
 * 同一目标包在冷却期内至多提示一次，不同包互不影响（切换应用即重新获得提示机会）。
 *
 * 生命周期＝服务实例内存态（与原 AtomicLong 同口径）：不落盘、不进任何状态流；
 * 分层刻意保持「判定纯函数 + 实例只管记忆」——判定可在宿主 JVM 穷举，记忆只做存取。
 * 不经 Hilt 注入：本节流态无跨注入点共享需求，与服务实例同生共死（同 `serviceScope` 先例）。
 */
internal class AutofillActivePromptThrottle(
    /** 取时注入点（单测传假时钟；生产为系统时钟） */
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    /** 包名 → 上次提示时刻（插入序，超容量按最旧淘汰） */
    private val lastPromptAtByPackage = LinkedHashMap<String, Long>()

    /**
     * 本次是否应发提示；判定为真时同步记账（先判后记，同包并发由 [synchronized] 串行）。
     * 空白包名恒不提示（fail-closed：无目标即无提示）。
     */
    @Synchronized
    fun shouldPrompt(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return false
        val now = nowMillis()
        if (!shouldPromptAt(lastPromptAtByPackage[normalized], now)) return false
        lastPromptAtByPackage.remove(normalized)
        lastPromptAtByPackage[normalized] = now
        while (lastPromptAtByPackage.size > MAX_TRACKED_PACKAGES) {
            lastPromptAtByPackage.remove(lastPromptAtByPackage.keys.first())
        }
        return true
    }

    /** 测试复位（本类仅测试使用；生产无清空需求——冷却态随服务销毁自然消亡） */
    @Synchronized
    internal fun reset() {
        lastPromptAtByPackage.clear()
    }

    companion object {
        /**
         * 同一目标包的提示冷却（Monica 同型防打扰；60 秒＝用户离开表单或完成操作的
         * 合理观察窗）。命名常量，禁散落字面量。
         */
        internal const val COOLDOWN_MS = 60_000L

        /** 记忆容量闸门：长跑服务进程最多跟踪的包名数（超出按插入序淘汰最旧） */
        internal const val MAX_TRACKED_PACKAGES = 64

        /**
         * 纯判定（AC② 反例用例直接打这里）：
         * - 首次（无记录）⇒ 可提示；
         * - 距上次提示 < [COOLDOWN_MS] ⇒ 拒绝；
         * - ≥ 冷却 ⇒ 可提示；时钟回拨（now < last）按未到冷却拒绝（fail-closed 防重刷）。
         */
        internal fun shouldPromptAt(lastPromptAt: Long?, now: Long, cooldownMs: Long = COOLDOWN_MS): Boolean {
            if (lastPromptAt == null) return true
            val elapsed = now - lastPromptAt
            if (elapsed < 0) return false
            return elapsed >= cooldownMs
        }
    }
}
