package com.keepasskey.sync.s3

import okhttp3.Response
import java.util.Date

/**
 * TASK-45（FINDINGS P2-14）时钟偏移协作单元：承载服务端时钟偏移状态与 SigV4 skew 自愈判定。
 *
 * 偏移语义为「服务端时间 - 本地时间」（毫秒），默认 0 = 尚未探测（fail-closed：
 * 不补偿、维持本地时间签名，与服务端无关时行为与旧版一致）。
 *
 * 实例可能被并发上传/下载共享（SyncCoordinator 单次同步周期内复用同一 Provider 实例），
 * 故偏移字段以 `@Volatile` 发布。
 */
internal class S3ClockSkewGuard(
    initialClockOffsetMillis: Long,
    private val clockOffsetUpdater: ((Long) -> Unit)?
) {

    // 运行时时钟偏移（server - local）。每次响应携带有效 Date 头即刷新，吸收 NTP 校正漂移。
    @Volatile
    var clockOffsetMillis: Long = initialClockOffsetMillis
        private set

    // 上次已上报持久化的偏移量：仅当变化 ≥ 1s 才回调（HTTP Date 秒级精度，
    // 抑制相邻请求亚秒抖动导致的重复刷盘）。
    @Volatile
    private var reportedClockOffsetMillis: Long = initialClockOffsetMillis

    /**
     * 签名时间戳取「本地时间 + 已探测时钟偏移」。偏移为 0（未探测）时
     * 退化为本地时间，与旧版行为一致（fail-closed）。
     */
    fun signingDate(): Date = Date(System.currentTimeMillis() + clockOffsetMillis)

    /**
     * 据响应 `Date` 头刷新运行时时钟偏移（服务端时间 - 本地时间）。
     *
     * 返回是否成功刷新：
     * - 响应携带可解析的 RFC 1123 `Date` 头 → 更新内存偏移并（变化 ≥ 1s 时）回调
     *   [clockOffsetUpdater] 持久化（尽力而为，失败静默容忍，仅丢失跨进程记忆）；
     * - 无 `Date` 头 / 解析失败 → 返回 false，**fail-closed**：不补偿、保持现状。
     */
    fun refreshFrom(response: Response): Boolean {
        val dateHeader = response.header(HEADER_DATE) ?: return false
        val serverMillis = S3HttpDateCodec.parse(dateHeader)
        if (serverMillis <= 0L) return false
        val newOffset = serverMillis - System.currentTimeMillis()
        clockOffsetMillis = newOffset
        // 节流：HTTP Date 头秒级精度，相邻请求亚秒抖动不触发重复刷盘
        if (Math.abs(newOffset - reportedClockOffsetMillis) >= CLOCK_OFFSET_PERSIST_THRESHOLD_MS) {
            reportedClockOffsetMillis = newOffset
            runCatching { clockOffsetUpdater?.invoke(newOffset) }
        }
        return true
    }

    /**
     * 判断本次请求是否因时钟偏斜被 S3 拒绝（`403 RequestTimeTooSkewed`，
     * SigV4 服务端容限 ±15 分钟）。**双信号任一命中即判定**：
     * - 响应主体含偏斜错误码标记（仅 GET/PUT/DELETE 等带实体的方法可靠）——
     *   [Response.peekBody] 探测不消费实体，仅 403 读取前 4 KiB，其余状态码直接短路；
     * - 响应刷新后的时钟偏移相对本次签名时发生了超过容限量级的跳变（对无实体的
     *   HEAD 请求同样有效——HTTP 规范禁止 HEAD 携带错误主体，只能依赖 Date 头）。
     *
     * 无有效 `Date` 头（无法计算偏移）或两种信号均不满足时返回 false（fail-closed）。
     */
    fun isSkewRejection(
        response: Response,
        offsetBeforeSigningMillis: Long,
        offsetAfterRefreshMillis: Long
    ): Boolean {
        if (response.code != HTTP_FORBIDDEN) return false
        val bodyMarked = runCatching { response.peekBody(PEEK_BODY_BYTES).string().contains(REQUEST_TIME_TOO_SKEWED) }
            .getOrDefault(false)
        val offsetJump = Math.abs(offsetAfterRefreshMillis - offsetBeforeSigningMillis)
        // 请求被拒仅因签名时间偏离服务端超过 15 分钟；此处跳变必然同量级。阈值取 14 分钟
        // 吸收 HTTP Date 秒级截断与网络 RTT 造成的亚分钟偏差，杜绝在正常时钟下误判重试
        val offsetJumpedSkewScale = offsetJump > CLOCK_SKEW_RETRY_THRESHOLD_MS
        return bodyMarked || offsetJumpedSkewScale
    }

    companion object {
        // TASK-45：时钟偏移持久化节流阈值——HTTP Date 头秒级精度，变化 < 1s 不回调刷盘
        private const val CLOCK_OFFSET_PERSIST_THRESHOLD_MS = 1000L

        // TASK-45：skew 判定偏移跳变阈值——SigV4 服务端容限 ±15 分钟，取 14 分钟吸收
        // HTTP Date 秒级截断与网络 RTT 的亚分钟偏差（正常时钟下绝无如此量级跳变）
        private const val CLOCK_SKEW_RETRY_THRESHOLD_MS = 14L * 60 * 1000

        // S3 时钟偏斜错误码主体标记（SigV4 ±15 分钟容限，设备时钟偏移超限时返回）
        private const val REQUEST_TIME_TOO_SKEWED = "RequestTimeTooSkewed"

        private const val HEADER_DATE = "Date"
        private const val HTTP_FORBIDDEN = 403
        private const val PEEK_BODY_BYTES = 4096L
    }
}
