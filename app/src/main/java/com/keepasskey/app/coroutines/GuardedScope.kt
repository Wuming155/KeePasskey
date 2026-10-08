package com.keepasskey.app.coroutines

import com.keepasskey.core.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级长生命周期协程作用域的**统一构造入口**（`ISSUE-P3-535`，§473 复核结论 #7）。
 *
 * ## 为什么需要它
 *
 * 整改前全仓应用级 scope 都以裸 `CoroutineScope(SupervisorJob() + dispatcher)` 创建、**零**
 * `CoroutineExceptionHandler`。`SupervisorJob` 只隔离兄弟协程，**不**接管异常——逃逸到协程根的
 * 异常会直达线程默认处理器并**直接杀死进程**（§473 真机闪退即此形态）。
 * 逐点 `launchGuarded` 只能覆盖「记得包」的调用点：漏一处即复发，且**新代码不会自动继承**。
 * 故此处提供单一收口——**新增应用级 scope 一律经本工厂创建**。
 *
 * ## 与 `launchGuarded` 的分工（两者不冲突）
 *
 * - 本工厂＝**作用域级**兜底：覆盖该 scope 内**所有** `launch`（含将来新增的）；
 * - `launchGuarded`＝**调用点级**兜底：能给出面向用户的失败语义（提示 / 状态复位）。
 *
 * 调用点已处理的异常不会到达本 handler。
 *
 * ## 行为与边界
 *
 * handler 只落**脱敏**日志（异常类型 + 原异常对象经 `AppLog` 输出，不含凭据明文），随后
 * **不再上抛**——进程不因单个后台任务失败而退出，这正是本工厂存在的目的。
 * 如实声明：这会把「未知缺陷 → 进程崩溃」降级为「未知缺陷 → 日志 + 该任务静默失败」，
 * 即**崩溃信号被弱化**；因此新代码仍应在调用点给出用户可见的失败语义（优先 `launchGuarded`），
 * 本工厂是兜底而非常态路径。
 */
fun guardedScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    tag: String = "GuardedScope"
): CoroutineScope = CoroutineScope(
    SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
        AppLog.e(tag, "未捕获的协程异常（已拦截，进程不退出）：${e.javaClass.simpleName}", e)
    }
)
