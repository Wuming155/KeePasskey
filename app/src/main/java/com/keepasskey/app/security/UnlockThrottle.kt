package com.keepasskey.app.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主密码解锁失败节流记录（ISSUE-P1-04 / ZT-04）。
 *
 * @param failureCount        连续失败次数（成功解锁后归零）
 * @param lockoutUntilEpochMs 锁定截止时间戳（epoch millis，`0` 表示当前未锁定）
 */
data class UnlockThrottleRecord(
    val failureCount: Int = 0,
    val lockoutUntilEpochMs: Long = 0L
)

/**
 * 解锁闸门判定结果。
 *
 * - [Allowed]：可继续尝试解锁；
 * - [Locked]：处于锁定期，调用方必须 fail-closed 拒绝解锁，**不得触碰 KDF/解密管线**。
 */
sealed interface ThrottleGate {
    /** 当前连续失败次数（供 UI 呈现「剩余尝试」等提示） */
    val failureCount: Int

    data class Allowed(override val failureCount: Int) : ThrottleGate

    data class Locked(
        override val failureCount: Int,
        /** 距锁定解除的剩余毫秒数（恒 > 0） */
        val remainingMs: Long
    ) : ThrottleGate
}

/**
 * 主密码解锁失败节流存储抽象。
 *
 * 生产环境使用 [SharedPrefsUnlockThrottleStore]（跨进程重启持久化，杜绝「杀进程即重置计数」
 * 的绕过路径）；JVM 单测注入内存实现。持久化内容仅为失败计数与时间戳，**不含任何主密码明文**。
 */
interface UnlockThrottleStore {
    fun read(databaseId: String): UnlockThrottleRecord
    fun write(databaseId: String, record: UnlockThrottleRecord)
    fun reset(databaseId: String)
}

/**
 * [UnlockThrottleStore] 的 SharedPreferences 实现：计数与锁定截止落盘，
 * 卸载应用或清除数据前持久有效。
 */
@Singleton
class SharedPrefsUnlockThrottleStore @Inject constructor(
    @ApplicationContext context: Context
) : UnlockThrottleStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(databaseId: String): UnlockThrottleRecord = UnlockThrottleRecord(
        failureCount = prefs.getInt(keyCount(databaseId), 0),
        lockoutUntilEpochMs = prefs.getLong(keyLock(databaseId), 0L)
    )

    override fun write(databaseId: String, record: UnlockThrottleRecord) {
        prefs.edit()
            .putInt(keyCount(databaseId), record.failureCount)
            .putLong(keyLock(databaseId), record.lockoutUntilEpochMs)
            .apply()
    }

    override fun reset(databaseId: String) {
        prefs.edit()
            .remove(keyCount(databaseId))
            .remove(keyLock(databaseId))
            .apply()
    }

    private fun keyCount(databaseId: String): String = "${databaseId}_unlock_fail_count"
    private fun keyLock(databaseId: String): String = "${databaseId}_unlock_lock_until"

    private companion object {
        const val PREFS_NAME = "com.keepasskey.unlock_throttle"
    }
}

/**
 * 节流退避策略（纯函数，JVM 可测）。
 *
 * 设计依据：OWASP MASVS-AUTH-10（失败限流）与 Google/业界惯例（如 Apigee 5 次失败触发锁定）——
 * 连续失败达到 [FAILURE_THRESHOLD] 后启用**指数退避**并封顶，既抵御在线暴力破解，
 * 又避免误输用户被永久拒之门外。阈值与时长集中为常量，便于统一调参（「阈值可配」）。
 */
object UnlockThrottlePolicy {

    /** 连续失败达到该阈值后启用退避锁定（默认 5 次，符合「不超过 5 次后启用退避」的验收基线） */
    const val FAILURE_THRESHOLD = 5

    /** 首次退避基准时长（30 秒） */
    const val BASE_BACKOFF_MS = 30_000L

    /** 退避时长上限（30 分钟），防止无限增长将用户长期锁死 */
    const val MAX_BACKOFF_MS = 30L * 60L * 1000L

    /** 移位安全阈值：指数超过该值直接取上限，杜绝左移溢出为负 */
    private const val MAX_SHIFT = 20

    /**
     * 给定连续失败次数，返回本次应施加的锁定时长（毫秒）；`0` 表示无需锁定。
     * 达到阈值后按 `BASE * 2^(count - THRESHOLD)` 指数增长并封顶于 [MAX_BACKOFF_MS]。
     */
    fun backoffMillisFor(failureCount: Int): Long {
        if (failureCount < FAILURE_THRESHOLD) return 0L
        val exponent = failureCount - FAILURE_THRESHOLD
        val backoff = if (exponent >= MAX_SHIFT) {
            MAX_BACKOFF_MS
        } else {
            BASE_BACKOFF_MS shl exponent
        }
        return backoff.coerceAtMost(MAX_BACKOFF_MS)
    }
}

/**
 * 主密码解锁失败节流管理器（ISSUE-P1-04 / ZT-04）。
 *
 * 职责单一：维护「连续失败计数 + 渐进退避锁定」的状态机，向 [com.keepasskey.app.ui.screens.unlock.UnlockViewModel]
 * 暴露三个原子操作——解锁前闸门 [gate]、失败登记 [registerFailure]、成功重置 [registerSuccess]。
 * 主密码擦除等敏感数据治理由 ViewModel 负责，本类不接触任何主密码明文。
 *
 * 时间源以方法默认参数 `now` 注入（缺省 [System.currentTimeMillis]），
 * 使锁定/解锁的时间边界在 JVM 单测中可精确断言，无需真实等待。
 */
@Singleton
class UnlockThrottleManager @Inject constructor(
    private val store: UnlockThrottleStore
) {

    /**
     * 解锁前闸门：锁定期内返回 [ThrottleGate.Locked]（fail-closed），否则 [ThrottleGate.Allowed]。
     * 调用方拿到 Locked 时**必须拒绝解锁**，不得进入 KDF/解密流程。
     */
    fun gate(databaseId: String, now: Long = System.currentTimeMillis()): ThrottleGate {
        val record = store.read(databaseId)
        val remaining = record.lockoutUntilEpochMs - now
        return if (remaining > 0L) {
            ThrottleGate.Locked(record.failureCount, remaining)
        } else {
            ThrottleGate.Allowed(record.failureCount)
        }
    }

    /**
     * 登记一次「凭据错误」失败：累加计数并按 [UnlockThrottlePolicy] 重算锁定截止时间戳。
     * 返回更新后的闸门状态，供 UI 即时呈现是否进入锁定及剩余时长。
     *
     * 注意：仅认证失败（主密码/密钥不匹配）应计入，IO/文件损坏等非认证错误不应调用本方法，
     * 以免瞬时故障误锁用户——该分流由调用方（ViewModel）负责。
     */
    fun registerFailure(databaseId: String, now: Long = System.currentTimeMillis()): ThrottleGate {
        val record = store.read(databaseId)
        val newCount = record.failureCount + 1
        val backoff = UnlockThrottlePolicy.backoffMillisFor(newCount)
        val lockUntil = if (backoff > 0L) now + backoff else 0L
        store.write(databaseId, UnlockThrottleRecord(newCount, lockUntil))
        return if (backoff > 0L) {
            ThrottleGate.Locked(newCount, backoff)
        } else {
            ThrottleGate.Allowed(newCount)
        }
    }

    /** 成功解锁：清零计数与锁定状态。 */
    fun registerSuccess(databaseId: String) {
        store.reset(databaseId)
    }
}
