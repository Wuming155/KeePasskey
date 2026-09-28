package com.keepasskey.app.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-363：`lockWhenScreenOff` 单一真相源守卫。
 *
 * 缺陷形态：该偏好曾双存储——行为消费方（AutoLockSessionGuard）读 DataStore/UserSettings，
 * UI 回显读 ExtendedSettings 独立偏好键，两键无对账；legacy 迁移用户的 DataStore 为旧值而
 * ExtendedSettings 键缺失回落默认 true ⇒ 拨动前「显示值 ≠ 熄屏行为」。
 * 整改按「删除冗余键改单源」处置：ExtendedSettings 字段与键移除，回显与行为同读 UserSettings。
 * 本测试锁定：① 投影直读 userSettings；② ExtendedSettings 域零残留；③ 行为消费方读侧不变；
 * ④ setter 只写 DataStore 通道。
 */
class LockWhenScreenOffSingleSourceTest {

    @Test
    fun `投影回显直读 userSettings 与行为消费方同源`() {
        val projection = readMain(PROJECTION)
        val hits = projection.split("lockWhenScreenOff = userSettings.lockWhenScreenOff").size - 1
        assertEquals("投影必须恰好一处直读 userSettings.lockWhenScreenOff", 1, hits)
        assertTrue("投影源码解析过短，疑似读取失败", projection.length > 2_000)
    }

    @Test
    fun `ExtendedSettings 字段与偏好键零残留`() {
        val model = readMain(EXTENDED_SETTINGS)
        val store = readMain(STORE)
        // 声明形态清零（整改说明注释中提及字段名不算声明）
        assertEquals("ExtendedSettings 不得再声明该字段", 0, model.split("val lockWhenScreenOff").size - 1)
        assertEquals("偏好键常量不得复活", 0, store.split("K_LOCK_WHEN_SCREEN_OFF").size - 1)
        // load/save 读写两侧同步清零（键不得只删一半）
        assertEquals("load 不得再读该键", 0, store.split("getBoolean(K_LOCK_WHEN_SCREEN_OFF").size - 1)
        assertEquals("save 不得再写该键", 0, store.split("putBoolean(K_LOCK_WHEN_SCREEN_OFF").size - 1)
        assertTrue("ExtendedSettings 源码解析过短", model.length > 400)
        assertTrue("Store 源码解析过短", store.length > 2_000)
    }

    @Test
    fun `行为消费方仍读 UserSettings 熄屏锁定语义不变`() {
        val guard = readMain(AUTO_LOCK_GUARD)
        val hits = guard.split("settings.lockWhenScreenOff || settings.autoLockBackground").size - 1
        assertEquals("熄屏熔断判定条件必须保持原样", 1, hits)
        // 仓库侧键仍是行为真值的载体
        val repo = readMain(REAL_SETTINGS_REPOSITORY)
        assertEquals("UserSettings 侧持久化键不得移除", 1, repo.split("KEY_LOCK_WHEN_SCREEN_OFF = booleanPreferencesKey").size - 1)
    }

    @Test
    fun `setter 只写 DataStore 单源且不再双写 ExtendedSettings`() {
        val controller = readMain(EXTENDED_PREFERENCES_CONTROLLER)
        assertEquals(
            "setLockWhenScreenOff 必须是 persistLockWhenScreenOff 单表达式",
            1,
            controller.split("fun setLockWhenScreenOff(enabled: Boolean) = persistLockWhenScreenOff(enabled)").size - 1
        )
        assertEquals("不得复活 ExtendedSettings 双写", 0, controller.split("copy(lockWhenScreenOff").size - 1)
        assertTrue("控制器源码解析过短", controller.length > 2_000)
    }

    private fun readMain(relative: String): String {
        val file = File(repositoryRoot, relative)
        assertTrue("源码文件不存在（是否被重命名或移动）：$relative", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        const val EXTENDED_SETTINGS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt"
        const val STORE =
            "app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt"
        const val AUTO_LOCK_GUARD =
            "app/src/main/java/com/keepasskey/app/security/AutoLockSessionGuard.kt"
        const val REAL_SETTINGS_REPOSITORY =
            "app/src/main/java/com/keepasskey/app/data/repository/RealSettingsRepository.kt"
        const val EXTENDED_PREFERENCES_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先（同 CloudSyncSwitchWiringTest 口径） */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
