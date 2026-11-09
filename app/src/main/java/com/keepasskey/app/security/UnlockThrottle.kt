package com.keepasskey.app.security

import android.content.Context
import android.os.SystemClock
import com.keepasskey.app.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import com.keepasskey.app.coroutines.guardedScope

/**
 * 主密码解锁失败节流记录（ISSUE-P1-04 / ZT-04）。
 *
 * @param failureCount        连续失败次数（成功解锁后归零）
 * @param lockoutUntilEpochMs 锁定截止时间戳（**墙钟** epoch millis，`0` 表示当前未锁定）。
 *                            墙钟是进程死亡后唯一可落盘表述的时间参照；本进程内的倒计时
 *                            不以它逐次计算，见 [UnlockThrottleManager] 的时间语义。
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
        /** 距锁定解除的剩余毫秒数（恒 > 0；本进程内由 [ThrottleClock.monotonicMillis] 推进） */
        val remainingMs: Long
    ) : ThrottleGate
}

/**
 * 主密码解锁失败节流存储抽象。
 *
 * 生产环境使用 [SharedPrefsUnlockThrottleStore]（跨进程重启持久化，杜绝「杀进程即重置计数」
 * 的绕过路径）；JVM 单测注入内存实现。持久化内容仅为失败计数与**墙钟**锁定截止时间戳，
 * **不含任何主密码明文**；该时间戳只用于跨进程恢复，进程内倒计时见 [UnlockThrottleManager]。
 */
interface UnlockThrottleStore {
    fun read(databaseId: String): UnlockThrottleRecord
    fun write(databaseId: String, record: UnlockThrottleRecord)
    fun reset(databaseId: String)
}

/**
 * [UnlockThrottleStore] 的 SharedPreferences 实现：计数与锁定截止落盘，
 * 卸载应用或清除数据前持久有效，杜绝「杀进程即重置计数」的绕过路径。
 *
 * ISSUE-P1-277（PD-46）：原 ISSUE-P3-54 的 Keystore HMAC 完整性层与 ISSUE-P2-45 的
 * 存在性标记**整体移除**——该层目标对手（本地文件级写者）在本机上要么不存在
 * （`allowBackup="false"` + 数据提取规则九域全 exclude 封死无代码执行的写路径），
 * 要么升级为同 UID / root（可直读 `filesDir` 下的 `*.kdbx` 离线爆破，节流对其本就无意义），
 * 且其 MAC 载荷无新鲜性来源（回写旧低计数组即可复位）、Keystore 故障时落入
 * 「每次 gate 重锁 30 分钟」的不可自愈循环。对在威胁模型内的对手零增量、
 * 反而引入独有失效面，故按「对模型内对手是否有增量」统一判据裁定移除。
 * 节流状态可被文件级删除复位的残余面登记于 `已知工程限界.md` §33。
 */
@Singleton
class SharedPrefsUnlockThrottleStore @Inject constructor(
    @ApplicationContext context: Context
) : UnlockThrottleStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(databaseId: String): UnlockThrottleRecord {
        val count = prefs.getInt(keyCount(databaseId), 0)
        val lockUntil = prefs.getLong(keyLock(databaseId), 0L)
        return UnlockThrottleRecord(count, lockUntil)
    }

    override fun write(databaseId: String, record: UnlockThrottleRecord) {
        prefs.edit()
            .putInt(keyCount(databaseId), record.failureCount)
            .putLong(keyLock(databaseId), record.lockoutUntilEpochMs)
            .apply()
    }

    override fun reset(databaseId: String) {
        // 成功解锁：清零计数与锁定截止（键移除后 read 回落默认零值记录）
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
 * 重试节流运行时配置（ISSUE-P3-68：用户可配置性）。
 *
 * **两套默认值不得混谈**（`ISSUE-P3-579` 口径收敛，2026-10-11）：
 * ① **用户设置的出厂默认 = `false`（关闭）**——2026-09-12 用户裁决「出厂关闭，需要暴力破解防护的用户
 *    可在设置页显式开启」，承载于 `UserSettings.unlockThrottleEnabled` / `SettingsRepository`
 *    （登记 `PD-84`，留痕批次 §29）；
 * ② 本类**无参构造**的 `enabled = true` 是**未接配置源 / 冷启动窗口的 fail-closed 安全回落**，
 *    **不是**出厂设置：它只在设置流抵达前生效（见 [UnlockThrottleConfigProvider]），抵达后一律按用户偏好
 *    收敛（出厂未动设置 ⇒ 收敛为关闭）。
 * 两者方向一致——出厂默认由用户裁决决定，回落值只保证「用户偏好尚未读到」这一瞬不放松。
 *
 * @param enabled      节流总开关；`false` = 用户显式退出重试锁定（`gate` 放行、失败不再退避）。
 *                     无参构造默认 `true`（安全回落，见上；出厂用户设置为 `false`）；
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
 * **冷启动窗口回落值 = 安全默认「开启 + 30 分钟封顶」**（`ISSUE-P3-579` 收口，2026-10-11）：
 * 设置流抵达前读不到用户偏好，此时唯一不放松的选择是**按既有锁定截止执行**——已开启节流的用户在锁定期内
 * 强杀进程重启，不再于该短窗内被直接放行（旧实现回落 `enabled = false`，即条目所指的绕过窗口）。
 * 设置流抵达后一律按用户偏好收敛：出厂未动设置 ⇒ `UserSettings.unlockThrottleEnabled = false` ⇒ 收敛为关闭
 * （该出厂默认出自 2026-09-12 用户裁决，登记 `PD-84`）。
 * 回落值**不改变出厂默认、也不改变未动设置用户的闸门结果**：出厂关闭态下
 * [UnlockThrottlePolicy.backoffMillisFor] 恒返回 `0`，失败登记恒写 `lockoutUntilEpochMs = 0`
 * ⇒ 已落盘记录不含锁定截止 ⇒ 即便回落值为「开启」，[UnlockThrottleManager.gate] 读到的仍是「未锁定 ⇒ 放行」。
 */
@Singleton
class UnlockThrottleConfigProvider @Inject constructor(
    settingsRepository: SettingsRepository
) : ThrottleConfigSource {

    /** 冷启动初值：设置流抵达前按安全默认执行（见类 KDoc），抵达后由下方 collect 覆写为用户偏好 */
    @Volatile
    override var current: ThrottleConfig = ThrottleConfig(enabled = true)
        private set

    private val scope = guardedScope(Dispatchers.Default, "UnlockThrottle")

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
 * 节流时间源（ENG-20261028-37）：把「跨进程持久化的截止」与「进程内的倒计时」拆给两个
 * 语义不同的时钟，避免系统调时改变用户实际要等待的时长。
 *
 * - [wallMillis]：**墙钟**，可被用户或网络时间调整。只用于写入与首次读取持久化锁定截止
 *   （进程死亡后没有任何单调参照可以落盘，墙钟是唯一可表述的形式）；
 * - [monotonicMillis]：**单调时钟**，进程存活期间只前进、不随调时回退。生产实现取
 *   `SystemClock.elapsedRealtime()`（CLOCK_BOOTTIME：计入设备休眠，锁屏与 doze 期间照常推进，
 *   不会因为设备休眠把锁定「冻住」而变相延长等待）；[Host] 只用于 JVM 单测与非 Android
 *   环境（`System.nanoTime()` 不计休眠，作为测试近似）。
 *
 * 声明为 `open` 且带 `@Inject` 空构造，是为了让 JVM 单测用子类替换时间源而不必新增 DI 绑定；
 * 生产注入的即本默认实现。
 */
@Singleton
open class ThrottleClock @Inject constructor() {

    /** 墙钟（epoch millis）：仅用于持久化锁定截止的写入与本进程首次换算 */
    open fun wallMillis(): Long = System.currentTimeMillis()

    /** 单调时钟（millis）：本进程内锁定倒计时的唯一推进依据 */
    open fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

    companion object {
        /** 无 Android 运行库时的宿主实现（JVM 单测缺省值，勿在生产构造） */
        val Host: ThrottleClock = object : ThrottleClock() {
            override fun wallMillis(): Long = System.currentTimeMillis()
            override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
        }
    }
}

/**
 * 主密码解锁失败节流管理器（ISSUE-P1-04 / ZT-04）。
 *
 * 职责单一：维护「连续失败计数 + 渐进退避锁定」的状态机，向 [com.keepasskey.app.ui.screens.unlock.UnlockViewModel]
 * 暴露三个原子操作——解锁前闸门 [gate]、失败登记 [registerFailure]、成功重置 [registerSuccess]。
 * 主密码擦除等敏感数据治理由 ViewModel 负责，本类不接触任何主密码明文。
 *
 * ## 时间语义（ENG-20261028-37）
 *
 * - **跨进程 / 重启**：锁定截止以墙钟时间戳持久化（[UnlockThrottleStore]），进程重启后
 *   仍按该截止继续节流——「杀进程即重置」不是本机制的绕过路径。合理时长上限即用户所选
 *   封顶（合法域 [UnlockThrottleConfigProvider.MIN_LOCKOUT_SECONDS,
 *   UnlockThrottleConfigProvider.MAX_LOCKOUT_SECONDS]）。
 * - **进程内**：本进程第一次观测到某库的锁定状态时，用墙钟把截止换算成一个单调截止点并记住它
 *   （[monotonicDeadlines]）；此后剩余等待只由 [ThrottleClock.monotonicMillis] 推进。
 *   锁定期内把系统时间往前调**不会**提前解锁，往后调**不会**加长等待。
 * - **调时上限**：换算时的剩余时长按「该失败次数在当前策略下可能的最长退避」截断
 *   （[UnlockThrottlePolicy.backoffMillisFor]）。持久截止只可能由本机制以该时长写出，
 *   因此截断只会吃掉进程死亡期间墙钟被回拨造成的虚增时长，不会缩短任何真实锁定。
 * - **已知边界**：进程已死亡期间没有单调参照可落盘，墙钟被**前移**仍会让重启后的持久等待
 *   提前结束（进程存活期间的调时已被单调时钟隔离）。该边界与「本机制不抵御离线爆破」
 *   同属作用域声明，不得在文案中宣称跨重启的严格时限保证。
 *
 * 边界如实声明：本机制只约束**经界面重试**的解锁尝试，作用域是应用进程内的状态。
 * 它不抵御离线爆破（直接对 `.kdbx` 文件尝试口令），清除应用数据亦可复位——
 * 该残余面按 `PD-46` 裁定登记于 `已知工程限界.md`，界面文案与安全说明都不得把
 * 本机制描述成对离线攻击的防护。
 *
 * ISSUE-P3-68：运行时配置经 [UnlockThrottleConfigProvider] 注入——总开关关闭时 `gate` 放行、
 * 失败不再退避；封顶时长随用户自定义。`configProvider` 可空（缺省 [ThrottleConfig] 安全默认），
 * 仅用于 JVM 单测零改动注入；生产 DI 恒注入真实实例。
 */
@Singleton
class UnlockThrottleManager @Inject constructor(
    private val store: UnlockThrottleStore,
    private val configProvider: ThrottleConfigSource? = null,
    // 缺省仅见于 JVM 单测；生产 DI 注入带 elapsedRealtime 的默认实现
    private val clock: ThrottleClock = ThrottleClock.Host
) {

    /**
     * 本进程各库的**单调**锁定截止点（刻度为 [ThrottleClock.monotonicMillis]）。
     *
     * 只在首次观测时由持久化墙钟截止换算而来，之后本进程内的倒计时不再读墙钟；
     * 过期条目同样保留（记为已到期），避免「到期后墙钟被回拨」把同一进程内的锁定重新立起来。
     * 与 [store] 的读写一并在 [gate]/[registerFailure]/[registerSuccess] 的监视器下进行。
     */
    private val monotonicDeadlines = HashMap<String, Long>()

    /**
     * 当前生效配置（source 缺省仅见于 JVM 单测，回落 [ThrottleConfig] 无参默认 = fail-closed 安全默认：
     * 开启 + 30 分钟封顶；用户设置的**出厂默认是关闭**，见 `PD-84`——两套默认的区分见 [ThrottleConfig] KDoc）。
     */
    private val config: ThrottleConfig
        get() = configProvider?.current ?: ThrottleConfig()

    /**
     * ISSUE-P2-355 AC②：当前是否执行失败锁定。
     * UI 仅在为 true 时呈现「剩余 N 次尝试」——节流被用户显式关闭时不存在锁定上限，
     * 任何「剩余尝试」提示都是对用户的假承诺（失败计数在关闭态仍会累加，不可据其推算）。
     */
    val isLockoutEnforced: Boolean
        get() = config.enabled

    /**
     * 解锁前闸门：锁定期内返回 [ThrottleGate.Locked]（fail-closed），否则 [ThrottleGate.Allowed]。
     * 调用方拿到 Locked 时**必须拒绝解锁**，不得进入 KDF/解密流程。
     *
     * ISSUE-P3-68：节流开关关闭时忽略既有锁定截止直接放行（放行不注销计数）。
     */
    @Synchronized
    fun gate(databaseId: String): ThrottleGate {
        val record = store.read(databaseId)
        // ISSUE-P3-68：用户显式退出重试节流——既有锁定截止不再生效（放行不注销计数）
        if (!config.enabled) return ThrottleGate.Allowed(record.failureCount)
        val remaining = remainingLockedMillis(databaseId, record)
        return if (remaining > 0L) {
            ThrottleGate.Locked(record.failureCount, remaining)
        } else {
            ThrottleGate.Allowed(record.failureCount)
        }
    }

    /**
     * 登记一次「凭据错误」失败：累加计数并按 [UnlockThrottlePolicy] 重算锁定截止。
     * 持久化写出墙钟截止，本进程同时登记对应的单调截止点。
     * 返回更新后的闸门状态，供 UI 即时呈现是否进入锁定及剩余时长。
     *
     * 注意：仅认证失败（主密码/密钥不匹配）应计入，IO/文件损坏等非认证错误不应调用本方法，
     * 以免瞬时故障误锁用户——该分流由调用方（ViewModel）负责。
     */
    @Synchronized
    fun registerFailure(databaseId: String): ThrottleGate {
        val record = store.read(databaseId)
        val newCount = record.failureCount + 1
        val backoff = UnlockThrottlePolicy.backoffMillisFor(newCount, config)
        if (backoff <= 0L) {
            store.write(databaseId, UnlockThrottleRecord(newCount, 0L))
            monotonicDeadlines.remove(databaseId)
            return ThrottleGate.Allowed(newCount)
        }
        store.write(databaseId, UnlockThrottleRecord(newCount, clock.wallMillis() + backoff))
        monotonicDeadlines[databaseId] = clock.monotonicMillis() + backoff
        return ThrottleGate.Locked(newCount, backoff)
    }

    /** 成功解锁：清零计数与锁定状态（本进程的单调截止点一并作废）。 */
    @Synchronized
    fun registerSuccess(databaseId: String) {
        store.reset(databaseId)
        monotonicDeadlines.remove(databaseId)
    }

    /**
     * 锁定期剩余毫秒数（`<= 0` 表示未在锁定）。语义见类 KDoc「时间语义」：
     * 本进程已登记过该库 ⇒ 只读单调时钟；首次观测 ⇒ 用墙钟换算一次并截断到策略上限。
     */
    private fun remainingLockedMillis(databaseId: String, record: UnlockThrottleRecord): Long {
        val monotonicNow = clock.monotonicMillis()
        monotonicDeadlines[databaseId]?.let { deadline ->
            return (deadline - monotonicNow).coerceAtLeast(0L)
        }
        val remaining = persistedRemainingMillis(record)
        if (remaining <= 0L) return 0L
        monotonicDeadlines[databaseId] = monotonicNow + remaining
        return remaining
    }

    /**
     * 首次观测持久锁定时算出「还需等待多少毫秒」：以墙钟比对，并按该失败次数在当前策略下的
     * 最长退避截断——策略上限即写出该截止时能使用的时长，超出部分只可能是进程死亡期间
     * 墙钟被回拨（或用户下调封顶）造成的虚增。
     */
    private fun persistedRemainingMillis(record: UnlockThrottleRecord): Long {
        if (record.lockoutUntilEpochMs <= 0L) return 0L
        val policyCeiling = UnlockThrottlePolicy.backoffMillisFor(record.failureCount, config)
        if (policyCeiling <= 0L) return 0L
        return (record.lockoutUntilEpochMs - clock.wallMillis()).coerceIn(0L, policyCeiling)
    }
}
