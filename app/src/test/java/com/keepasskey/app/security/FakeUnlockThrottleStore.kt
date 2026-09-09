package com.keepasskey.app.security

/**
 * [UnlockThrottleStore] 的内存实现，仅供 JVM 单元测试使用（ISSUE-P1-04）。
 * 生产环境使用 [SharedPrefsUnlockThrottleStore]，本类不得绑定生产 DI。
 */
class FakeUnlockThrottleStore : UnlockThrottleStore {

    private val records = mutableMapOf<String, UnlockThrottleRecord>()

    override fun read(databaseId: String): UnlockThrottleRecord =
        records[databaseId] ?: UnlockThrottleRecord()

    override fun write(databaseId: String, record: UnlockThrottleRecord) {
        records[databaseId] = record
    }

    override fun reset(databaseId: String) {
        records.remove(databaseId)
    }

    /** 测试辅助：直接预置锁定状态（模拟「已锁定」而无需真实等待） */
    fun seed(databaseId: String, record: UnlockThrottleRecord) {
        records[databaseId] = record
    }
}
