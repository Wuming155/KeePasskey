package com.keepasskey.app.data.logger

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * 故障现场快照（`ISSUE-P2` C3 / B7）。
 *
 * ERROR 只有异常类型与栈帧，无法判断当时是锁定态、低内存、磁盘将满还是后台受限。
 * 本类在错误发生时补一行**只含枚举与数值**的快照：不写库名、路径、账号、UUID 或任何字段值。
 *
 * 限流：同一分钟最多一行（每 op 最多 1 次由调用方保证；跨 op 的洪泛由本限流吸收）。
 * 解释：快照只为定位环境，不承担审计职责，故放弃「每次错误都留一行」。
 */
internal object DiagnosticSnapshot {

    /** 快照行的级别与 tag（`exportText` 中可直接检索 `snapshot=`）。 */
    const val LEVEL = "INFO"
    const val TAG = "Snapshot"

    /**
     * 生成快照正文。
     *
     * @param context 生产传 Application；纯 JVM 单测传 null，此时平台相关字段输出 `?`
     * @param locked 当前库是否处于锁定态（调用方从会话状态取得）
     * @param timeZone 当前时区（仅用于日志关联跨时区问题）
     */
    fun describe(
        context: Context?,
        locked: Boolean,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val memoryMb = Runtime.getRuntime().maxMemory() / BYTES_PER_MB
        val storage = context?.let { "${safeStorageMb(it)}MB" } ?: UNKNOWN
        val powerSave = context?.let { safePowerSave(it) } ?: UNKNOWN
        val foreground = context?.let { safeForeground(it) } ?: UNKNOWN
        return buildString {
            append("snapshot=locked=").append(locked)
            append(", mem=").append(memoryMb).append("MB")
            append(", storage=").append(storage)
            append(", powerSave=").append(powerSave)
            append(", foreground=").append(foreground)
            append(", tz=").append(timeZone.id)
        }
    }

    private fun safeStorageMb(context: Context): Long = runCatching {
        StatFs(context.noBackupFilesDir.absolutePath).availableBytes / BYTES_PER_MB
    }.getOrDefault(UNKNOWN_MB)

    private fun safePowerSave(context: Context): String = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode?.toString() ?: UNKNOWN
    }.getOrDefault(UNKNOWN)

    /** 自身进程重要性 → 前台 / 后台枚举；取不到时 `?`。 */
    private fun safeForeground(context: Context): String = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            "true"
        } else {
            "false"
        }
    }.getOrDefault(UNKNOWN)

    private const val UNKNOWN = "?"
    private const val UNKNOWN_MB = -1L
    private const val BYTES_PER_MB = 1024L * 1024L
}

/**
 * 快照限流器：同一时间窗内只放行一次。
 *
 * 独立成类以便用显式时间戳验证「窗口内拒绝、窗口外放行」，不依赖真实时钟与进程级静态状态。
 */
internal class DiagnosticSnapshotThrottle(
    private val minIntervalMs: Long = 60_000L
) {

    private val lastAcceptedAt = AtomicLong(Long.MIN_VALUE)

    fun tryAcquire(nowMs: Long): Boolean {
        while (true) {
            val last = lastAcceptedAt.get()
            if (last != Long.MIN_VALUE && nowMs - last < minIntervalMs) return false
            if (lastAcceptedAt.compareAndSet(last, nowMs)) return true
        }
    }
}

/**
 * ERROR 事件 → 快照行。检查顺序：诊断开关 → 限流 → 计算 → 写入。
 *
 * 依赖全部以函数注入，避免 JVM 单测触碰平台 API；生产实例由 [DiagnosticLogBridge] 装配。
 */
internal class DiagnosticSnapshotEmitter(
    private val buffer: DebugLogBuffer,
    private val throttle: DiagnosticSnapshotThrottle = DiagnosticSnapshotThrottle(),
    private val now: () -> Long,
    private val describe: () -> String
) {

    fun onError() {
        if (!buffer.isRecordingEnabled()) return
        if (!throttle.tryAcquire(now())) return
        buffer.log(DiagnosticSnapshot.LEVEL, DiagnosticSnapshot.TAG, describe())
    }
}
