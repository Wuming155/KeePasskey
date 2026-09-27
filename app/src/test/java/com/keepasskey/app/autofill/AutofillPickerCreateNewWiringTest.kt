package com.keepasskey.app.autofill

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 选择器空结果「就地新建」接线守卫（`ISSUE-P3-345` / `PD-51`）。
 *
 * 选择器是 Compose UI，空态按钮的**渲染**宿主不可证伪（真机 AC 承担）；但两条
 * **只读门控与回程接线**是纯接线事实，源码守卫即可锁定——漏掉任何一条，
 * 要么出现「只读会话也能点新建」的假入口（`PD-50`），要么新建后回不到填充链路
 * （保存成功却把用户丢在空结果页）。
 */
class AutofillPickerCreateNewWiringTest {

    @Test
    fun `空态新建入口必须由只读会话门控`() {
        val activity = stripCommentsOnly(
            File("src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt").readText()
        )
        assertTrue(
            "canCreateNew 必须取自 isSessionReadOnly 的反值（只读不呈现，PD-50 / PD-51 裁决 4）",
            activity.contains("canCreateNew = !vaultRepository.isSessionReadOnly()")
        )
        assertTrue(
            "onCreateNew 必须接线草稿页拉起（completed 防重入）",
            activity.contains("onCreateNew = {") && activity.contains("draftLauncher.launch(")
        )
    }

    @Test
    fun `新建完成后必须复用既有 confirmAndFill 交付链`() {
        val activity = stripCommentsOnly(
            File("src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt").readText()
        )
        assertTrue(
            "草稿回程必须经 ActivityResultLauncher（不得用已废弃的 onActivityResult）",
            activity.contains("ActivityResultContracts.StartActivityForResult()")
        )
        assertTrue(
            "回程必须经草稿页伴生的统一解析（extras 构造/解析集中防漂移）",
            activity.contains("PasswordDraftActivity.draftEntryIdFrom(result)")
        )
        assertTrue(
            "保存成功后必须走 confirmAndFill（唯一交付链，不得另开数据集构造路径）",
            activity.contains("confirmAndFill")
        )
        assertTrue(
            "入口 Intent 必须经伴生 createIntent 构造",
            activity.contains("PasswordDraftActivity.createIntent(")
        )
    }

    @Test
    fun `空态按钮不得无条件呈现`() {
        val screen = stripCommentsOnly(
            File("src/main/java/com/keepasskey/app/autofill/AutofillPickerScreen.kt").readText()
        )
        assertTrue(
            "空态按钮必须包在 if (canCreateNew) 内",
            Regex("""if \(canCreateNew\) \{[^}]*Button""").containsMatchIn(screen)
        )
    }
}
