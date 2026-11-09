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

/**
 * [ThrottleClock] 的可控替身（ENG-20261028-37）：墙钟与单调时钟可**分别**推进，
 * 用于区分「系统调时」与「真实流逝」——只有 [shiftWall] 才模拟用户/网络调整系统时间。
 */
class FakeThrottleClock(
    wallStart: Long = 0L,
    monotonicStart: Long = 0L
) : ThrottleClock() {

    /** 当前墙钟（epoch millis），供用例构造持久截止 */
    var wall: Long = wallStart

    /** 当前单调时钟（millis） */
    var monotonic: Long = monotonicStart

    override fun wallMillis(): Long = wall

    override fun monotonicMillis(): Long = monotonic

    /** 两钟同步推进：模拟正常流逝 */
    fun advance(ms: Long) {
        wall += ms
        monotonic += ms
    }

    /** 只移动墙钟：模拟调时（进程存续期间单调时钟不回退、不跳变） */
    fun shiftWall(ms: Long) {
        wall += ms
    }
}
