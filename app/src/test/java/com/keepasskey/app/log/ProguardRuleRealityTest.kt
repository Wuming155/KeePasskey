package com.keepasskey.app.log

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `proguard-rules.pro` 的**实物对照**守卫（`ISSUE-P3-507`；守卫模式照 `ISSUE-P3-98` 的
 * [AppLogProguardRuleTest]）。
 *
 * ## 为什么需要它
 *
 * 本仓实际解析的 okhttp-android 5.5.0 里 `okhttp3.internal.publicsuffix.PublicSuffixDatabase`
 * **既无 native 方法也无 `findSuffix` 成员**，而规则文件里那条
 * `-keepclassmembers … { native byte[] findSuffix(java.lang.String[]); }` 自初版起就在
 * （`git log -S` 仅一个提交、注释无任何依据）⇒ **恒不匹配、静默 no-op**，与文件头自立的
 * 「据实最小保留」原则相悖。它之所以能存活这么久，是因为**没有任何守卫**去问一句
 * 「这条规则指向的类 / 成员真的存在吗」——`AppLogProguardRuleTest` 只锁了 AppLog 那一族。
 *
 * 本用例补上这一层：把规则文件里**完全限定类名**（不含通配）的 keep 规则拿到当前测试
 * classpath（即真实依赖实物）上做 `Class.forName`，并对已删除的那条 no-op 规则做
 * 「复活条件」反查。
 *
 * ## 覆盖边界（如实声明）
 *
 * 1. 只校验**完全限定类名**的规则；`**` / `* extends …` 一类的通配规则不在本面内
 *    （它们的「存在性」无法用单个类名表达）；
 * 2. 成员签名只做**单条专项**校验（[`已删除的 no-op 规则在依赖实物中确实不存在`]），
 *    不对全部规则做通用签名解析——那是 R8 自己的职责；
 * 3. 本用例**不**替代 `assembleRelease`：规则改动是否真被 R8 接受，仍须跑一次发布构建。
 */
class ProguardRuleRealityTest {

    /**
     * 规则正文（已剔除注释）。
     *
     * 两级剥离：先剥 Kotlin 风格注释（[stripCommentsOnly]，处理字符串内的 `#`），
     * 再剥 ProGuard 自己的 `#` 行注释——本仓规则文件有**大段 `#` 说明文字**，其中会
     * 提到被删除规则的类名 / 成员名（如 `findSuffix`），不剥就会造成「规则仍在」的假命中。
     */
    private val rules: String by lazy { stripHashComments(stripCommentsOnly(readRepoFile(PROGUARD_PATH))) }

    @Test
    fun `精确类名的 keep 规则必须在依赖实物中真实存在`() {
        val classNames = exactClassRuleTargets()
        assertTrue(
            "未从 $PROGUARD_PATH 解析到任何完全限定类名的 keep 规则（解析结构是否已变更？）——"
                + "本守卫已失去判别对象，不得据此认为检查通过",
            classNames.size >= MIN_EXACT_CLASS_RULES
        )

        val missing = classNames.filter { !classExists(it) }
        assertTrue(
            "以下 keep 规则指向的类在当前依赖实物（测试 classpath）中不存在：$missing——"
                + "规则恒不匹配即静默 no-op（ISSUE-P3-507 同型）；请删除该规则或改写为真实存在的类 / 签名。"
                + "若确为「将来才会出现的类」，请在规则处写明依据并同步本用例的白名单。",
            missing.isEmpty()
        )
    }

    @Test
    fun `已删除的 no-op 规则在依赖实物中确实不存在`() {
        // 复活条件反查（fail-closed）：一旦依赖升级真的引入了该 native 成员，
        // 这里会红并提示把 keep 规则加回来——而不是让「曾经删错过」这一事实被永久遗忘。
        val methods = try {
            Class.forName(PSL_CLASS).declaredMethods.map { it.name }
        } catch (e: ClassNotFoundException) {
            return // 类本身都不存在 ⇒ 更无从复活，规则保持删除状态（另由上一用例给出结论）
        }
        assertTrue(
            "$PSL_CLASS 现已存在 `findSuffix` 成员（$methods）——ISSUE-P3-507 删除的那条 "
                + "-keepclassmembers 规则**应当恢复**（并同步本用例与规则注释）；"
                + "若成员形态与旧规则不同，请按实际签名改写而非照抄旧签名。",
            "findSuffix" !in methods
        )
    }

    @Test
    fun `已删除的 no-op 规则不得复活`() {
        assertTrue(
            "已删除的 PublicSuffixDatabase keep 规则不得复活——如需恢复请先核对成员签名真实存在"
                + "（见「已删除的 no-op 规则在依赖实物中确实不存在」用例）",
            !rules.contains("findSuffix")
        )
    }

    /** 剥 ProGuard 的 `#` 行注释（规则文件里的大段说明不得参与规则匹配） */
    private fun stripHashComments(source: String): String = source.lineSequence()
        .joinToString("\n") { line ->
            val idx = line.indexOf('#')
            if (idx >= 0) line.substring(0, idx) else line
        }

    /** 规则文件中 `-keep*` / `-assumenosideeffects` 的完全限定类名目标（排除通配与 `extends`） */
    private fun exactClassRuleTargets(): List<String> {
        val spec = Regex(
            """^-\s*(?:keep|keepclassmembers|keepnames|keepclasseswithmembers|assumenosideeffects)[a-zA-Z,]*\s+class\s+([^\s{]+)""",
            RegexOption.MULTILINE
        )
        return spec.findAll(rules).map { it.groupValues[1] }
            .filter { it.matches(Regex("""[A-Za-z0-9_.$]+""")) } // 去通配（`**` / `* extends …`）
            .distinct()
            .toList()
    }

    private fun classExists(fqcn: String): Boolean = try {
        Class.forName(fqcn)
        true
    } catch (e: ClassNotFoundException) {
        false
    } catch (e: LinkageError) {
        // Android 平台 stub 一类的加载错误不等于「类不存在」——记为真存在，交由 R8 侧判定
        true
    }

    private fun readRepoFile(relativePath: String): String {
        val file = File(repositoryRoot, relativePath)
        assertTrue("文件不存在（是否被重命名/移动）：$relativePath", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PROGUARD_PATH = "app/proguard-rules.pro"
        const val PSL_CLASS = "okhttp3.internal.publicsuffix.PublicSuffixDatabase"

        /** 现形态下至少有 WorkDatabase_Impl / AppLog / android.util.Log 三条；低于此值即解析失效 */
        const val MIN_EXACT_CLASS_RULES = 3
        const val ROOT_SEARCH_DEPTH = 6

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 $ROOT_SEARCH_DEPTH 层）")
        }
    }
}
