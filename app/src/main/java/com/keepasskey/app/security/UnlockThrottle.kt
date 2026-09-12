package com.keepasskey.app.security

import android.content.Context
import com.keepasskey.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * 主密码解锁失败节流记录（ISSUE-P1-04 / ZT-04）。
 *
 * @param failureCount        连续失败次数（成功解锁后归零）
 * @param lockoutUntilEpochMs 锁定截止时间戳（epoch millis，`0` 表示当前未锁定）
 * @param integrityIntact     记录完整性是否通过校验（ISSUE-P3-54：false 表示被删除 / 篡改，
 *                            由 [UnlockThrottleManager.gate] fail-closed 处理；
 *                            默认 true 以兼容 JVM 测试用内存实现）
 */
data class UnlockThrottleRecord(
    val failureCount: Int = 0,
    val lockoutUntilEpochMs: Long = 0L,
    val integrityIntact: Boolean = true
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
 *
 * ISSUE-P3-54：追加 Keystore 密钥的 HMAC（[UnlockThrottleIntegrity]）完整性绑定——
 * 记录被删除 / 篡改时 MAC 校验失败，由 [UnlockThrottleManager.gate] fail-closed 处置。
 */
@Singleton
class SharedPrefsUnlockThrottleStore @Inject constructor(
    @ApplicationContext context: Context,
    private val integrity: UnlockThrottleIntegrity
) : UnlockThrottleStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(databaseId: String): UnlockThrottleRecord {
        val count = prefs.getInt(keyCount(databaseId), 0)
        val lockUntil = prefs.getLong(keyLock(databaseId), 0L)
        val storedMac = prefs.getString(keyMac(databaseId), null)
        // 全新安装：无任何记录字段（含 MAC）——无可保护对象，视为完整
        if (count == 0 && lockUntil == 0L && storedMac == null) return UnlockThrottleRecord()

        val record = UnlockThrottleRecord(count, lockUntil)
        val macBytes = storedMac?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        return record.copy(integrityIntact = integrity.verify(databaseId, record, macBytes))
    }

    override fun write(databaseId: String, record: UnlockThrottleRecord) {
        val encodedMac = integrity.mac(databaseId, record)
            ?.let { Base64.getEncoder().encodeToString(it) }
            .orEmpty()
        prefs.edit()
            .putInt(keyCount(databaseId), record.failureCount)
            .putLong(keyLock(databaseId), record.lockoutUntilEpochMs)
            .putString(keyMac(databaseId), encodedMac)
            .apply()
    }

    override fun reset(databaseId: String) {
        prefs.edit()
            .remove(keyCount(databaseId))
            .remove(keyLock(databaseId))
            .remove(keyMac(databaseId))
            .apply()
    }

    private fun keyCount(databaseId: String): String = "${databaseId}_unlock_fail_count"
    private fun keyLock(databaseId: String): String = "${databaseId}_unlock_lock_until"
    private fun keyMac(databaseId: String): String = "${databaseId}_unlock_mac"

    private companion object {
        const val PREFS_NAME = "com.keepasskey.unlock_throttle"
    }
}

/**
 * 重试节流运行时配置（ISSUE-P3-68：用户可配置性）。
 *
 * @param enabled      节流总开关；`false` = 用户显式退出重试锁定（`gate` 放行、失败不再退避）。
 *                     **默认 `true`**——安全默认不得放松；
 * @param maxBackoffMs 退避封顶时长（用户可自定义的最长锁定，毫秒），合法域 [60s, 24h]。
 *                     与 [UnlockThrottlePolicy.MAX_BACKOFF_MS] 的编译期常量解耦。
 */
data class ThrottleConfig(
    val enabled: Boolean = true,
    val maxBackoffMs: Long = UnlockThrottlePolicy.MAX_BACKOFF_MS
)

/**
 * [UnlockThrottlePolicy] 退避策略（纯函数，JVM 可测）。
 *
 * 设计依据：OWASP MASVS-AUTH-10（失败限流）与 Google/业界惯例（如 Apigee 5 次失败触发锁定）——
 * 连续失败达到 [FAILURE_THRESHOLD] 后启用**指数退避**并封顶，既抵御在线暴力破解，
 * 又避免误输用户被永久拒之门外。阈值与时长集中为常量，便于统一调参（「阈值可配」）。
 *
 * ISSUE-P3-68：封顶时长与开关自 [ThrottleConfig] 运行时注入——`config` 缺省时与
 * 既有编译期常量行为逐位一致（既有调用方与单测零改动）。
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
     * 达到阈值后按 `BASE * 2^(count - THRESHOLD)` 指数增长并封顶于 [ThrottleConfig.maxBackoffMs]。
     * 开关关闭（`config.enabled == false`）时恒返回 `0`（不锁定）。
     */
    fun backoffMillisFor(
        failureCount: Int,
        config: ThrottleConfig = ThrottleConfig()
    ): Long {
        if (!config.enabled || failureCount < FAILURE_THRESHOLD) return 0L
        val exponent = failureCount - FAILURE_THRESHOLD
        val backoff = if (exponent >= MAX_SHIFT) {
            config.maxBackoffMs
        } else {
            (BASE_BACKOFF_MS shl exponent).coerceAtMost(config.maxBackoffMs)
        }
        return backoff.coerceAtMost(config.maxBackoffMs)
    }
}

/**
 * 重试节流配置源（ISSUE-P3-68）：节流路径同步读取当前 [ThrottleConfig] 的最小抽象。
 * 生产实现为 [UnlockThrottleConfigProvider]（设置流 → 进程级缓存）；JVM 单测注入固定值。
 */
interface ThrottleConfigSource {
    val current: ThrottleConfig
}

/**
 * 重试节流配置的进程级缓存（ISSUE-P3-68）。
 *
 * 设置流为异步 DataStore 通道，而 [UnlockThrottleManager.gate]/[UnlockThrottleManager.registerFailure]
 * 是同步签名（既有调用方与 JVM 单测均按位置传参依赖该契约）。本类以独立协程持续收集设置流，
 * 把当前 [ThrottleConfig] 缓存为 `@Volatile` 快照，供节流路径零挂起地同步读取——
 * 主解锁（`UnlockViewModel`）与子库挂载（`ChildDatabaseSessionManager`）两条路径自动同时生效。
 *
 * 进程启动初期设置尚未抵达的短窗内回落 [UserSettings] 出厂默认（2026-09-12 用户裁决：关闭 + 30 分钟封顶）。
 * （[ThrottleConfig] 无参构造的「开启」默认仅用于未接配置源的 JVM 单测场景。）
 */
@Singleton
class UnlockThrottleConfigProvider @Inject constructor(
    settingsRepository: SettingsRepository
) : ThrottleConfigSource {

    @Volatile
    override var current: ThrottleConfig = ThrottleConfig(enabled = false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            settingsRepository.getSettings().collect { settings ->
                current = ThrottleConfig(
                    enabled = settings.unlockThrottleEnabled,
                    maxBackoffMs = settings.unlockLockoutMaxSeconds
                        .coerceIn(MIN_LOCKOUT_SECONDS, MAX_LOCKOUT_SECONDS)
                        .seconds.inWholeMilliseconds
                )
            }
        }
    }

    companion object {
        /** 自定义最长锁定的合法下限（1 分钟；低于基准退避时长仍有「立即反馈」意义） */
        const val MIN_LOCKOUT_SECONDS = 60

        /** 自定义最长锁定的合法上限（24 小时） */
        const val MAX_LOCKOUT_SECONDS = 24 * 60 * 60
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
 *
 * ISSUE-P3-68：运行时配置经 [UnlockThrottleConfigProvider] 注入——总开关关闭时 `gate` 放行、
 * 失败不再退避；封顶时长随用户自定义。`configProvider` 可空（缺省 [ThrottleConfig] 安全默认），
 * 仅用于 JVM 单测零改动注入；生产 DI 恒注入真实实例。**记录完整性 fail-closed 处置不受开关影响**。
 */
@Singleton
class UnlockThrottleManager @Inject constructor(
    private val store: UnlockThrottleStore,
    private val configProvider: ThrottleConfigSource? = null
) {

    /** 当前生效配置（source 缺省仅见于 JVM 单测，回落 ThrottleConfig 无参默认：开启 + 30 分钟封顶） */
    private val config: ThrottleConfig
        get() = configProvider?.current ?: ThrottleConfig()

    /**
     * 解锁前闸门：锁定期内返回 [ThrottleGate.Locked]（fail-closed），否则 [ThrottleGate.Allowed]。
     * 调用方拿到 Locked 时**必须拒绝解锁**，不得进入 KDF/解密流程。
     *
     * ISSUE-P3-68：节流开关关闭时忽略既有锁定截止直接放行；但记录完整性校验失败
     * （ISSUE-P3-54 防篡改语义）**不受开关影响**，恒 fail-closed。
     */
    fun gate(databaseId: String, now: Long = System.currentTimeMillis()): ThrottleGate {
        val record = store.read(databaseId)
        // ISSUE-P3-54：记录完整性校验失败（被删除 / 篡改）→ fail-closed。
        // 落一个带有效 MAC 的**有界**锁定期记录后返回 Locked：既不因记录被动过而放行，
        // 也不永久锁死用户（锁定期上限 MAX_BACKOFF_MS）。
        if (!record.integrityIntact) {
            val locked = UnlockThrottleRecord(
                failureCount = UnlockThrottlePolicy.FAILURE_THRESHOLD,
                lockoutUntilEpochMs = now + UnlockThrottlePolicy.MAX_BACKOFF_MS
            )
            store.write(databaseId, locked)
            return ThrottleGate.Locked(
                UnlockThrottlePolicy.FAILURE_THRESHOLD,
                UnlockThrottlePolicy.MAX_BACKOFF_MS
            )
        }
        // ISSUE-P3-68：用户显式退出重试节流——既有锁定截止不再生效（放行不注销计数）
        if (!config.enabled) return ThrottleGate.Allowed(record.failureCount)
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
        val backoff = UnlockThrottlePolicy.backoffMillisFor(newCount, config)
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
