package com.keepasskey.app.autofill

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.data.childdb.InMemorySharedPreferences
import com.keepasskey.app.data.repository.EntryTotpSnapshot
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.app.ui.screens.settings.SettingsUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-43 / ISSUE-P2-71 回归：**自动填充面「默认开启」的两条外泄通道**。
 *
 * - `autofillCopyTotp`（P2-43）：开启后每次填充确认都把 TOTP 动态码写入系统剪贴板；
 * - `inlineSuggestionsEnabled`（P2-71）：开启后把候选**用户名 / 条目标题**交给系统 IME
 *   （可能是第三方 / 云端联想键盘）。
 *
 * 两项均改为**默认关闭**，因此本用例锁三件事：
 * 1. **默认值**——数据类与 UI 状态两处投影都必须是 false；
 * 2. **单键读取通道的缺省**——`ExtendedSettingsStore` 的两个 `isXxxEnabled()` 自带
 *    硬编码兜底（**不读**数据类默认值），是「改完默认值仍默认开启」的真实陷阱；
 *    「无持久化层」与「有持久化层但键缺失」两条路径都按实际取值锁定；
 * 3. **开关未被硬禁用**——用户显式开启后仍可持久化并生效（否则「默认关闭」会退化成
 *    「功能被删」）。
 *
 * 另含：默认配置下 TOTP 复制策略对「确有 TOTP 的条目」也不得触达剪贴板（端到端口径），
 * 以及设置页文案须如实披露两条通道的代价（AC②，中英双语文案静态断言）。
 */
class AutofillPrivacyDefaultsTest {

    // ---- AC①：默认值 ----

    @Test
    fun `两通道默认值在数据类与 UI 状态两处均须关闭`() {
        val defaults = ExtendedSettings()
        assertFalse("ExtendedSettings.autofillCopyTotp 默认须关闭（ISSUE-P2-43）", defaults.autofillCopyTotp)
        assertFalse(
            "ExtendedSettings.inlineSuggestionsEnabled 默认须关闭（ISSUE-P2-71）",
            defaults.inlineSuggestionsEnabled
        )

        val uiState = SettingsUiState()
        assertFalse("SettingsUiState.autofillCopyTotp 默认须关闭", uiState.autofillCopyTotp)
        assertFalse("SettingsUiState.inlineSuggestionsEnabled 默认须关闭", uiState.inlineSuggestionsEnabled)
    }

    /**
     * 关键回归锁：`ExtendedSettingsStore` 的两个单键读取方法此前把缺省值**硬编码为 true**
     * （`?: true`），仅改数据类默认值不会改变「无持久化层 / 键缺失」判定——
     * 即「默认关闭」会被该兜底悄悄抵消。
     *
     * ENG-20261028-12 整改：原用例在该行为读数之外再按源码反查 `K_XXX, true` 字面量。
     * 那只能命中当时的写法（换个变量、换个缺省常量即漏判或误红），而真实陷阱是**键缺失时的取值**，
     * 已由下面的「有持久化层但键缺失」行为读数直接覆盖。
     */
    @Test
    fun `单键读取通道的缺省值不得回退为开启`() {
        val noPrefs = ExtendedSettingsStore(null)
        assertFalse("无持久化层时 TOTP 复制单键读取缺省须为关闭", noPrefs.isAutofillCopyTotpEnabled())
        assertFalse("无持久化层时内联建议单键读取缺省须为关闭", noPrefs.isInlineSuggestionsEnabled())

        val emptyPrefs = ExtendedSettingsStore(inMemoryContext(InMemorySharedPreferences()))
        assertFalse(
            "键缺失时 TOTP 复制单键读取须回落关闭（历史陷阱：硬编码 true 兜底抵消数据类默认值）",
            emptyPrefs.isAutofillCopyTotpEnabled()
        )
        assertFalse(
            "键缺失时内联建议单键读取须回落关闭（同上）",
            emptyPrefs.isInlineSuggestionsEnabled()
        )
    }

    // ---- AC③（P2-43）：关闭后不触达剪贴板 ----

    @Test
    fun `默认配置下即使条目确有 TOTP 也不得触达剪贴板`() {
        val store = ExtendedSettingsStore(null)
        val snapshotWithCode = EntryTotpSnapshot(
            code = "123456",
            periodSeconds = 30,
            digits = 6,
            algorithm = "SHA1"
        )
        // 端到端口径：真实默认读取通道 × 真实决策层（而非直接传 false 短路）
        assertFalse(
            "默认关闭时复制策略必须为 false（否则剪贴板必被写入）",
            AutofillTotpCopyPolicy.shouldCopy(
                copyTotpEnabled = store.isAutofillCopyTotpEnabled(),
                snapshot = snapshotWithCode
            )
        )
    }

    // ---- 开关未被硬禁用 ----

    @Test
    fun `用户显式开启后两通道仍可持久化并生效`() {
        val prefs = InMemorySharedPreferences()
        val store = ExtendedSettingsStore(inMemoryContext(prefs))

        store.save(ExtendedSettings(autofillCopyTotp = true, inlineSuggestionsEnabled = true))

        assertTrue("显式开启 TOTP 复制后单键读取须为真", store.isAutofillCopyTotpEnabled())
        assertTrue("显式开启内联建议后单键读取须为真", store.isInlineSuggestionsEnabled())
        assertTrue("数据类往返不得丢失显式开启的取值", store.load().autofillCopyTotp)
        assertTrue("数据类往返不得丢失显式开启的取值", store.load().inlineSuggestionsEnabled)
    }

    @Test
    fun `内联建议构建器以开关为前置门控（关闭即不下发展示）`() {
        val disabled = AutofillInlinePresentationFactory(inMemoryContext(InMemorySharedPreferences()), ExtendedSettingsStore(null))
        assertFalse("默认配置下构建器的开关判定须为 false", disabled.isEnabled())
        // 请求为 null 时任何配置都返回 null（官方降级链末端）；
        // 真实请求下「关闭恒不下发、开启下发与降级」契约已由 AutofillPlatformContractsDeviceTest 真实对象端到端验证
        assertTrue(disabled.build(null, "user", "title") == null)
    }

    // ---- AC②：设置页如实披露 ----

    @Test
    fun `设置页文案须披露 TOTP 入剪贴板与候选名入输入法`() {
        val zh = readSource("app/src/main/res/values/strings.xml")
        val en = readSource("app/src/main/res/values-en/strings.xml")

        assertTrue(
            "中文副本 TOTP 文案须点明「剪贴板」并要求默认关闭说明：${stringBody(zh, "autofill_copy_totp_sub")}",
            stringBody(zh, "autofill_copy_totp_sub").let { it.contains("剪贴板") && it.contains("默认关闭") }
        )
        assertTrue(
            "中文内联建议文案须点明「输入法」通道：${stringBody(zh, "autofill_inline_sub")}",
            stringBody(zh, "autofill_inline_sub").let { it.contains("输入法") && it.contains("默认关闭") }
        )
        assertTrue(
            "英文副本 TOTP 文案须点明 clipboard：${stringBody(en, "autofill_copy_totp_sub")}",
            stringBody(en, "autofill_copy_totp_sub").let { it.contains("clipboard") && it.contains("Off by default") }
        )
        assertTrue(
            "英文内联建议文案须点明 input method：${stringBody(en, "autofill_inline_sub")}",
            stringBody(en, "autofill_inline_sub").let { it.contains("input method") && it.contains("Off by default") }
        )
    }

    // ---- 辅助 ----

    private fun inMemoryContext(prefs: SharedPreferences): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private fun stringBody(xml: String, name: String): String =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: error("未找到字符串资源 $name")

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码/资源文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
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
