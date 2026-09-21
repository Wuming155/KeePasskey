package com.keepasskey.app.ui.screens.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P1-241` 的**接线守卫**（源码文本断言），与
 * [VaultRemovalPresentationTest]（判定与文案的纯函数用例）配对。
 *
 * 本项要防的失效形态是「缺陷原样复发但**全绿**」：文案改对了、判据也对了，但链条上有一处
 * 各自为政——例如界面用 A 判据选文案、数据层仍按 `id` 形状反推是否删文件。二者只要有一次
 * 不一致，「界面承诺」与「真实行为」就又分家了。四条判据：
 *
 * 1. **界面内不得自判**：弹窗文案与下行删除开关必须来自**同一枚**判据
 *    （`VaultRemovalConfirmation.of(...)` 的 `kind`），界面不得再做 `path` 比较；
 * 2. **数据层只在私有库分支删文件**：`targetFile.delete()` 必须被
 *    `kind == VaultRemovalKind.PRIVATE_FILE` 包住，否则「承诺不删」的场景照样会删；
 * 3. **注册表条目匹配不得按展示名**：外部登记的 `name` 只是展示名，
 *    与其它库的文件名撞车时会摘掉**另一个库**的登记（同函数内相邻缺陷，本批一并收紧）；
 * 4. **旧的一套共用键不得复活**：`db_picker_delete_confirm_*` 已按存储类型分列，
 *    重新出现即意味着两类库又在共用同一套措辞。
 */
class VaultRemovalWiringTest {

    private val screenSource: String
        get() = readRepoFile(PICKER_SCREEN)

    private val coordinatorSource: String
        get() = readRepoFile(COORDINATOR)

    /** `VaultLifecycleCoordinator.removeDatabase` 的方法体切片（中文注释长度不可预期，故按方法切片） */
    private val removeMethodBody: String
        get() = coordinatorSource
            .substringAfter(FN_REMOVE_DATABASE, missingDelimiterValue = "")
            .substringBefore(FN_NEXT_ANCHOR, missingDelimiterValue = "")

    @Test
    fun `确认弹窗的文案与下行删除开关同源`() {
        assertTrue(
            "[$PICKER_SCREEN] 未用 VaultRemovalConfirmation.of(...) 派生确认弹窗的措辞与动作",
            screenSource.contains("VaultRemovalConfirmation.of(")
        )
        assertTrue(
            "[$PICKER_SCREEN] 下行给数据层的删除开关必须取同一枚判据（confirmation.kind）——" +
                "两处各判一次即「文案与行为再次分家」的复发形态（AC①）",
            screenSource.contains("onRemoveDatabase(db.id, confirmation.kind)")
        )
        assertTrue(
            "[$PICKER_SCREEN] 未把应用私有目录交给判据（判据退化后私有库会被判成外部库）",
            screenSource.contains("filesDir?.absolutePath")
        )
        assertFalse(
            "[$PICKER_SCREEN] 界面自行比较路径得到私有/外部结论——判据必须单点化（AC④）",
            screenSource.contains("parentFile")
        )
    }

    @Test
    fun `数据层只在私有库分支删除物理文件`() {
        assertTrue("[$COORDINATOR] 未切出 removeDatabase 方法体（锚点是否已变更？）", removeMethodBody.isNotBlank())

        val gate = removeMethodBody.indexOf("kind == VaultRemovalKind.PRIVATE_FILE")
        val deletion = removeMethodBody.indexOf("targetFile.delete()")
        assertTrue(
            "[$COORDINATOR] 删文件未由 kind == PRIVATE_FILE 守卫——界面承诺「不删物理文件」的场景会被真的删掉（AC①）",
            gate >= 0
        )
        assertTrue("[$COORDINATOR] 未切到删除动作", deletion >= 0)
        assertTrue(
            "[$COORDINATOR] 删除动作必须位于私有库守卫**之后**（顺序即语义：先判对象再动手）",
            deletion > gate
        )
    }

    @Test
    fun `注册表条目匹配不得按展示名`() {
        assertFalse(
            "[$COORDINATOR] 注册表匹配又按 it.name == id —— 外部登记的 name 只是展示名，" +
                "与其它库文件名撞车时会摘掉另一个库的登记（同函数内相邻缺陷）",
            removeMethodBody.contains("it.name == id")
        )
        assertTrue(
            "[$COORDINATOR] 注册表匹配须按 id / path（外部登记的 id 即其 path）",
            removeMethodBody.contains("it.id == id || it.path == id")
        )
    }

    @Test
    fun `按存储类型分列的两套确认键不得退回共用一套`() {
        assertFalse(
            "[$PICKER_SCREEN] 又引用旧的共用键 db_picker_delete_confirm_*（两类库共用一套措辞的复发形态）",
            screenSource.contains("db_picker_delete_confirm_")
        )
        listOf(zhStrings, enStrings).forEachIndexed { index, source ->
            val label = if (index == 0) "values" else "values-en"
            listOf(
                "db_picker_remove_private_title",
                "db_picker_remove_private_desc",
                "db_picker_remove_private_confirm",
                "db_picker_remove_external_title",
                "db_picker_remove_external_desc",
                "db_picker_remove_external_confirm"
            ).forEach { key ->
                assertTrue("$label 缺少键 $key（两套措辞必须成对存在）", source.contains("name=\"$key\""))
            }
            assertFalse(
                "$label 仍残留旧的共用键 db_picker_delete_confirm_*（旧键与新键并存会让「改了一套」看不出来）",
                source.contains("db_picker_delete_confirm_")
            )
        }
    }

    private val zhStrings: String by lazy { readRepoFile("app/src/main/res/values/strings.xml") }

    private val enStrings: String by lazy { readRepoFile("app/src/main/res/values-en/strings.xml") }

    /** 源码全文；app 模块测试工作目录为 `app/`，向上回溯定位仓库根 */
    private fun readRepoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(ROOT_SEARCH_DEPTH) {
            val candidate = dir ?: return@repeat
            val target = File(candidate, relativePath)
            if (target.isFile) return target.readText()
            dir = candidate.parentFile
        }
        error("无法定位仓库文件：$relativePath（起始：${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 4

        /** 方法体切片锚点：起于目标方法签名，止于其后继方法签名 */
        const val FN_REMOVE_DATABASE = "suspend fun removeDatabase"
        const val FN_NEXT_ANCHOR = "suspend fun importExternalDatabase"

        const val COORDINATOR =
            "app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt"
        const val PICKER_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/DatabasePickerScreen.kt"
    }
}
