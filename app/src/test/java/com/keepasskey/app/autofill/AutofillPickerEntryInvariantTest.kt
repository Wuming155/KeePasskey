package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手动选择器入口的**设计不变量**（**ISSUE-P3-125 ①**）。
 *
 * ## 被裁决的问题
 *
 * 复核条目曾指出「`buildPickerDataset` 在 `appendUnlockedDatasets` 之后**无条件**调用，无候选数门槛
 * ⇒ 严格匹配设计对任意应用失效」。本用例把复核结论**固化为可执行的不变量**：
 * 该「无条件」是**设计**，不是疏漏——选择器是**用户显式指认调用方**的唯一入口，也正是
 * `ISSUE-P1-24`（首现授权）与 `ISSUE-P2-46`（`android://` 首次绑定）的**写入点**；
 * 若按「零匹配才挂」或「零匹配就不挂」设门槛，它会在**最需要的时候**（零匹配）不可达。
 *
 * 严格匹配保护的是**自动下发**：`AutofillCandidateRanker` 先在候选层完成域归属（`ISSUE-P2-07`）
 * 与包名维度绑定（`ISSUE-P2-46`）判定；选择器路径在用户显式点选前**不携带任何明文**
 * （数据集值恒为 `null`），点选后由 `AutofillPickerActivity` 在受保护窗口内展示调用方归属
 * （`ISSUE-P2-70`）并记录绑定。
 */
class AutofillPickerEntryInvariantTest {

    private val service = readSource(SERVICE)
    private val builders = readSource(BUILDERS)

    @Test
    fun `选择器入口必须无条件挂入`() {
        val callIndex = service.indexOf("buildPickerDataset(")
        assertTrue("未找到选择器入口调用点（守卫失效，需更新锚点）", callIndex >= 0)

        // 调用点之前 200 字符内不得出现条件包裹——「零匹配才挂 / 零匹配就不挂」都会让它失效：
        // 选择器恰恰是零匹配时唯一的用户出口。
        val window = service.substring((callIndex - 200).coerceAtLeast(0), callIndex)
        assertFalse(
            "选择器入口不得被候选数门槛包裹（它是零匹配时唯一可达的用户出口，" +
                "也是 ISSUE-P1-24 / P2-46 的绑定写入点）",
            Regex("""if\s*\([^)]*count[^)]*\)\s*\{""").containsMatchIn(window)
        )
    }

    @Test
    fun `选择器文案不得自称命中`() {
        assertTrue(
            "入口标题必须自述为「搜索全部条目」类的主动动作，而非匹配结果",
            builders.contains("R.string.autofill_picker_entry_title")
        )
        assertTrue(
            "入口副标题必须自述为「手动选择」",
            builders.contains("R.string.autofill_picker_entry_sub")
        )
    }

    @Test
    fun `选择器数据集在用户点选前不得携带明文`() {
        // 值以 null 占位：真正的凭据值只在用户于选择器内显式选中并确认后，
        // 经 AutofillManager.EXTRA_AUTHENTICATION_RESULT 回传（官方认证数据集语义）。
        assertTrue(
            "用户名 / 口令字段必须以 null 占位",
            builders.contains("pickerBuilder.setValue(usernameId, null)") ||
                builders.contains("pickerBuilder.setValue(passwordId, null)")
        )
    }

    @Test
    fun `自动候选仍须经严格匹配与绑定门控`() {
        // 与上一条互补：放宽的只有「手动指认」这一条路，自动候选一条都没放宽
        assertTrue(
            "自动候选必须经 AutofillCandidateRanker 严格匹配",
            builders.contains("AutofillCandidateRanker.rank(")
        )
        assertTrue(
            "android:// 维度必须经包名 + 签名绑定门控",
            builders.contains("AndroidPackageBindingPolicy.isPackageDimensionAuthorized(")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SERVICE = "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"
        const val BUILDERS = "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt"

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
