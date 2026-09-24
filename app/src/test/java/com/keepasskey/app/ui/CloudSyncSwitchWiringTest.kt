package com.keepasskey.app.ui

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.testutil.stripCommentsOnly
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 云端同步开关接线守卫（**ISSUE-P3-272** 验收 AC①③⑤，静态源码比对，
 * 体例沿用 [AutofillChannelSwitchWiringTest] 的「消费点计数 + 持久化两侧 + 文案不得谎报」口径）。
 *
 * 本条修的**不是**「判定算错」，而是四项**整条链路是摆设**：
 *
 * 1. `autoSyncEnabled` 只活在本控制器内存 `StateFlow`——无持久化键（重启回 `true`）、
 *    无任何生产消费方 ⇒ 现改走 `ExtendedSettingsStore`（内存快照 + 落盘原子完成），
 *    真实消费点为 `VaultListViewModel` init 的「解锁后自动同步一次」触发点；
 * 2. `useFileTransactions` / `preloadDatabaseEnabled` / `allowedWifiSsids` 三项
 *    「偏好只落盘不消费」（前者还挂着「关得掉原子写」的**假承诺**）⇒ 整体移除（登记 `PD-39`）。
 *
 * 故本类的断言指向「摆设复现」的三种形态：
 * - **无持久化**：`autoSyncEnabled` 必须同时出现在读侧与写侧，UI 回显必须取自持久化快照 `extState`；
 * - **无消费方**：开关必须在 `VaultListViewModel` 的解锁触发点被求值，且与「是否已配置云同步」合取；
 * - **虚假措辞**：已移除三项的字段名 / 偏好键 / 字符串键**全域零残留**，自动同步副标题不得再宣称
 *   「数据变更后自动推送」（无该实现）。
 */
class CloudSyncSwitchWiringTest {

    private val storeSource: String by lazy { readSource(STORE) }
    private val projectionSource: String by lazy { readSource(PROJECTION) }
    private val syncControllerSource: String by lazy { readSource(SYNC_CONTROLLER) }
    private val vaultListSource: String by lazy { readSource(VAULT_LIST_VM) }
    private val settingsViewModelSource: String by lazy { readSource(SETTINGS_VM) }
    private val preferencesControllerSource: String by lazy { readSource(PREFERENCES_CONTROLLER) }
    private val cloudSyncSectionsSource: String by lazy { readSource(CLOUD_SYNC_SECTIONS) }

    // ========== AC⑤ ①：持久化闭环，重启不回弹 ==========

    @Test
    fun `自动同步开关在读侧与写侧同时存在`() {
        assertTrue(
            "读侧缺键：${EXT_KEY} 未进 load()",
            storeSource.contains("$EXT_KEY, defaults.autoSyncEnabled")
        )
        assertTrue(
            "写侧缺键：${EXT_KEY} 未进 save()",
            storeSource.contains(".putBoolean($EXT_KEY")
        )
    }

    /** 回归锁：整改前的「纯内存第二数据源」不得复活 */
    @Test
    fun `自动同步 setter 走内存快照与持久化原子完成`() {
        val body = functionBody(
            stripCommentsOnly(syncControllerSource),
            "fun setAutoSyncEnabled("
        )
        assertTrue("必须更新进程级共享快照", body.contains("extendedSettingsStore.publish("))
        assertTrue("必须落盘（否则重启回默认值的假开关复活）", body.contains("extendedSettingsStore.save("))
        assertFalse(
            "不得只写本控制器内存 StateFlow（整改前形态：关掉啥也不影响，重启即回弹）",
            body.contains("syncStateFlow.update")
        )
    }

    @Test
    fun `自动同步 UI 回显取自持久化快照而非内存态`() {
        assertTrue(
            "投影必须读持久化快照",
            projectionSource.contains("autoSyncEnabled = extState.autoSyncEnabled")
        )
        assertFalse(
            "投影不得再读同步控制器内存态（整改前形态）",
            projectionSource.contains("autoSyncEnabled = syncState.autoSyncEnabled")
        )
    }

    // ========== AC①⑤ ②：开关有真实生产消费方 ==========

    @Test
    fun `自动同步开关被解锁后触发点真实消费`() {
        // 静态消费点计数（沿用 AutofillChannelSwitchWiringTest 口径）：本 VM 是唯一生产消费方，
        // 且只读一次——多读说明接线漂移（有了第二个触发点需重新评估语义），零读即假开关复活。
        assertEquals(
            "解锁后触发点必须恰好求值开关一次（0 = 假开关复活，>1 = 出现未评估的第二触发点）",
            1,
            vaultListSource.countInvocationsOf("autoSyncEnabled")
        )
        val initBlock = functionBody(stripCommentsOnly(vaultListSource), "    init {")
        assertTrue("触发点必须仍在 init（解锁后首次进入列表页）", initBlock.contains("autoSyncEnabled"))
        assertTrue(
            "必须与「已配置云同步」合取后才触发（否则未配置云同步也空跑一轮）",
            initBlock.contains("isSyncConfigured()")
        )
        assertTrue("触发动作必须仍为下拉刷新同源的 triggerPullRefresh()", initBlock.contains("triggerPullRefresh()"))
        assertTrue(
            "开关求值必须先于触发动作（顺序倒置即恒不触发）",
            initBlock.indexOf("autoSyncEnabled") < initBlock.indexOf("triggerPullRefresh()")
        )
    }

    @Test
    fun `自动同步开关经设置域单语句委托到达控制器`() {
        assertTrue(
            "SettingsViewModel 必须单语句委托（PD-23 收窄授权口径）",
            settingsViewModelSource.contains("fun setAutoSyncEnabled(enabled: Boolean) = syncController.setAutoSyncEnabled(enabled)")
        )
    }

    // ========== AC①：默认值两侧一致 ==========

    @Test
    fun `无持久化层与数据类默认两侧同为开启`() {
        assertTrue("数据类默认值必须为开启（否则重启后行为与承诺相反）", ExtendedSettings().autoSyncEnabled)
        assertTrue(
            "无持久化层（纯 JVM / 键缺失）路径须回落到同一默认值",
            ExtendedSettingsStore(null).load().autoSyncEnabled
        )
    }

    // ========== AC①③：三项假开关整体移除，全域零残留 ==========

    @Test
    fun `三项已移除假开关在 app 主源码与文案中零残留`() {
        val files = collectAppMainSourceFiles()
        assertTrue(
            "扫描面异常：app/src/main 下 .kt/.xml 仅 ${files.size} 个（扫描器可能已失效）",
            files.size >= 300
        )

        val scanned = StringBuilder()
        val offenders = mutableListOf<String>()
        files.forEach { file ->
            val raw = file.readText()
            // 注释不参与判定：整改说明本身会写出被移除的键名（本类 KDoc 即为例），
            // 剔除注释后残留即真实代码残留。
            val text = if (file.name.endsWith(KOTLIN_SUFFIX)) stripCommentsOnly(raw) else raw
            scanned.append(text)
            REMOVED_TOKENS.forEach { token ->
                if (text.contains(token)) offenders += "${file.relativeTo(repositoryRoot)} → $token"
            }
        }

        assertTrue("三项已移除假开关出现残留：$offenders", offenders.isEmpty())

        // 反空转「正控制」：同一次扫描必须命中真实保留的接线符号，
        // 否则「零残留」只是扫描器失灵造成的假绿（沿用 AutofillChannelSwitchWiringTest 防空转口径）。
        assertTrue(
            "正控制失守：同一次扫描未命中 wifiOnlySync——真开关会被一并误判为已移除",
            scanned.contains("wifiOnlySync")
        )
        assertTrue(
            "正控制失守：同一次扫描未命中 sync_wifi_only_title——文案扫描面不完整",
            scanned.contains("sync_wifi_only_title")
        )
    }

    @Test
    fun `已移除假开关的字段与 setter 在设置域不再存在`() {
        val removedDeclarations = listOf(
            "val allowedWifiSsids",
            "val useFileTransactions",
            "val preloadDatabaseEnabled",
            "fun setAllowedWifiSsids",
            "fun setUseFileTransactions",
            "fun setPreloadDatabaseEnabled"
        )
        listOf(
            "ExtendedSettings" to readSource(EXTENDED_SETTINGS),
            "SettingsUiState" to readSource(SETTINGS_UI_STATE),
            "SettingsExtendedPreferencesController" to preferencesControllerSource,
            "SettingsViewModel" to settingsViewModelSource
        ).forEach { (label, source) ->
            val code = stripCommentsOnly(source)
            removedDeclarations.forEach { declaration ->
                assertFalse("$label 仍残留已移除项：$declaration", code.contains(declaration))
            }
        }
    }

    @Test
    fun `已移除假开关的字符串键与偏好键已删除`() {
        listOf("中文" to ZH_STRINGS, "英文" to EN_STRINGS).forEach { (label, path) ->
            val xml = readSource(path)
            listOf("sync_file_tx_title", "sync_file_tx_sub", "sync_preload_title", "sync_preload_sub")
                .forEach { key ->
                    assertFalse("$label 仍残留字符串键 $key", xml.contains(key))
                }
            assertTrue(
                "$label 正控制未命中 sync_wifi_only_title（文案面可能未读到）",
                xml.contains("sync_wifi_only_title")
            )
        }
        listOf("allowed_wifi_ssids", "use_file_transactions", "preload_database_enabled").forEach { key ->
            assertFalse("偏好存储仍残留已移除键 $key", storeSource.contains(key))
        }
        // 真开关必须仍在渲染链上（防「连坐误删」）
        assertTrue("仅 Wi-Fi 同步开关不得被连带移除", cloudSyncSectionsSource.contains("sync_wifi_only_title"))
        assertTrue("自动同步开关必须仍在渲染链上", cloudSyncSectionsSource.contains("sync_auto_sync_title"))
    }

    // ========== AC④：文案仅描述真实行为 ==========

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

    // ========== helpers ==========

    /** app/src/main 下全部 `.kt` / `.xml`（唯一生产可运行面） */
    private fun collectAppMainSourceFiles(): List<File> =
        File(repositoryRoot, APP_MAIN).walkTopDown()
            .filter { it.isFile && (it.name.endsWith(KOTLIN_SUFFIX) || it.name.endsWith(".xml")) }
            .toList()

    private fun String.countInvocationsOf(token: String): Int {
        var count = 0
        var at = indexOf(token)
        while (at >= 0) {
            count++
            at = indexOf(token, at + token.length)
        }
        return count
    }

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

    /** 按花括号配平提取函数 / 块体（调用前须先剔除注释，注释中的 `{` `}` 会破坏配对） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到签名：$signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("签名缺少代码块：$signature", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
        }
        error("代码块未闭合：$signature")
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val KOTLIN_SUFFIX = ".kt"
        const val APP_MAIN = "app/src/main"

        /** `ExtendedSettingsStore` 中的自动同步偏好键名（读写两侧共用符号） */
        const val EXT_KEY = "K_AUTO_SYNC_ENABLED"

        const val STORE = "app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt"
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        const val SYNC_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsSyncController.kt"
        const val SETTINGS_VM =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val PREFERENCES_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        const val EXTENDED_SETTINGS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt"
        const val SETTINGS_UI_STATE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt"
        const val CLOUD_SYNC_SECTIONS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/CloudSyncSections.kt"
        const val VAULT_LIST_VM =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListViewModel.kt"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

        /**
         * `ISSUE-P3-272` 移除的三项假开关的全部可辨识符号（字段名 / 偏好键 / 字符串键 /
         * UI 回调形参 / 设置域 setter）。任一命中即视为残留。
         */
        val REMOVED_TOKENS = listOf(
            "allowedWifiSsids",
            "useFileTransactions",
            "preloadDatabaseEnabled",
            "allowed_wifi_ssids",
            "use_file_transactions",
            "preload_database_enabled",
            "K_ALLOWED_WIFI_SSIDS",
            "K_USE_FILE_TRANSACTIONS",
            "K_PRELOAD_DATABASE",
            "sync_file_tx",
            "sync_preload",
            "onAllowedWifiSsidsChange",
            "onUseFileTransactionsToggle",
            "onPreloadDatabaseEnabledToggle",
            "setAllowedWifiSsids",
            "setUseFileTransactions",
            "setPreloadDatabaseEnabled"
        )

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
