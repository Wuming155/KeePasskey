package com.keepasskey.app.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-98（审计 L1）回归：**`AppLog` 的 release 剥离规则必须与声明形态一致**。
 *
 * 缺陷形态：`proguard-rules.pro` 把规则写作 `public static void v(...)`，而 `AppLog` 是 Kotlin
 * `object`、其 `v/d` 为**实例方法**（JVM 签名 `public final void v(String, String)`，未标
 * `@JvmStatic`）→ 规则**永不匹配**（no-op），「双保险」实际只有运行期 `debugEnabled` 一重。
 *
 * 本用例把「规则」与「声明」**交叉锁定**：任一侧改形态而另一侧未同步即失败。
 */
class AppLogProguardRuleTest {

    private val rules: String by lazy { readSource(PROGUARD_PATH) }
    private val appLogSource: String by lazy { stripComments(readSource(APP_LOG_PATH)) }

    @Test
    fun `剥离规则必须使用实例方法签名`() {
        val block = appLogRuleBlock()
        assertTrue("未找到 AppLog 的 -assumenosideeffects 规则", block != null)
        val body = block!!
        assertTrue("规则必须包含实例方法 v：$body", Regex("""public\s+void\s+v\s*\(""").containsMatchIn(body))
        assertTrue("规则必须包含实例方法 d：$body", Regex("""public\s+void\s+d\s*\(""").containsMatchIn(body))
        assertFalse(
            "规则不得使用 public static（AppLog 的 v/d 是实例方法，static 规则永不匹配）",
            Regex("""public\s+static\s+void\s+[vd]\s*\(""").containsMatchIn(body)
        )
    }

    @Test
    fun `AppLog 的 v d 必须确为实例方法（与规则交叉锁定）`() {
        // 交叉锁定：若将来给 v/d 加 @JvmStatic（变为静态方法），规则必须同步改回 static
        assertFalse(
            "AppLog.v/d 若加了 @JvmStatic 则剥离规则须改为 static —— 两侧形态必须一致",
            Regex("""@JvmStatic[\s\S]{0,80}fun\s+[vd]\s*\(""").containsMatchIn(appLogSource)
        )
        assertTrue("AppLog.kt 必须定义实例方法 v(...)", Regex("""fun\s+v\s*\(\s*tag""").containsMatchIn(appLogSource))
        assertTrue("AppLog.kt 必须定义实例方法 d(...)", Regex("""fun\s+d\s*\(\s*tag""").containsMatchIn(appLogSource))
    }

    @Test
    fun `只剥 v d 不得剥离 e w i（release 故障时不得失声）`() {
        val block = appLogRuleBlock() ?: error("未找到 AppLog 规则")
        for (level in listOf("e", "w", "i")) {
            assertFalse(
                "不得剥离 AppLog.$level —— release 下剥掉 e/w/i 会让故障彻底失声",
                Regex("""void\s+$level\s*\(""").containsMatchIn(block)
            )
        }
    }

    /** `<AppLog 的 -assumenosideeffects ... { ... }>` 规则块（含类名行与花括号体） */
    private fun appLogRuleBlock(): String? =
        Regex(
            """-assumenosideeffects\s+class\s+com\.keepasskey\.core\.log\.AppLog\s*\{[^}]*}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(rules)?.value

    /** 剔除块注释与行注释（规则文件里的大段说明含关键字，直接匹配会造成假通过） */
    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PROGUARD_PATH = "app/proguard-rules.pro"
        const val APP_LOG_PATH = "core/src/main/java/com/keepasskey/core/log/AppLog.kt"
        const val ROOT_SEARCH_DEPTH = 6

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
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
