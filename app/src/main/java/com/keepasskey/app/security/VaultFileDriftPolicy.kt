package com.keepasskey.app.security

import java.io.File

/**
 * ISSUE-P2-378：已打开库文件的外部修改基线（mtime + size，双条件比对）。
 *
 * 口径（批次留痕）：
 * - **≥1 秒粒度 mtime**：文件系统 mtime 毫秒可能被截断（keepass2android 实证），
 *   比对时 mtime 差 < 1s **不**单独判漂移；size 不等即漂移。
 * - **宁可多提示不漏报**：任一条件超阈值即判漂移。
 * - **不引入常驻 FileObserver**（限界 §24：SAF 不参与监听），检测只挂「保存」与
 *   「回到前台」两时点。
 * - SAF / content:// 路径由调用方用 DocumentFile 元数据构造本基线；本地 File 直接读属性。
 *
 * 判定语义（keepassxc `811887e5` 参照）：
 * 基线漂移 ⇒ **fail-closed 中止覆盖**，不得静默整树覆盖；用户三选
 * （重载 / 走既有 `KdbxMerger` 合并 / 放弃保存）。
 */
data class VaultFileBaseline(
    val pathIdentifier: String,
    val lastModifiedMillis: Long,
    val sizeBytes: Long
) {
    companion object {
        /** mtime 比对粒度：小于 1 秒的 mtime 差视为同一时刻（毫秒截断容差） */
        const val MTIME_GRANULARITY_MILLIS = 1_000L

        /** 从本地 File 读取基线；文件不存在返回 null */
        fun fromFile(file: File): VaultFileBaseline? {
            if (!file.exists() || !file.isFile) return null
            return VaultFileBaseline(
                pathIdentifier = file.absolutePath,
                lastModifiedMillis = file.lastModified(),
                sizeBytes = file.length()
            )
        }

        /** SAF / content:// 等通道由调用方提供元数据构造 */
        fun fromMetadata(
            pathIdentifier: String,
            lastModifiedMillis: Long,
            sizeBytes: Long
        ): VaultFileBaseline = VaultFileBaseline(
            pathIdentifier = pathIdentifier,
            lastModifiedMillis = lastModifiedMillis,
            sizeBytes = sizeBytes
        )
    }
}

/**
 * 外部修改漂移判定（纯函数，JVM 可测）。
 */
object VaultFileDriftPolicy {

    /**
     * 判定 [baseline] 与当前 [current] 是否发生外部漂移。
     *
     * - current 为 null（文件已消失 / 元数据不可读）⇒ 漂移（宁可提示）
     * - size 不等 ⇒ 漂移
     * - mtime 差 ≥ [VaultFileBaseline.MTIME_GRANULARITY_MILLIS] ⇒ 漂移
     * - 其余 ⇒ 未漂移
     */
    fun isDrifted(baseline: VaultFileBaseline?, current: VaultFileBaseline?): Boolean {
        if (baseline == null) return false
        if (current == null) return true
        if (baseline.sizeBytes != current.sizeBytes) return true
        val mtimeDelta = kotlin.math.abs(current.lastModifiedMillis - baseline.lastModifiedMillis)
        return mtimeDelta >= VaultFileBaseline.MTIME_GRANULARITY_MILLIS
    }

    /**
     * 保存前校验入口：返回 true 表示存在漂移、调用方须中止覆盖。
     * 路径标识不一致（用户换库 / 通道漂移）也按漂移处理。
     */
    fun shouldAbortSave(
        baseline: VaultFileBaseline?,
        current: VaultFileBaseline?
    ): Boolean {
        if (baseline == null) return false
        if (current != null && baseline.pathIdentifier != current.pathIdentifier) return true
        return isDrifted(baseline, current)
    }
}

/**
 * 用户三选（重载 / 合并 / 放弃）枚举——UI 与会话层共用。
 */
enum class ExternalModificationChoice {
    /** 放弃本次覆盖，保留磁盘版本；内存树保持打开态或引导关闭 */
    ABANDON_SAVE,

    /** 重载磁盘版本（丢弃内存未落盘改动） */
    RELOAD_FROM_DISK,

    /** 走既有 KdbxMerger 三方/双向合并后继续保存 */
    MERGE_AND_SAVE
}
