package com.keepasskey.app.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-P2-192 余量第 4 项：`WorkManager` 周期同步的**设备侧**调度与执行用例。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：
 *   1. `PeriodicSyncScheduler.reschedule` 的真实 WorkManager 注册 / 取消（设备上
 *      `WorkManager.getInstance` 初始化链、唯一任务名、周期请求结构真实成立）；
 *   2. `PeriodicSyncWorker.doWork()` 在设备上真实执行成功——该执行体经 **Hilt EntryPoint**
 *      解析 `SyncCoordinator`，顺带在设备上证明 SingletonComponent 图的 EntryPoint 子图
 *      可构建（ISSUE-P2-192 余量第 3 项的部分设备证据，见限界表同批登记）。
 * - **不覆盖**：`Constraints`/`NetworkType` 约束下的真实触发时机与**锁屏态执行**——
 *   周期任务真实触发需等待 ≥15 分钟下限且受设备空闲策略支配，无法在测试时限内自动证成；
 *   该残余面在限界表同批登记。
 *
 * 选择 work-testing 的原因：`PeriodicSyncScheduler` 直接消费
 * `WorkManager.getInstance(context)`，真实 WorkManager 在测试进程未初始化时不可用；
 * `WorkManagerTestInitHelper` 提供可查询的测试实例（enqueue / 取消 / 状态查询全部走真实代码路径）。
 * `ExtendedSettingsStore(null)`（null 上下文 = 纯内存默认偏好）是该仓自带的可测性设计，
 * 无需另写测试桩。
 */
@RunWith(AndroidJUnit4::class)
class PeriodicSyncSchedulerDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun newScheduler() = PeriodicSyncScheduler(context, ExtendedSettingsStore(null))

    @Test
    fun `开启调度后唯一周期任务真实注册为 ENQUEUED`() {
        val scheduler = newScheduler()

        scheduler.reschedule(enabled = true, intervalMinutes = 30, wifiOnly = false)

        val after = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PeriodicSyncScheduler.UNIQUE_WORK_NAME).get()
        val enqueued = after.filter { it.state == WorkInfo.State.ENQUEUED }
        assertTrue(
            "重排后必须存在 ENQUEUED 的周期任务（实际 ${after.map { it.state }}）",
            enqueued.isNotEmpty()
        )
        // 「注册的执行体确为 PeriodicSyncWorker」由下方 TestListenableWorkerBuilder
        // 直接构建并执行该类承载（WorkInfo 不暴露 workerClassName，无法在此断言）
    }

    @Test
    fun `关闭调度后唯一周期任务被取消`() {
        val scheduler = newScheduler()
        scheduler.reschedule(enabled = true, intervalMinutes = 30, wifiOnly = false)

        scheduler.reschedule(enabled = false, intervalMinutes = 30, wifiOnly = false)

        val after = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(PeriodicSyncScheduler.UNIQUE_WORK_NAME).get()
        assertTrue(
            "关闭后不得残留 ENQUEUED 任务（实际状态：${after.map { it.state }}）",
            after.none { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    @Test
    fun `Worker 在设备上经 Hilt EntryPoint 真实执行成功`() = runBlocking {
        val worker = TestListenableWorkerBuilder<PeriodicSyncWorker>(context).build()

        val result = worker.doWork()

        assertTrue(
            "未配置同步凭据时 Worker 必须安全返回 success（按未配置处理，绝不 retry 风暴），" +
                "实际 $result",
            result is ListenableWorker.Result.Success
        )
    }
}
