package com.keepasskey.app.security

/**
 * 运行完整性风险闸门假实现（JVM 单测用，ISSUE-P2-08）。
 * 生产实现为 [RuntimeIntegrityDetector]；单测注入本类以固定策略、避免 Android 框架依赖。
 */
class FakeRuntimeIntegrityGate(
    private val enforcement: IntegrityEnforcement = IntegrityEnforcement.ALLOWED
) : RuntimeIntegrityGate {

    override fun currentEnforcement(): IntegrityEnforcement = enforcement

    override suspend fun awaitEnforcement(): IntegrityEnforcement = enforcement
}
