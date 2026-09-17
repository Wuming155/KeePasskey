package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.data.repository.EntryTotpSnapshot
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.otp.OtpEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [VaultListTotpTracker] 的节拍与重算纪律（ISSUE-P2-89 / ISSUE-P2-90 / ISSUE-P3-158）。
 *
 * 本用例覆盖五条**可判别的**契约（每条都能被对应的回退改动弄红）：
 * 1. **订阅驱动**：无人订阅时既不 tick 也不请求验证码——回退到「init 期常驻 start()」
 *    即 `batchCalls` 非 0 而必红；
 * 2. **周期内零重算**：连续 3 拍（周期内）`calculateEntryTotps` 调用次数不增加——
 *    回退到「每拍逐条重算」即必红；
 * 3. **跨周期必重算**：时钟跨过周期边界后调用次数 +1——回退到「干脆不刷新」即必红；
 * 4. **周期取条目自身值（ISSUE-P3-158）**：`period = 10` 的条目必须在**自身**边界重算——
 *    回退到「按全局 30 秒网格判翻转」即漏拍（列表显示过期验证码）；
 * 5. **不产生无谓重算**：`period = 60` 的条目在 30 秒时刻**不得**重算——旧网格判据在此多算一次。
 *
 * 时钟与调度器均由用例注入：前者使周期边界可被确定性地跨过（不依赖真实墙钟），
 * 后者使秒级节拍落在虚拟时间上（不 sleep、不依赖真实线程）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultListTotpTrackerTest {

    /** 计数型仓库：只关心「批量取码被调用了几次、请求了哪些 id」 */
    private class CountingRepository : VaultRepository by FakeVaultRepository() {

        var batchCalls = 0
            private set

        var lastRequestedIds: List<String> = emptyList()
            private set

        override suspend fun calculateEntryTotps(entryIds: List<String>): Map<String, EntryTotpSnapshot> {
            batchCalls++
            lastRequestedIds = entryIds
            return entryIds.associateWith {
                EntryTotpSnapshot(code = CODE, periodSeconds = 30, digits = 6, algorithm = "SHA1")
            }
        }

        private companion object {
            const val CODE = "123456"
        }
    }

    private fun totpEntry(id: String, periodSeconds: Int = 30) = UiVaultEntry(
        id = id,
        title = "条目$id",
        username = "user",
        url = "https://example.com",
        totpCode = "123456",
        totpPeriod = periodSeconds
    )

    /** 窄通道刻度 → 指定周期下的剩余秒数（生产侧列表徽标用同一函数换算） */
    private fun VaultListTotpTracker.remainingSeconds(periodSeconds: Int = 30): Int =
        OtpEngine.getRemainingSeconds(
            timestampMillis = nowSeconds.value * 1000L,
            periodSeconds = periodSeconds
        )

    /** 推进指定秒数并跑完虚拟时钟上的待处理工作 */
    private fun TestScope.advanceSeconds(clock: () -> Unit, seconds: Int) {
        repeat(seconds) {
            clock()
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    private fun TestScope.trackerFor(
        repository: VaultRepository,
        entries: () -> List<UiVaultEntry>,
        clock: () -> Long
    ) = VaultListTotpTracker(
        vaultRepository = repository,
        // 用 backgroundScope：其生命周期由用例框架在收尾时取消，
        // 否则 WhileSubscribed 的 stateIn 常驻协程会让 runTest 报「未完成的协程」
        scope = backgroundScope,
        currentEntries = entries,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        nowMillis = clock
    )

    @Test
    fun `无人订阅时不计时也不重算验证码`() = runTest {
        val repository = CountingRepository()
        var clock = 0L
        trackerFor(repository, { listOf(totpEntry("1")) }, { clock })

        // 时间推进 5 秒：若节拍是常驻的（旧实现），这里必然产生多次取码请求
        clock += 5_000
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(0, repository.batchCalls)
    }

    @Test
    fun `周期内逐秒推进不得重算验证码，跨周期才重算`() = runTest {
        val repository = CountingRepository()
        var clock = 0L
        // 列表中混入一条无 TOTP 的条目：批量请求只应包含带 TOTP 的 id
        val tracker = trackerFor(
            repository,
            { listOf(totpEntry("1"), totpEntry("2").copy(totpCode = null)) },
            { clock }
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.nowSeconds.collect {}
        }
        runCurrent()

        // 首拍之前：尚无任何取码（订阅本身不等于取码）
        assertEquals(0, repository.batchCalls)
        assertEquals(30, tracker.remainingSeconds())

        // 第 1 拍：开屏即有码，批量刷新一次
        clock += 1_000
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, repository.batchCalls)
        assertEquals(listOf("1"), repository.lastRequestedIds)
        assertEquals(29, tracker.remainingSeconds())

        // 周期内再走 3 拍：倒计时必须继续推进，但**不得**重算验证码
        repeat(3) {
            clock += 1_000
            advanceTimeBy(1_000)
            runCurrent()
        }
        assertEquals("周期内每拍重算即为 ISSUE-P2-90 的缺陷形态", 1, repository.batchCalls)
        assertEquals(26, tracker.remainingSeconds())

        // 跨周期（时钟跳到下一周期起点，剩余秒数回跳）：必须重算出一个新周期之码
        clock = 30_000
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, repository.batchCalls)
        assertEquals(30, tracker.remainingSeconds())
    }

    @Test
    fun `周期不等于 30 的条目按自身周期重算，不得漏在全局 30 秒网格上`() = runTest {
        val repository = CountingRepository()
        var clock = 0L
        // period = 10：自身边界在 10 / 20 / 30 秒，与 30 秒网格**不同相位**
        val tracker = trackerFor(repository, { listOf(totpEntry("1", periodSeconds = 10)) }, { clock })

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.nowSeconds.collect {}
        }
        runCurrent()
        assertEquals("订阅本身不等于取码（首拍尚未到来）", 0, repository.batchCalls)

        // 第 1 拍：开屏即有码 ⇒ 刷新一次；第 2..9 拍均在周期内，不得重算
        advanceSeconds({ clock += 1_000 }, 9)
        assertEquals("周期内每拍重算即为 ISSUE-P2-90 的缺陷形态", 1, repository.batchCalls)

        // 第 10 拍：跨过 10 秒周期边界 ⇒ 必须重算（旧「30 秒网格」判据在此**不刷新**）
        advanceSeconds({ clock += 1_000 }, 1)
        assertEquals("period = 10 的条目必须在自身边界重算，否则列表显示过期验证码", 2, repository.batchCalls)

        assertEquals("倒计时按条目自身周期换算", 10, tracker.remainingSeconds(periodSeconds = 10))
    }

    @Test
    fun `周期 60 的条目在 30 秒时刻不得重算`() = runTest {
        val repository = CountingRepository()
        var clock = 0L
        val tracker = trackerFor(repository, { listOf(totpEntry("1", periodSeconds = 60)) }, { clock })

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            tracker.nowSeconds.collect {}
        }
        runCurrent()
        assertEquals("订阅本身不等于取码（首拍尚未到来）", 0, repository.batchCalls)

        // 走到 30 秒（含首拍那次刷新）：旧「全局 30 秒网格」判据会在此多算一次
        // （此拍并非该 60 秒条目的周期边界）
        advanceSeconds({ clock += 1_000 }, 30)
        assertEquals("30 秒时刻不是 60 秒条目的周期边界，不得重算", 1, repository.batchCalls)
        assertEquals("60 秒周期下剩余 30 秒", 30, tracker.remainingSeconds(periodSeconds = 60))

        // 第 31..60 拍：跨过 60 秒周期边界 ⇒ 恰好重算一次
        advanceSeconds({ clock += 1_000 }, 30)
        assertEquals(2, repository.batchCalls)
        assertEquals(60, tracker.remainingSeconds(periodSeconds = 60))
    }
}
