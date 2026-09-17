package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整性重扫参数的**不得放宽守卫**（ISSUE-P3-152 ②）。
 *
 * 背景：`RuntimeIntegrityDetector` 以 [RuntimeIntegrityDetector.LIVE_RESCAN_INTERVAL_MS]
 * 周期重扫（ISSUE-P2-63），并由 [RuntimeIntegrityDetector.SNAPSHOT_STALE_AFTER_MS] 定义
 * 「快照陈旧即 fail-closed（保守策略）」的容忍上限。两者**都是安全参数**：
 * 前者是「附加注入框架后多久被抓到」的上界，后者是「连丢多少次重扫即拒绝为高价值通道放行」。
 *
 * ISSUE-P3-152 复核（2026-09-17）结论：**不为省电放宽**——省下的只是每 30 s 一次的文件探测
 * （IO 线程），与判定新鲜度不成比例；该窗口作为**已接受残余风险**登记于
 * `docs/architecture/已知工程限界.md` §3.5。本用例把该结论钉成可执行契约：
 * 任何放宽（调大周期 / 调大陈旧窗口）都会立即变红，收紧则不受限制。
 */
class RuntimeIntegrityRescanContractTest {

    @Test
    fun `重扫间隔不得放宽超过 30 秒`() {
        assertTrue(
            "重扫间隔是「注入框架多久被抓到」的上界，不得为省电放宽（当前 ${RuntimeIntegrityDetector.LIVE_RESCAN_INTERVAL_MS} ms）",
            RuntimeIntegrityDetector.LIVE_RESCAN_INTERVAL_MS <= 30_000L
        )
    }

    @Test
    fun `快照陈旧窗口不得放宽超过 120 秒`() {
        assertTrue(
            "陈旧窗口是 fail-closed 的容忍上限，不得为省电放宽（当前 ${RuntimeIntegrityDetector.SNAPSHOT_STALE_AFTER_MS} ms）",
            RuntimeIntegrityDetector.SNAPSHOT_STALE_AFTER_MS <= 120_000L
        )
    }

    @Test
    fun `陈旧窗口至少覆盖 4 个重扫周期`() {
        // 陈旧窗口与重扫间隔的关系是设计意图的一部分（「容忍 4 次周期重扫缺失」）：
        // 若把窗口收到小于一个周期，非 suspend 门控会在正常重扫节奏下持续 fail-closed。
        assertTrue(
            "陈旧窗口不得小于重扫间隔的 4 倍（设计意图：容忍 4 次重扫缺失）",
            RuntimeIntegrityDetector.SNAPSHOT_STALE_AFTER_MS >=
                4 * RuntimeIntegrityDetector.LIVE_RESCAN_INTERVAL_MS
        )
        assertEquals(
            "窗口与间隔的既有比例（4 倍）",
            RuntimeIntegrityDetector.LIVE_RESCAN_INTERVAL_MS * 4,
            RuntimeIntegrityDetector.SNAPSHOT_STALE_AFTER_MS
        )
    }
}
