package com.keepasskey.sync.engine

/**
 * 同步防回滚状态的本地认证抽象（ISSUE-P2-18）。
 *
 * `sync` 模块为纯 Kotlin（仅依赖 `core`），无法直接使用 Android Keystore；本接口由 `sync` 定义，
 * `app` 层以 AndroidKeyStore 内不可导出的 HMAC 密钥实现并注入，维持模块单向依赖拓扑。
 * JVM 单测注入固定密钥假实现。
 *
 * 认证对象为设备的「已见内容高水位」状态文件（见 [SyncRollbackGuard]），
 * 用于在 Assume Breach（云端不可信）威胁模型下拒绝旧库重放。
 */
interface SyncIntegrityMac {

    /** 计算载荷 MAC；密钥不可用时返回 null（调用方按不可信处理） */
    fun compute(data: ByteArray): ByteArray?

    /** 校验载荷 MAC；密钥不可用 / MAC 缺失 / 不匹配一律 false */
    fun verify(data: ByteArray, mac: ByteArray?): Boolean
}

/**
 * 空实现：不做完整性认证，[verify] 恒 false → [SyncRollbackGuard] 视状态为不可信（无历史），
 * 即**禁用防回滚**。仅供未接线路径与 JVM 测试使用，绝不绑定生产 DI。
 */
object NoopSyncIntegrityMac : SyncIntegrityMac {
    override fun compute(data: ByteArray): ByteArray? = null
    override fun verify(data: ByteArray, mac: ByteArray?): Boolean = false
}
