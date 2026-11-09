package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.ui.model.StringsProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ISSUE-P3-362：「自动锁定超时」回显的单一真相源守卫。
 *
 * 缺陷形态：回显曾走 `securityTimeoutStateFlow` 内存流（初值硬编码 0、全仓无仓库播种），
 * 冷启动进安全设置页显示「立即」，而行为侧（AutoLockManager / AutoLockSessionGuard）
 * 生效的是持久化的 60 秒——显示与行为分叉。整改把投影改为直读 `userSettings`。
 *
 * ENG-20261028-12 整改：原附带的「投影与控制器不再持有超时内存回显流」用例逐条统计源码
 * 字符串与类型残留。它锁的是**整改当时的写法**（例如把回显表达式挪到别的赋值位置、或
 * 把内存流的替代载体改名，都会误红），而「回显等于仓库输入」的行为读数已由本类三个用例
 * 覆盖；已移除的类型/字段由编译器与类型系统兜底。故删除该源码守卫。
 */
class AutoLockTimeoutEchoTest {

    private fun project(userSettings: UserSettings): SettingsUiState = buildSettingsUiState(
        userSettings = userSettings,
        syncState = SettingsSyncController.SyncUiState(),
        healthState = SettingsHealthController.HealthCheckUiState(
            healthScore = 0,
            healthStatus = "",
            healthMessage = "",
            weakPasswordCount = 0,
            reusedPasswordCount = 0,
            compromisedPasswordCount = null,
            breachCheckStatus = BreachCheckStatus.DISABLED,
            breachCheckMessage = "",
            lastHealthScanTime = "",
            isHealthScanning = false
        ),
        dbState = DatabaseConfigUiState(
            databaseName = "",
            defaultUsername = "",
            encryptionAlgorithm = "",
            kdfAlgorithm = "",
            argon2Iterations = 0L,
            argon2MemoryMb = 0L,
            argon2Parallelism = 0,
            recycleBinEnabled = true
        ),
        extState = ExtendedSettings(),
        debugLogLines = emptyList(),
        mountedChildDatabases = 0,
        strings = StringsProvider { _, _ -> "" }
    )

    @Test
    fun `冷启动默认回显等于仓库持久化默认值 60`() {
        assertEquals(60, project(UserSettings()).autoLockTimeoutSeconds)
    }

    @Test
    fun `自定义档回显逐值等于仓库输入`() {
        assertEquals(300, project(UserSettings(autoLockTimeoutSeconds = 300)).autoLockTimeoutSeconds)
    }

    @Test
    fun `永不档回显逐值等于仓库输入`() {
        assertEquals(-1, project(UserSettings(autoLockTimeoutSeconds = -1)).autoLockTimeoutSeconds)
    }
}
