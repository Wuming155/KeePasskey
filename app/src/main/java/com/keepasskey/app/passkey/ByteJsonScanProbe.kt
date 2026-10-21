package com.keepasskey.app.passkey

/**
 * 仅同模块测试可见的**扫描工作缓冲观察器**（`ISSUE-P3-647`）。
 *
 * 为什么需要：`ByteSink` 扩容会遗弃旧数组，`wipe()` 之后缓冲内容为零——两者在返回值上
 * 完全看不出来，只有保留**数组引用**才能断言「旧代次也已清零」。
 *
 * 两类缓冲的所有权不同，故分成两个清单（测试据此断言各自的终态）：
 * - [sinkBuffers]：`ByteSink` 的**工作缓冲**（初始容量 + 每次扩容后的新缓冲）。归扫描器所有，
 *   `scan` 无论成功还是失败都已清零——任何路径都不该出现非零字节；
 * - [stringCopies]：交给 AST 的字符串**副本**（`ByteJson.Str.bytes`）。`scan` 成功时所有权
 *   随树交给调用方，只有 [PasskeyCxfReader.read] 在 `finally` 里递归清零它。
 *
 * 纪律：由调用方逐次显式传入（生产路径传 null），**禁止**做成全局/单例观察者——
 * 那会把一次解析的明文数组钉在整个进程生命周期里，比原缺陷更糟。观察者只许保存引用。
 */
internal open class ByteJsonScanProbe {

    /** `ByteSink` 的每个缓冲代次，按分配顺序；`scan` 返回时全部应为零。 */
    val sinkBuffers = ArrayList<ByteArray>()

    /** 交给 AST 的字符串副本；`PasskeyCxfReader.read` 返回时全部应为零。 */
    val stringCopies = ArrayList<ByteArray>()
}
