package com.keepasskey.app.sync

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-08 / TASK-12 单元测试：
 * 调度器间隔钳制纯函数与 ExtendedSettingsStore 无持久化层（纯 JVM 测试）回退语义。
 */
class PeriodicSyncSchedulerTest {

    @Test
    fun `周期间隔下限钳制为WorkManager系统最小值`() {
        assertEquals(15L, PeriodicSyncScheduler.clampIntervalMinutes(5))
        assertEquals(15L, PeriodicSyncScheduler.clampIntervalMinutes(15))
        assertEquals(30L, PeriodicSyncScheduler.clampIntervalMinutes(30))
        assertEquals(15L, PeriodicSyncScheduler.clampIntervalMinutes(0))
        assertEquals(15L, PeriodicSyncScheduler.clampIntervalMinutes(-10))
    }

    @Test
    fun `无持久化层时Store回退为不持久化的内存语义`() {
        // 纯 JVM 单元测试无 Android Context：Store 必须优雅降级而非抛异常
        val store = ExtendedSettingsStore(context = null)
        assertEquals(ExtendedSettings(), store.load())
        assertTrue(store.loadWifiOnlySync())
        // 写操作静默 no-op，不崩溃
        store.save(ExtendedSettings(useOfflineCache = false))
        store.saveWifiOnlySync(false)
        assertEquals(ExtendedSettings(), store.load())
    }
}
