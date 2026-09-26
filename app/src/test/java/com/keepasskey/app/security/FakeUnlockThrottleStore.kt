package com.keepasskey.app.security

/**
 * [UnlockThrottleStore] 的内存实现，仅供 JVM 单元测试使用（ISSUE-P1-04）。
 * 生产环境使用 [SharedPrefsUnlockThrottleStore]，本类不得绑定生产 DI。
 *
 * 注意：本类 [reset] 直接移除记录，只模拟**逻辑上的清零**；持久化维度的回归
 * （落盘读写 / 重置清零 / 键按库隔离）由 [SharedPrefsUnlockThrottleStoreTest] 覆盖——
 * 那需要真实持久化语义，内存实现无法承载。
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
