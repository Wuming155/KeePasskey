package com.keepasskey.app.autofill

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P3-390` AC③ 接线守卫：确认页 / 选择器 / TOTP 通知三处文案必须随应用内语言
 * （`appLanguage=EN` 时不得回落系统语言）。
 *
 * 与 `UnlockEntryLanguageWiringTest`（§365 解锁入口）同范式：静态源扫描 + 真实条数计数。
 * JVM 无法直接加载 Android 资源并断言 getString 英文文案，故以
 * 「三处都经 `AppShellLocalization.localizedContextForAppLanguage` 取文案」+
 * 「values-en 对应键存在且为英文文案」锁定该承诺。
 *
 * 防失效形态：
 * 1. 回归原缺陷：确认页/选择器/通知只 `getString`，强制英文时仍随系统语言；
 * 2. 「同法接线」被架空：复制第二份 locale 实现，或绕过 `AppShellLocalization`；
 * 3. 通知面单独漏接：只有两页接线、`TotpNotificationPublisher` 仍走原始 `context`；
 * 4. 空转断言：锚点只写在注释里 ⇒ 全部先剥注释再计数。
 */
class AutofillAppLanguageSurfacesWiringTest {

    private val confirm: String get() = stripped(CONFIRM)
    private val picker: String get() = stripped(PICKER)
    private val totp: String get() = stripped(TOTP_PUBLISHER)
    private val appShell: String get() = stripped(APP_SHELL)

    @Test
    fun `三处装配点都经AppShellLocalization派生本地化上下文`() {
        mapOf(
            CONFIRM to confirm,
            PICKER to picker,
            TOTP_PUBLISHER to totp
        ).forEach { (path, source) ->
            assertTrue(
                "[$path] 缺 localizedContextForAppLanguage(——三处必须同法接线 AppShellLocalization",
                source.contains("localizedContextForAppLanguage(")
            )
            assertTrue(
                "[$path] 缺 SettingsRepository 注入/读取——appLanguage 快照通道不可断",
                source.contains("SettingsRepository") || source.contains("settingsRepository")
            )
        }
        // 主外壳单点派生函数仍存在（同源判据的「源」侧）
        assertTrue(
            "[$APP_SHELL] 缺 fun localizedContextForAppLanguage(",
            appShell.contains("fun localizedContextForAppLanguage(")
        )
    }

    @Test
    fun `三处文案经本地化上下文getString而非裸context`() {
        // 确认页 / 选择器：页内与系统认证弹窗文案都必须走 localizedContext
        listOf(CONFIRM to confirm, PICKER to picker).forEach { (path, source) ->
            assertTrue(
                "[$path] 缺 localizedContext.getString(——文案不得回落原始 context",
                source.contains("localizedContext.getString(")
            )
            assertFalse(
                "[$path] 不得再直接 context.getString（应用内语言接线被旁路）",
                // 排除 createConfigurationContext 等合法用法后的裸 context.getString
                bareContextGetString(source)
            )
        }
        // TOTP 通知：publish 内标题与正文都必须走 localizedContext
        assertTrue(
            "[$TOTP_PUBLISHER] 缺 localizedContext.getString(notification_totp_title)",
            totp.contains("localizedContext.getString(R.string.notification_totp_title)")
        )
        assertTrue(
            "[$TOTP_PUBLISHER] 缺 localizedContext.getString(notification_totp_text)",
            totp.contains("localizedContext.getString(R.string.notification_totp_text")
        )
        assertFalse(
            "[$TOTP_PUBLISHER] 不得再用原始 context 取通知文案（AC② 漏接）",
            bareContextGetString(totp)
        )
    }

    @Test
    fun `确认页与选择器不再裸用getString取页面文案`() {
        // 经 stripComments 后的源码里不得出现未限定的 getString(R.string（必须挂 localizedContext.）
        listOf(CONFIRM to confirm, PICKER to picker, TOTP_PUBLISHER to totp).forEach { (path, source) ->
            val bare = Regex("""(?<![\w.])getString\(R\.string""")
                .findAll(source)
                .count()
            assertTrue(
                "[$path] 存在 $bare 处未限定 getString(R.string)——必须全部改为 localizedContext.getString",
                bare == 0
            )
        }
    }

    @Test
    fun `values-en对应键存在且为英文文案`() {
        // AC③「appLanguage=EN 时三处文案为英文」的资源侧判据：
        // EN 词条必须非空且与默认（中文）词条不同——保证强制英文时不会回落到中文串
        val en = readRepoFile(STRINGS_EN)
        val zh = readRepoFile(STRINGS_ZH)
        val keys = listOf(
            "autofill_confirm_title",
            "fill_confirm_biometric_subtitle",
            "autofill_confirm_manual_hint",
            "autofill_confirm_ok",
            "autofill_confirm_cancel",
            "notification_totp_title",
            "notification_totp_text"
        )
        keys.forEach { key ->
            val enValue = stringValue(en, key)
            val zhValue = stringValue(zh, key)
            assertTrue("values-en 缺键 $key（应用内英文路径无文案可取）", enValue.isNotEmpty())
            assertTrue("values-zh 缺键 $key", zhValue.isNotEmpty())
            assertTrue(
                "键 $key 的 EN/ZH 文案不得相同（相同则「强制英文」无鉴别力）：$enValue",
                enValue != zhValue
            )
        }
        // 选择器页标题亦须有 EN 词条（picker 页文案面）
        val pickerTitle = stringValue(en, "autofill_picker_title")
        assertTrue("values-en 缺键 autofill_picker_title", pickerTitle.isNotEmpty())
        assertTrue(
            "autofill_picker_title EN/ZH 不得相同",
            pickerTitle != stringValue(zh, "autofill_picker_title")
        )
    }

    @Test
    fun `禁止在三处装配点复制第二份locale实现`() {
        mapOf(CONFIRM to confirm, PICKER to picker, TOTP_PUBLISHER to totp)
            .forEach { (path, source) ->
                listOf(
                    "createConfigurationContext" to "第二份 locale 实现——必须只经 AppShellLocalization 派生",
                    "java.util.Locale" to "第二份 locale 实现——Locale 取值必须只经 localeFor"
                ).forEach { (banned, why) ->
                    val count = source.occurrencesOf(banned)
                    assertFalse(
                        "[$path] 出现禁用锚点「$banned」× $count——$why",
                        count > 0
                    )
                }
            }
    }

    // ===== 基础设施 =====

    private fun stripped(path: String): String = stripCommentsOnly(readSource(path))

    /** 排除合法前缀后的「裸 context.getString(R.string」计数（文案接线被旁路的形态） */
    private fun bareContextGetString(source: String): Boolean {
        // 允许：localizedContext.getString / resources.getString 等；
        // 禁止：context.getString( 且前面不是 localizedContext.
        var index = source.indexOf("context.getString(R.string")
        while (index >= 0) {
            val before = source.substring(maxOf(0, index - 20), index)
            if (!before.endsWith("localizedContext.")) return true
            index = source.indexOf("context.getString(R.string", index + 1)
        }
        return false
    }

    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    /** 从 strings.xml 取指定 name 的词条值（粗解析，足够断言 EN/ZH 非空与不等） */
    private fun stringValue(xml: String, name: String): String {
        val pattern = Regex("""<string name="$name">([\s\S]*?)</string>""")
        val match = pattern.find(xml) ?: return ""
        return match.groupValues[1]
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("\\", "")
            .trim()
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在：$path", file.isFile)
        return file.readText()
    }

    private fun readRepoFile(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        const val CONFIRM =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
        const val PICKER =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt"
        const val TOTP_PUBLISHER =
            "app/src/main/java/com/keepasskey/app/notification/TotpNotificationPublisher.kt"
        const val APP_SHELL =
            "app/src/main/java/com/keepasskey/app/ui/AppShellLocalization.kt"
        const val STRINGS_EN = "app/src/main/res/values-en/strings.xml"
        const val STRINGS_ZH = "app/src/main/res/values/strings.xml"

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
            error("无法定位仓库根（起始：${System.getProperty("user.dir")}）")
        }
    }
}
