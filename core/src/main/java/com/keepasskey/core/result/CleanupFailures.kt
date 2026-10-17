package com.keepasskey.core.result

/**
 * 执行失败路径上的清理动作，并把清理自身抛出的异常并入主异常。
 *
 * 存在的理由：`catch (t: Throwable) { try { cleanup() } catch (e: Throwable) { t.addSuppressed(e) } }`
 * 这种嵌套写法在每个点位都要重复一遍，且会被「协程上下文中的 catch(Throwable) 必须显式处理取消」
 * 机检判为疑似吞取消——因为内层 `catch` 的兄弟分支判定会命中更近的内层 `try`。
 *
 * 本函数**不是**挂起函数，清理段按定义不含挂起点：取消不可能在清理期间从这里被观测到，
 * 因此不需要（也不应该）在此重抛 `CancellationException`。主异常——含取消——由调用方
 * 原样处理（`if (t is CancellationException) throw t` 或直接归为失败结果）。
 *
 * 清理异常与主异常是同一对象时（例如清理段原样重抛）不重复登记，避免自引用抑制。
 */
inline fun suppressCleanupFailure(primary: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
    }
}
