package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-444`「界面偏好」组的**接线守卫**。
 *
 * 三件事各自都曾在本仓真实发生过（假开关 / 只落盘不消费 / 绕过偏好直取样式），故逐条钉住：
 * ① 两个偏好必须有与既有可观察行为一致的默认值，且真实读写持久化；
 * ② 必须投影到设置页状态、并在「界面偏好」组里露出开关；
 * ③ 等宽字段样式**只能**经偏好访问器取值——任何直接引用样式常量的站点都等于绕过开关。
 */
class InterfacePreferencesWiringTest {

    @Test
    fun `界面偏好默认值必须与既有可观察行为一致`() {
        // 无持久化层时 load() 回落数据类默认值（生产未落库时同语义）
        val defaults = ExtendedSettingsStore(null).load()
        assertTrue("等宽字段默认开启＝改动前的硬编码行为", defaults.monospaceFieldsEnabled)
        assertFalse("动效降级默认关闭＝改动前的正常动效", defaults.reduceAnimations)
    }

    @Test
    fun `界面偏好必须真实读写持久化且投影到设置状态`() {
        val store = stripCommentsOnly(readSource(STORE_PATH))
        assertTrue("缺少等宽字段偏好的持久化键", store.contains("K_MONOSPACE_FIELDS = \"monospace_fields_enabled\""))
        assertTrue("缺少动效降级偏好的持久化键", store.contains("K_REDUCE_ANIMATIONS = \"reduce_animations\""))
        assertTrue(
            "load() 必须读取等宽字段偏好（否则重启回默认）",
            store.contains("monospaceFieldsEnabled = p.getBoolean(K_MONOSPACE_FIELDS")
        )
        assertTrue(
            "save() 必须写入动效降级偏好（否则重启回默认）",
            store.contains("putBoolean(K_REDUCE_ANIMATIONS, settings.reduceAnimations)")
        )

        val projection = stripCommentsOnly(readSource(PROJECTION_PATH))
        assertTrue(
            "设置页状态投影必须下发等宽字段偏好",
            projection.contains("monospaceFieldsEnabled = extState.monospaceFieldsEnabled")
        )
        assertTrue(
            "设置页状态投影必须下发动效降级偏好",
            projection.contains("reduceAnimations = extState.reduceAnimations")
        )

        val section = stripCommentsOnly(readSource(SECTION_PATH))
        assertTrue(
            "「界面偏好」页必须渲染等宽字段开关",
            section.contains("checked = uiState.monospaceFieldsEnabled")
        )
        assertTrue(
            "「界面偏好」页必须渲染动效降级开关",
            section.contains("checked = uiState.reduceAnimations")
        )

        // ISSUE-P3-444 修订：两个开关收进二级页后，设置主页必须**保留可达入口**
        // （自「界面与显示」组第三行进入）——入口缺失等于开关被打入冷宫
        val home = stripCommentsOnly(readSource(HOME_GROUPS_PATH))
        assertTrue(
            "设置主页「界面与显示」组必须有指向界面偏好页的入口行",
            home.contains("onClick = actions.onNavigateToInterface")
        )
        assertTrue(
            "设置主页不得再内联渲染这两个开关（否则开关出现两份）",
            !home.contains("checked = uiState.monospaceFieldsEnabled")
        )
    }

    /**
     * `ISSUE-P3-444` 修订：两个开关收进二级页后，「入口可达」本身成为不变量——
     * 门面必须装配路由体、路由体必须指向新 `Screen` 且宿主为新页面。
     */
    @Test
    fun `界面偏好二级页必须已注册且指向新路由`() {
        val facade = stripCommentsOnly(readSource(FACADE_PATH))
        assertTrue(
            "二级路由门面必须装配 settingsInterfaceRoute(navController)",
            facade.contains("settingsInterfaceRoute(navController)")
        )
        // ISSUE-P3-444 修订：外观域路由体已拆至 Display Routes 文件（tier2 棘轮），仍须逐项在册
        val routes = stripCommentsOnly(readSource(ROUTE_PATH))
        assertTrue(
            "路由体必须在路由文件中定义",
            routes.contains("fun NavGraphBuilder.settingsInterfaceRoute")
        )
        assertTrue(
            "路由体必须落在新建的 Screen.SettingsInterface 上",
            routes.contains("Screen.SettingsInterface.route")
        )
        assertTrue(
            "路由体必须渲染新页面 InterfaceSettingsScreen",
            routes.contains("InterfaceSettingsScreen(")
        )
    }

    @Test
    fun `动效降级必须在运行期生效且不改定标常量`() {
        val app = stripCommentsOnly(readSource(APP_PATH))
        assertTrue(
            "KeePasskeyApp 必须按偏好派生降级转场实例",
            app.contains("if (reduceAnimations) base.withReducedMotion() else base")
        )
        assertTrue(
            "动效降级必须经 LocalReduceAnimations 全站可见",
            app.contains("LocalReduceAnimations provides appSettings.reduceAnimations")
        )
        val listContent = stripCommentsOnly(readSource(LIST_CONTENT_PATH))
        assertTrue(
            "列表项动画必须随动效降级退化为直切（spec 传 null）",
            listContent.contains("if (reduceAnimations) null else MaterialTheme.motionScheme.defaultEffectsSpec")
        )
    }

    /**
     * 等宽样式常量只允许出现在定义文件 `Type.kt` 内（定义 + 回落样式 + 访问器）。
     * 其它任何站点直取常量即绕过「界面偏好」开关。
     */
    @Test
    fun `等宽样式常量不得被定义文件之外的站点直接引用`() {
        val offenders = File(repositoryRoot, MAIN_SOURCE_DIR)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.relativePath() != TYPE_PATH }
            .filter { file ->
                val code = stripCommentsOnly(file.readText(Charsets.UTF_8))
                code.contains("MonospacePasswordStyle") || code.contains("MonospaceTotpStyle")
            }
            .map { it.relativePath() }
            .sorted()
            .toList()
        assertTrue(
            "以下文件直接引用了等宽样式常量（应改走 passwordFieldStyle() / totpFieldStyle()）：$offenders",
            offenders.isEmpty()
        )
    }

    private fun File.relativePath(): String = relativeTo(repositoryRoot).invariantSeparatorsPath

    private fun readSource(relativePath: String): String =
        File(repositoryRoot, relativePath).readText(Charsets.UTF_8)

    private companion object {
        const val MAIN_SOURCE_DIR = "app/src/main"
        const val TYPE_PATH = "app/src/main/java/com/keepasskey/app/ui/theme/Type.kt"
        const val STORE_PATH =
            "app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt"
        const val PROJECTION_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        /** ISSUE-P3-444 修订：两个开关现落在「界面偏好」二级页 */
        const val SECTION_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/InterfaceSettingsScreen.kt"

        /** 设置主页的分组装配（入口行必须在此，且不得再内联渲染两开关） */
        const val HOME_GROUPS_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsGroups.kt"

        const val FACADE_PATH = "app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsNavGraph.kt"

        /** ISSUE-P3-444 修订：外观域（主题 / 列表与导航 / 界面偏好）路由体所在文件 */
        const val ROUTE_PATH =
            "app/src/main/java/com/keepasskey/app/ui/KeePasskeySettingsDisplayNavGraphRoutes.kt"
        const val APP_PATH = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt"
        const val LIST_CONTENT_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListContent.kt"

        private const val ROOT_SEARCH_DEPTH = 4

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
