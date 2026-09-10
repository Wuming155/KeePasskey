package com.keepasskey.app.security

/**
 * 运行完整性风险闸门（抽象，ISSUE-P2-08）。
 *
 * 敏感通道（生物识别快速解锁、传统自动填充）依赖本抽象而非具体探测实现——
 * 便于 JVM 单测注入假实现，也符合依赖倒置：高层只约定了「读取当前策略」这一契约。
 * 生产绑定见 `RuntimeIntegrityModule`，实现为 [RuntimeIntegrityDetector]。
 */
interface RuntimeIntegrityGate {

    /**
     * 同步读取当前生效策略。
     * 首次后台扫描未完成时返回 [IntegrityEnforcement.UNDETERMINED]（保守 fail-closed）。
     */
    fun currentEnforcement(): IntegrityEnforcement

    /**
     * 等待首次后台扫描完成后再读取策略（供 suspend 调用点，如自动填充请求）。
     * 超时未完成同样返回 [IntegrityEnforcement.UNDETERMINED]，绝不返回「默认放行」。
     */
    suspend fun awaitEnforcement(): IntegrityEnforcement
}
