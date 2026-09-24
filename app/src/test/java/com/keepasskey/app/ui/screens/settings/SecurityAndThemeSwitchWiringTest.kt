package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安全与外观两项**假开关**移除的接线守卫（**ISSUE-P3-274** AC⑤）。
 *
 * 两项在整改前均为「偏好只落盘不消费」：
 * - `rememberRecentFiles`（安全语义项）——文案为「清除最近文件记录 / 移除密码库的历史打开记录」，
 *   但全仓**无任何「最近打开的数据库」记录源**，开关无对象可清；安全项不得停留在
 *   「看起来能控制痕迹」的假状态 ⇒ **如实移除**（`PD-40`）。
 * - `iconSet`（三选一：Material / KeePass 经典 / 极简单色）——全仓渲染侧**无任何消费**，
 *   改选后全站图标无变化 ⇒ **如实移除**（`PD-40`）。
 *
 * 本类同时锁定**不得连坐**：同卡真实接线的相邻项（`rememberKeyFileLocation` 在解锁侧
 * `SafKeyFileAccess` 被真实读取）必须原样保留——这是「逐项以消费方取证」而非「整片删除」的证据。
 */
class SecurityAndThemeSwitchWiringTest {

    @Test
    fun `两项已移除假开关在 app 主源码与文案中零残留`() {
        val files = collectAppMainSourceFiles()
        assertTrue(
            "扫描面异常：app/src/main 下 .kt/.xml 仅 ${files.size} 个（扫描器可能已失效）",
            files.size >= 300
        )

        val scanned = StringBuilder()
        val offenders = mutableListOf<String>()
        files.forEach { file ->
            val raw = file.readText()
            // 注释不参与判定：整改说明本身会写出被移除的标识符（本类 KDoc 即为例）
            val text = if (file.name.endsWith(KOTLIN_SUFFIX)) stripCommentsOnly(raw) else raw
            scanned.append(text)
            REMOVED_TOKENS.forEach { token ->
                if (text.contains(token)) offenders += "${file.relativeTo(repositoryRoot)} → $token"
            }
        }

        assertTrue("两项已移除假开关出现残留：$offenders", offenders.isEmpty())

        // 反空转正控制：同一次扫描必须命中仍真实存在的相邻项
        assertTrue(
            "正控制失守：同一次扫描未命中 rememberKeyFileLocation",
            scanned.contains("rememberKeyFileLocation")
        )
        assertTrue(
            "正控制失守：同一次扫描未命中 sync_wifi_only_title——文案扫描面不完整",
            scanned.contains("sync_wifi_only_title")
        )
    }

    @Test
    fun `已移除项的偏好键与字符串键均已删除`() {
        val store = readSource(STORE)
        listOf("remember_recent_files", "K_REMEMBER_RECENT_FILES", "icon_set", "K_ICON_SET")
            .forEach { key -> assertFalse("偏好存储仍残留 $key", store.contains(key)) }

        listOf("中文" to ZH_STRINGS, "英文" to EN_STRINGS).forEach { (label, path) ->
            val xml = readSource(path)
            listOf(
                "sec_recent_files_title",
                "sec_recent_files_sub",
                "theme_section_iconset",
                "theme_iconset_material",
                "theme_iconset_material_desc",
                "theme_iconset_classic",
                "theme_iconset_classic_desc",
                "theme_iconset_mono",
                "theme_iconset_mono_desc"
            ).forEach { key -> assertFalse("$label 仍残留字符串键 $key", xml.contains(key)) }

            assertTrue(
                "$label 正控制未命中 sec_keyfile_title（相邻真接线项被误删？）",
                xml.contains("sec_keyfile_title")
            )
            assertTrue(
                "$label 正控制未命中 theme_section_list——主题页其余分区仍在",
                xml.contains("theme_section_list")
            )
        }
    }

    @Test
    fun `相邻真接线项未被连坐误删`() {
        // rememberKeyFileLocation 的消费方是解锁侧真实读取，属「偏好 → 行为」闭环已成立项，
        // 与本条两项的「零消费方」判定**逐项分别取证**，不得整片删除。
        val unlock = stripCommentsOnly(readSource(SAF_KEY_FILE_ACCESS))
        assertTrue(
            "解锁侧必须仍读取 rememberKeyFileLocation（真实接线的证据）",
            unlock.contains("rememberKeyFileLocation")
        )

        val theme = readSource(THEME_SECTIONS)
        assertTrue("主题页列表显示分区须保留", theme.contains("themeListSection"))
        assertTrue("主题页语言分区须保留", theme.contains("themeLanguageSection"))
    }

    @Test
    fun `两处 UI 不再引用已移除项且入口参数已摘除`() {
        val security = stripCommentsOnly(readSource(SECURITY_SCREEN))
        assertFalse("安全页不得再引用已移除项", security.contains("sec_recent_files"))
        assertFalse("安全页不得再持有该开关的回调形参", security.contains("onRememberRecentFilesToggle"))
        // 该卡片仍保留相邻的密钥文件策略开关（证明是「摘一项」而非「砍整卡」）
        assertTrue(security.contains("onRememberKeyFileLocationToggle"))

        val themeScreen = stripCommentsOnly(readSource(THEME_SCREEN))
        assertFalse("主题页不得再挂载图标集分区", themeScreen.contains("themeIconSetSection"))
        assertFalse("主题页不得再持有该分区回调形参", themeScreen.contains("onIconSetSelected"))
        assertTrue("主题页语言分区须仍被挂载", themeScreen.contains("themeLanguageSection("))
    }

    @Test
    fun `偏好数据类与设置域不再保留两项字段`() {
        listOf(
            "ExtendedSettings" to readSource(EXTENDED_SETTINGS),
            "SettingsUiState" to readSource(SETTINGS_UI_STATE),
            "SettingsUiStateProjection" to readSource(PROJECTION),
            "SettingsViewModel" to readSource(SETTINGS_VIEW_MODEL),
            "SettingsExtendedPreferencesController" to readSource(PREFERENCES_CONTROLLER)
        ).forEach { (label, source) ->
            val code = stripCommentsOnly(source)
            listOf("rememberRecentFiles", "iconSet", "IconSetOption", "setIconSet")
                .forEach { token -> assertFalse("$label 仍残留 $token", code.contains(token)) }
        }
    }

    // ========== helpers ==========

    private fun collectAppMainSourceFiles(): List<File> =
        File(repositoryRoot, APP_MAIN).walkTopDown()
            .filter { it.isFile && (it.name.endsWith(KOTLIN_SUFFIX) || it.name.endsWith(".xml")) }
            .toList()

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val KOTLIN_SUFFIX = ".kt"
        const val APP_MAIN = "app/src/main"

        const val STORE = "app/src/main/java/com/keepasskey/app/data/repository/ExtendedSettingsStore.kt"
        const val EXTENDED_SETTINGS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/ExtendedSettings.kt"
        const val SETTINGS_UI_STATE =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiState.kt"
        const val PROJECTION =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsUiStateProjection.kt"
        const val SETTINGS_VIEW_MODEL =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsViewModel.kt"
        const val PREFERENCES_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/SettingsExtendedPreferencesController.kt"
        const val SECURITY_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/SecuritySettingsScreen.kt"
        const val THEME_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsScreen.kt"
        const val THEME_SECTIONS =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/subscreens/ThemeSettingsListSections.kt"
        const val SAF_KEY_FILE_ACCESS =
            "app/src/main/java/com/keepasskey/app/ui/screens/unlock/SafKeyFileAccess.kt"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

        /**
         * 两项已移除假开关的全部可辨识符号（字段名 / 偏好键 / 字符串键 / 枚举 / 回调形参 / setter）。
         * 任一命中即视为残留。
         */
        val REMOVED_TOKENS = listOf(
            "rememberRecentFiles",
            "remember_recent_files",
            "K_REMEMBER_RECENT_FILES",
            "onRememberRecentFilesToggle",
            "setRememberRecentFiles",
            "sec_recent_files",
            "iconSet",
            "IconSetOption",
            "icon_set",
            "K_ICON_SET",
            "theme_iconset",
            "theme_section_iconset",
            "themeIconSetSection",
            "onIconSetSelected",
            "setIconSet"
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
