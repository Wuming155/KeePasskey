package com.keepasskey.app.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 默认 tick 周期：1 秒，匹配 TOTP 30 秒窗口的秒级倒计时精度 */
const val DEFAULT_TICK_PERIOD_MS = 1_000L

/**
 * 通用周期性 tick 冷流 —— 官方 `flow { while { emit(); delay() } }` 生产者范例形态
 * （对齐 developer.android.com/kotlin/flow 的 NewsRemoteDataSource 范例）。
 *
 * P1 整改背景：TOTP 秒级倒计时此前在 VaultListViewModel / EntryDetailViewModel /
 * AuthenticatorViewModel 三处各写一份 `while (isActive) { delay(...); ... }` 无限循环，存在三重问题：
 * 1. 三份独立 delay 起点互不对齐，周期翻转（剩余秒数回跳）判定会错位，同一个 30 秒窗口
 *    在不同页面可能差出 1 秒；
 * 2. 手写 `while (isActive)` 依赖真实延时推进，无法被 kotlinx-coroutines-test 的虚拟时钟
 *    （`runTest` + `advanceTimeBy`）精确控制，逻辑不可测；
 * 3. 每处都需自行维护 Activity 之外不被清理的生命周作用域。
 *
 * 收敛为单一冷流后的收益：
 * - 冷流语义：随订阅启动、随协程作用域取消，无需手工判 isActive；
 * - 所有订阅者共享同一发射时刻，翻转判定收敛到一处；
 * - 可被虚拟时钟精确推进，具备可测试性。
 *
 * **发射时序刻意选择「先 delay 后 emit」**：与被替换的手写 `while (isActive) { delay(); ... }`
 * 语义逐位一致。若反过来「先 emit 后 delay」，订阅者会在 collect 启动的同一调度节拍内立刻
 * 收到第一次发射——而 ViewModel 中常存在声明于 `init` 之后的属性（如 uiState），
 * init 块内立即消费会造成「属性未初始化」NPE（已在 VaultListViewModel 实测命中）。
 *
 * @param periodMs 发射周期（毫秒）；首次发射同样延迟该时长
 */
fun tickerFlow(periodMs: Long = DEFAULT_TICK_PERIOD_MS): Flow<Unit> = flow {
    while (true) {
        delay(periodMs)
        emit(Unit)
    }
}
