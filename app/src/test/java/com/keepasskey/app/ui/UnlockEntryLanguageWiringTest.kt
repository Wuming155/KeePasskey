package com.keepasskey.app.ui

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-365 AC② 接线守卫（静态源扫描 + 真实条数计数）：两条独立解锁入口
 * （[com.keepasskey.app.autofill.AutofillUnlockActivity] /
 * [com.keepasskey.app.passkey.CredentialUnlockActivity]）与创建链解锁 Presenter 的
 * **配置上下文必须与主外壳同源**——三者与 [KeePasskeyApp] 同锚：都经
 * `AppShellLocalization`（`localeFor` / `localizedConfigurationOf` / `localizedContextOf`）
 * 派生，并 provide `LocalContext` 与 `LocalConfiguration`。
 *
 * 防失效形态：
 * 1. 回归原缺陷：入口只读 themeMode 不读 appLanguage ⇒ 强制语言回落系统语言；
 * 2. 「同源」被架空：自造第二份 locale 实现（直调 `createConfigurationContext` /
 * `java.util.Locale`），或在 `attachBaseContext` / `runBlocking` 里阻塞读 DataStore；
 * 3. AC③ 回归：顺带改动既有设置读取链（`getSettings().first()` 快照 + `settings.themeMode` 被拆）；
 * 4. 空转断言：锚点写进注释也「提及」了 ⇒ 全部先剥注释再计数，且每锚带条数下限。
 */
class UnlockEntryLanguageWiringTest {

    // ===== 锚点文件（路径相对仓库根） =====

    private val autofillUnlock: String get() = stripped(AUTOFILL_UNLOCK)
    private val credentialUnlock: String get() = stripped(CREDENTIAL_UNLOCK)
    private val credentialPresenter: String get() = stripped(CREDENTIAL_PRESENTER)
    private val appShell: String get() = stripped(APP_SHELL)

    @Test
    fun `三处独立解锁入口都从既有设置快照读取appLanguage并经AppShellLocalization派生`() {
        mapOf(
            AUTOFILL_UNLOCK to autofillUnlock,
            CREDENTIAL_UNLOCK to credentialUnlock,
            CREDENTIAL_PRESENTER to credentialPresenter
        ).forEach { (path, source) ->
            listOf(
                "settings.appLanguage" to 1,
                "localeFor(" to 1,
                "localizedConfigurationOf(" to 1,
                "localizedContextOf(" to 1
            ).forEach { (anchor, lowerBound) ->
                val count = source.occurrencesOf(anchor)
                assertTrue(
                    "[$path] 锚点「$anchor」解析出 $count 处，期望 >= $lowerBound" +
                        "——独立入口必须读 appLanguage 并复用 AppShellLocalization 派生（不得复制第二份实现）",
                    count >= lowerBound
                )
            }
        }
    }

    @Test
    fun `三处独立入口与主外壳同锚都provide本地化上下文与配置`() {
        val files = mapOf(
            AUTOFILL_UNLOCK to autofillUnlock,
            CREDENTIAL_UNLOCK to credentialUnlock,
            CREDENTIAL_PRESENTER to credentialPresenter,
            APP_SHELL to appShell
        )
        files.forEach { (path, source) ->
            listOf(
                "CompositionLocalProvider(" to 1,
                "LocalContext provides" to 1,
                "LocalConfiguration provides" to 1
            ).forEach { (anchor, lowerBound) ->
                val count = source.occurrencesOf(anchor)
                assertTrue(
                    "[$path] 锚点「$anchor」解析出 $count 处，期望 >= $lowerBound" +
                        "——配置上下文必须与主外壳同款 provide（ISSUE-P3-365 AC②）",
                    count >= lowerBound
                )
            }
        }
        // 防空转总读数：4 个文件 × 3 个锚点，每锚合计 >= 4（单文件 0 处靠上一组逐文件断言兜住）
        listOf("CompositionLocalProvider(", "LocalContext provides", "LocalConfiguration provides")
            .forEach { anchor ->
                val total = files.values.sumOf { it.occurrencesOf(anchor) }
                assertTrue(
                    "四文件锚点「$anchor」合计 $total 处，期望 >= ${files.size}",
                    total >= files.size
                )
            }
        // 主外壳对照锚自身必须仍经 localeFor 派生（同源判据的「源」侧）
        assertTrue(
            "[$APP_SHELL] 主外壳锚点「localeFor(」解析出 ${appShell.occurrencesOf("localeFor(")} 处，期望 >= 1",
            appShell.occurrencesOf("localeFor(") >= 1
        )
        assertTrue(
            "[$APP_SHELL_LOCALIZATION] 派生函数仍是单点定义（fun localeFor(），期望 >= 1",
            stripped(APP_SHELL_LOCALIZATION).occurrencesOf("fun localeFor(") >= 1
        )
    }

    @Test
    fun `禁止阻塞式挂载与第二份locale实现落地在独立入口`() {
        mapOf(
            AUTOFILL_UNLOCK to autofillUnlock,
            CREDENTIAL_UNLOCK to credentialUnlock,
            CREDENTIAL_PRESENTER to credentialPresenter
        ).forEach { (path, source) ->
            listOf(
                "attachBaseContext" to "不得在挂载期读设置（禁令：attachBaseContext 里 runBlocking 读 DataStore）",
                "runBlocking" to "解锁入口不得阻塞主线程读 DataStore",
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

    @Test
    fun `既有设置读取链不因语言覆盖被顺带改动（AC③）`() {
        mapOf(
            AUTOFILL_UNLOCK to autofillUnlock,
            CREDENTIAL_UNLOCK to credentialUnlock,
            CREDENTIAL_PRESENTER to credentialPresenter
        ).forEach { (path, source) ->
            listOf(
                "settingsRepository.getSettings().first()" to 1,
                "currentTheme = settings.themeMode" to 1
            ).forEach { (anchor, lowerBound) ->
                val count = source.occurrencesOf(anchor)
                assertTrue(
                    "[$path] 既有设置链锚点「$anchor」解析出 $count 处，期望 >= $lowerBound" +
                        "——本条只加语言覆盖，不得改动其余设置读取链",
                    count >= lowerBound
                )
            }
        }
    }

    // ===== 基础设施 =====

    /** 剥注释后的源码（锚点若只写在注释里不得计数——防空转） */
    private fun stripped(path: String): String = stripCommentsOnly(readSource(path))

    /** 子串出现次数（不引正则以免转义歧义；与 ObscuredTouchWiringTest 同款实现） */
    private fun String.occurrencesOf(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val AUTOFILL_UNLOCK = "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt"
        const val CREDENTIAL_UNLOCK = "app/src/main/java/com/keepasskey/app/passkey/CredentialUnlockActivity.kt"
        const val CREDENTIAL_PRESENTER = "app/src/main/java/com/keepasskey/app/passkey/CredentialUnlockPresenter.kt"
        const val APP_SHELL = "app/src/main/java/com/keepasskey/app/ui/KeePasskeyApp.kt"
        const val APP_SHELL_LOCALIZATION = "app/src/main/java/com/keepasskey/app/ui/AppShellLocalization.kt"

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
