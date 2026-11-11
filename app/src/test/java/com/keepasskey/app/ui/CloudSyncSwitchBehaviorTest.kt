package com.keepasskey.app.ui

import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.DatabaseConfigUiState
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.settings.SettingsHealthController
import com.keepasskey.app.ui.screens.settings.SettingsSyncController
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import com.keepasskey.app.ui.screens.settings.buildSettingsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 云端同步开关的**行为**用例（ISSUE-P3-272 验收 AC①③⑤）。
 *
 * 本条修的**不是**「判定算错」，而是整条链路是摆设：`autoSyncEnabled` 曾只活在本控制器内存
 * `StateFlow`（无持久化键、重启回 true；无任何生产消费方）。整改后走 `ExtendedSettingsStore`
 * （进程级快照 + 落盘）。
 *
 * ENG-20261028-12 整改：原 `CloudSyncSwitchWiringTest` 的 6 条用例读源码文本——统计开关引用次数
 * 恰为 1、匹配 `functionBody("fun setAutoSyncEnabled(")` 内的调用、逐文件反查已移除假开关的
 * 字段名 / 偏好键 / 字符串键零残留。这类断言只锁整改当时的写法（提取消歧函数、改行、换键名
 * 常量即整批误红），既不能证明开关真的持久化与回显，也把「等价重构」当成缺陷。现改为：
 *
 * 1. 持久化往返：关掉 → 新实例（模拟进程重启）读回仍为关闭；
 * 2. 缺省语义：数据类默认与无持久化层路径同值；
 * 3. 设置页投影：回显取自传入的持久化快照（而不是另一路内存态）。
 *
 * 保留：两条**用户可见文案**用例（自动同步副标题、仅 Wi-Fi 副标题）——那是真实交付的串资源
 * 内容，不是源码结构。
 *
 * **未覆盖（须设备 / 界面验收，不得由本件绿推定）**：
 * - 「未持久化即回弹」以外的触发接线：`VaultListViewModel` 解锁后自动同步一次的触发点
 *   （需开开关 + 已配置云同步才触发，且先于刷新动作）；
 * - `PeriodicSyncScheduler` 对 `wifiOnly` 的网络约束映射（须断言实际 `Constraints` 为 UNMETERED）；
 * - `SettingsViewModel` → `SettingsSyncController` 的委托链与宿主回显。
 * ENG-20261028-12 关闭条件要求这些点各自有行为或设备证据。
 */
class CloudSyncSwitchBehaviorTest {

    @Test
    fun `自动同步开关关闭后由新实例读回仍为关闭`() {
        val prefs = InMemorySharedPreferences()
        ExtendedSettingsStore(prefs.context()).save(ExtendedSettings(autoSyncEnabled = false))

        // 新实例 = 进程重启后的冷读路径（原缺陷：无持久化键，重启回 true）
        val reloaded = ExtendedSettingsStore(prefs.context())
        assertFalse("自动同步开关重启后回弹为开启", reloaded.load().autoSyncEnabled)

        reloaded.save(ExtendedSettings(autoSyncEnabled = true))
        assertTrue(
            "重新开启同样必须落盘（开关不得被硬禁用）",
            ExtendedSettingsStore(prefs.context()).load().autoSyncEnabled
        )
    }

    @Test
    fun `无持久化层与数据类默认两侧同为开启`() {
        assertTrue("数据类默认值必须为开启（否则重启后行为与承诺相反）", ExtendedSettings().autoSyncEnabled)
        assertTrue(
            "无持久化层（纯 JVM / 键缺失）路径须回落到同一默认值",
            ExtendedSettingsStore(null).load().autoSyncEnabled
        )
    }

    @Test
    fun `设置页回显随持久化快照取值变化`() {
        assertTrue("默认快照下自动同步回显为开启", project(ExtendedSettings()).autoSyncEnabled)
        assertFalse(
            "自动同步关闭后设置页回显必须为关闭（不得读另一路内存态）",
            project(ExtendedSettings(autoSyncEnabled = false)).autoSyncEnabled
        )
    }

    @Test
    fun `自动同步文案描述真实触发点而不得再宣称数据变更即推送`() {
        val zhSub = stringValue(readSource(ZH_STRINGS), "sync_auto_sync_sub")
        val enSub = stringValue(readSource(EN_STRINGS), "sync_auto_sync_sub")

        // 防空转：两侧必须真取到值，否则下面的否定断言会「永远绿」
        assertTrue("中文副标题未取到值（解析失败）", zhSub.isNotBlank())
        assertTrue("英文副标题未取到值（解析失败）", enSub.isNotBlank())

        assertTrue("中文副标题须描述真实触发点（解锁后一次）", zhSub.contains("解锁"))
        assertTrue("英文副标题须描述真实触发点", enSub.contains("unlock", ignoreCase = true))
        assertFalse("中文不得再承诺「数据变更后自动推送」——全仓无该实现", zhSub.contains("数据变更后"))
        assertFalse(
            "英文不得再承诺自动推送变更——全仓无该实现",
            enSub.contains("Push changes", ignoreCase = true)
        )
    }

    @Test
    fun `仅 Wi-Fi 开关文案两侧限定定时后台同步作用域且旧过度承诺零残留`() {
        val zh = readSource(ZH_STRINGS)
        val en = readSource(EN_STRINGS)
        // 逐条按 <string> 键取值（键改名即断言红），防空转：两侧必须真取到值
        val zhTitle = stringValue(zh, "sync_wifi_only_title")
        val zhSub = stringValue(zh, "sync_wifi_only_sub")
        val enTitle = stringValue(en, "sync_wifi_only_title")
        val enSub = stringValue(en, "sync_wifi_only_sub")
        listOf(
            "中文标题" to zhTitle, "中文副标题" to zhSub,
            "英文标题" to enTitle, "英文副标题" to enSub
        ).forEach { (label, value) ->
            assertTrue("$label 未取到值（解析失败）", value.isNotBlank())
        }

        // ① 作用域限定：中英两侧新文案必须点名「定时后台同步」，且保留不受约束入口的排除声明
        assertTrue("中文标题须限定为定时同步", zhTitle.contains("定时"))
        assertTrue("中文副标题须限定「定时后台同步」作用域", zhSub.contains("定时后台同步"))
        assertTrue("中文副标题须声明不受约束的入口（排除声明）", zhSub.contains("不受此开关限制"))
        assertTrue("英文标题须限定为 scheduled sync", enTitle.contains("scheduled", ignoreCase = true))
        assertTrue(
            "英文副标题须限定 scheduled background sync 作用域",
            enSub.contains("scheduled background sync", ignoreCase = true)
        )
        assertTrue(
            "英文副标题须声明不受约束的入口（排除声明）",
            enSub.contains("not limited by this switch", ignoreCase = true)
        )

        // ② 旧的过度承诺短语整文件零残留（含注释——超范围承诺不得以任何形式留在串资源里）
        listOf("中文" to zh, "英文" to en).forEach { (label, xml) ->
            assertFalse("$label strings.xml 仍残留旧过度承诺「暂停大文件同步」", xml.contains("暂停大文件同步"))
            assertFalse("$label strings.xml 仍残留旧过度承诺「移动网络下暂停」", xml.contains("移动网络下暂停"))
            assertFalse(
                "$label strings.xml 仍残留旧过度承诺英文对应短语",
                xml.contains("Pause large transfers", ignoreCase = true)
            )
        }
    }

    @Test
    fun `兼容覆盖写入开关默认关闭且开启后由新实例读回仍为开启`() {
        assertFalse("默认必须关闭（不得静默放松覆盖安全）", ExtendedSettings().webdavAllowDeleteBeforeMove)
        assertFalse(ExtendedSettingsStore(null).load().webdavAllowDeleteBeforeMove)

        val prefs = InMemorySharedPreferences()
        ExtendedSettingsStore(prefs.context()).save(
            ExtendedSettings(webdavAllowDeleteBeforeMove = true)
        )
        assertTrue(
            "用户显式开启后重启不得回弹为关闭",
            ExtendedSettingsStore(prefs.context()).load().webdavAllowDeleteBeforeMove
        )
        assertFalse(
            "默认快照的设置页回显必须为关闭",
            project(ExtendedSettings()).webdavAllowDeleteBeforeMove
        )
        assertTrue(
            "设置页回显须取自持久化快照",
            project(ExtendedSettings(webdavAllowDeleteBeforeMove = true)).webdavAllowDeleteBeforeMove
        )
    }

    @Test
    fun `兼容覆盖写入文案如实告知云端文件短暂消失与并发覆盖风险`() {
        val zh = stringValue(readSource(ZH_STRINGS), "sync_delete_before_move_sub")
        val en = stringValue(readSource(EN_STRINGS), "sync_delete_before_move_sub")
        assertTrue("中文须告知云端文件短暂消失: $zh", zh.contains("短暂消失"))
        assertTrue("中文须告知多端并发覆盖风险: $zh", zh.contains("多端"))
        assertTrue("英文须告知 remote file disappears briefly: $en", en.contains("disappears briefly"))
        assertTrue("英文须告知并发覆盖风险: $en", en.contains("concurrent", ignoreCase = true))
    }

    private fun project(extState: ExtendedSettings): SettingsUiState = buildSettingsUiState(
        userSettings = UserSettings(),
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
        extState = extState,
        debugLogLines = emptyList(),
        mountedChildDatabases = 0,
        strings = StringsProvider { _, _ -> "" }
    )

    /** 提取 `<string name="…">…</string>` 的正文 */
    private fun stringValue(xml: String, name: String): String {
        val marker = "<string name=\"$name\">"
        val start = xml.indexOf(marker)
        assertTrue("未找到字符串资源：$name", start >= 0)
        val bodyStart = start + marker.length
        val end = xml.indexOf("</string>", bodyStart)
        assertTrue("字符串资源未闭合：$name", end >= 0)
        return xml.substring(bodyStart, end)
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
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