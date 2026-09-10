package com.keepasskey.app.security

/**
 * [UnlockPasskeyStore] 的内存实现，仅供 JVM 单元测试使用（ISSUE-P1-09）。
 * 生产环境使用 [BiometricCredentialStorage]（含硬件 HMAC 防篡改封存），
 * 本类不得绑定生产 DI。
 */
class FakeUnlockPasskeyStore : UnlockPasskeyStore {

    private val records = mutableMapOf<String, UnlockPasskeyRecord>()

    override fun saveUnlockPasskey(databaseId: String, publicKeyB64: String, credentialIdB64: String, signCount: Int) {
        records[databaseId] = UnlockPasskeyRecord(publicKeyB64, credentialIdB64, signCount)
    }

    override fun getUnlockPasskey(databaseId: String): UnlockPasskeyRecord? = records[databaseId]

    override fun commitSignCount(databaseId: String, newSignCount: Int) {
        records[databaseId] = records[databaseId]?.copy(signCount = newSignCount) ?: return
    }

    override fun clearUnlockPasskey(databaseId: String) {
        records.remove(databaseId)
    }

    /** 测试辅助：直接预置登记记录（模拟攻击者写入合法形态记录） */
    fun seed(databaseId: String, record: UnlockPasskeyRecord) {
        records[databaseId] = record
    }
}
