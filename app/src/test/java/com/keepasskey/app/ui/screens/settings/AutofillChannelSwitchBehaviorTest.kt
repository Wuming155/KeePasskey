package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.breach.BreachCheckStatus
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.model.StringsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三条凭据通道开关（凭据管理器 / 通行密钥 / 系统自动填充）的**行为**用例（ISSUE-P2-228）。
 *
 * 缺陷背景：整改前三个开关只回写一个纯内存 `StateFlow`——无持久化键（重启回弹）、
 * 无生产消费方（关掉啥也不影响）。
 *
 * ENG-20261028-12 整改：原 `AutofillChannelSwitchWiringTest` 用「服务侧开关调用次数恰为 2」
 * 「源码含某个标识符」「旧内存载体已删除」等源码文本断言代替行为读数。这类断言既不证明开关
 * 真的约束交付（见 ENG-20261028-05：链式响应与最终交付处并未复核通道开关），又会阻断等价重构
 * （例如把 get / create 两条入口收敛到一个共享门控，计数从 2 变 1 即误红；把开关判定搬进
 * 纯函数也会让 `contains()` 失配）。故改为按可观测行为验证：
 *
 * 1. 持久化往返：关掉 → 新实例（模拟进程重启）读回仍为关闭，重新开启也回写成功；
 * 2. 缺省语义：无持久化层与数据类默认值两处一致（避免两处各自漂移）；
 * 3. 设置页投影：投影输出取自持久化快照。
 *
 * **未覆盖（须设备验收，不得由本件绿推定）**：开关在 `KeePasskeyAutofillService` 的填充 / 保存
 * 两条回调、`KeePasskeyCredentialProviderService` 的 get / create 入口，以及链式响应与最终交付
 * 边界上的实际门控。该接线缺口登记在 ENG-20261028-05，验收须在设备上覆盖「初始关闭」
 * 「候选已生成后关闭」「解锁或用户验证挂起后关闭」三类时序。
 */
class AutofillChannelSwitchBehaviorTest {

    @Test
    fun `三条通道开关关闭后由新实例读回仍为关闭`() {
        val prefs = InMemorySharedPreferences()
        ExtendedSettingsStore(prefs.context()).save(
            ExtendedSettings(
                credentialProviderEnabled = false,
                passkeySupportEnabled = false,
                autofillServiceEnabled = false,
                // 旧版无障碍通道反向验证：默认关闭的开关打开后同样必须落盘
                autofillLegacyAccessibilityEnabled = true
            )
        )

        // 新实例 = 进程重启后的冷读路径（原缺陷：回弹到默认开启）
        val reloaded = ExtendedSettingsStore(prefs.context())
        assertFalse("凭据管理器开关重启后回弹为开启", reloaded.isCredentialProviderEnabled())
        assertFalse("通行密钥开关重启后回弹为开启", reloaded.isPasskeySupportEnabled())
        assertFalse("自动填充开关重启后回弹为开启", reloaded.isAutofillServiceEnabled())

        val snapshot = reloaded.load()
        assertFalse(snapshot.credentialProviderEnabled)
        assertFalse(snapshot.passkeySupportEnabled)
        assertFalse(snapshot.autofillServiceEnabled)
        assertTrue("默认关闭的旧版无障碍通道开关未持久化", snapshot.autofillLegacyAccessibilityEnabled)
    }

    @Test
    fun `关闭后再开启同样可持久化`() {
        val prefs = InMemorySharedPreferences()
        val store = ExtendedSettingsStore(prefs.context())
        store.save(
            ExtendedSettings(
                credentialProviderEnabled = false,
                passkeySupportEnabled = false,
                autofillServiceEnabled = false,
                autofillLegacyAccessibilityEnabled = true
            )
        )
        store.save(ExtendedSettings())

        val reloaded = ExtendedSettingsStore(prefs.context())
        assertTrue(reloaded.isCredentialProviderEnabled())
        assertTrue(reloaded.isPasskeySupportEnabled())
        assertTrue(reloaded.isAutofillServiceEnabled())
        assertFalse(reloaded.isAutofillLegacyAccessibilityEnabled())
    }

    @Test
    fun `无持久化层时三条通道按默认开启判定`() {
        val store = ExtendedSettingsStore(null)
        assertTrue(store.isCredentialProviderEnabled())
        assertTrue(store.isPasskeySupportEnabled())
        assertTrue(store.isAutofillServiceEnabled())
        assertFalse("旧版无障碍通道默认关闭", store.isAutofillLegacyAccessibilityEnabled())
    }

    @Test
    fun `数据类默认值与单键读取缺省同值`() {
        val defaults = ExtendedSettings()
        val noPrefs = ExtendedSettingsStore(null)
        // 单键读取路径不经过数据类默认值，两侧漂移会让「默认开启」形同虚设
        assertEquals(defaults.credentialProviderEnabled, noPrefs.isCredentialProviderEnabled())
        assertEquals(defaults.passkeySupportEnabled, noPrefs.isPasskeySupportEnabled())
        assertEquals(defaults.autofillServiceEnabled, noPrefs.isAutofillServiceEnabled())
        assertEquals(
            defaults.autofillLegacyAccessibilityEnabled,
            noPrefs.isAutofillLegacyAccessibilityEnabled()
        )
    }

    @Test
    fun `设置页投影按持久化快照呈现通道开关`() {
        val prefs = InMemorySharedPreferences()
        val store = ExtendedSettingsStore(prefs.context())
        store.save(
            ExtendedSettings(
                credentialProviderEnabled = false,
                passkeySupportEnabled = false,
                autofillServiceEnabled = false,
                autofillLegacyAccessibilityEnabled = true
            )
        )

        val state = project(ExtendedSettingsStore(prefs.context()).load())
        assertFalse(state.credentialProviderEnabled)
        assertFalse(state.passkeySupportEnabled)
        assertFalse(state.autofillServiceEnabled)
        assertTrue(state.autofillLegacyAccessibilityEnabled)

        val defaultsState = project(ExtendedSettings())
        assertTrue(defaultsState.credentialProviderEnabled)
        assertTrue(defaultsState.passkeySupportEnabled)
        assertTrue(defaultsState.autofillServiceEnabled)
        assertFalse(defaultsState.autofillLegacyAccessibilityEnabled)
    }

    @Test
    fun `自动填充卡文案不再声称虚假的无障碍权限`() {
        // ISSUE-P2-228：三条框架通道的文案不得再声称「无障碍填充方式」（当时无对应实现）。
        // ISSUE-P3-324 更新：旧版无障碍自动填充通道已真实落地（LegacyAutofillAccessibilityService），
        // 其 autofill_legacy_accessibility_* 文案中的「无障碍」措辞**如实**描述真实存在的服务，
        // 属白名单例外；其余 autofill* 文案仍不得出现无障碍措辞。
        val zh = readSource("app/src/main/res/values/strings.xml")
        val en = readSource("app/src/main/res/values-en/strings.xml")
        val legacyKeys = setOf(
            "autofill_legacy_accessibility_title",
            "autofill_legacy_accessibility_sub",
            // ISSUE-P2-405：legacy 通道健康异常文案（如实描述无障碍服务，属白名单例外）
            "autofill_health_issue_legacy_a11y_system_disabled"
        )

        listOf("中文" to zh, "英文" to en).forEach { (label, source) ->
            val values = autofillStringValues(source)
            // 防空转：正则若因元素写法变化而一条不匹配，本用例就会「永远绿」而失去守卫意义
            assertTrue(
                "$label 侧至少应解析出 10 条 autofill* 文案（实际 ${values.size}，解析正则已失效）",
                values.size >= 10
            )
            assertTrue(
                "$label 侧旧版通道文案缺失（须含 $legacyKeys）",
                values.map { it.first }.toSet().containsAll(legacyKeys)
            )
            val offenders = values
                .filter { (name, _) -> name !in legacyKeys }
                .filter { (_, value) -> value.contains("无障碍") || value.contains("ccessibilit") }
                .map { it.first }
            assertTrue("$label 仍有框架通道的无障碍措辞：$offenders", offenders.isEmpty())
        }
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

    /** 提取全部 `autofill*` 字符串资源的 name 与 value（限定在单个元素内，不跨行拼接） */
    private fun autofillStringValues(source: String): List<Pair<String, String>> =
        Regex("<string name=\"(autofill[^\"]*)\">([^<]*)</string>").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun readSource(path: String): String {
        val file = java.io.File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: java.io.File by lazy {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (java.io.File(candidate, "app/src/main/java").isDirectory &&
                    java.io.File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}