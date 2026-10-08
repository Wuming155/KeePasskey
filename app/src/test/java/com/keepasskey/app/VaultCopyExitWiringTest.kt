package com.keepasskey.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-529` AC③：四处「保留副本」出口的**接线守卫**（源码文本断言）。
 *
 * 本项要防的失效形态是「机制存在 ≠ 已接线」——本仓反复踩过：判据 / 文案 / 底层能力都写好了，
 * 但链条上有一段各自为政（对话框按钮没接上回调、控制器没把两个动作串起来、界面另判一次），
 * 结果是「看得见、点不动」的**假按钮**，而单元测试与预览都发现不了。六条判据覆盖四处出口：
 *
 * 1. 库身份覆盖对话框：声明了第三条出口回调，且真的渲染了新文案键；
 * 2. 列表页：把新回调接到 ViewModel 的实际动作（不是空 lambda）；
 * 3. 同步控制器：新动作**先**另存副本、**成功才**覆盖（顺序即语义）；
 * 4. 移除对话框：按 `saveCopyRes` 非空呈现新出口（避免外部库也冒出一个副本按钮）；
 * 5. 云端同名导入：确实走统一的 `VaultCopyNaming` 命名，且**不再**把同名冲突当作错误拒绝；
 * 6. 冲突策略文案：`每次询问` 一档显式给出「保留两份」口径（AC① 第四处）。
 */
class VaultCopyExitWiringTest {

    private val takeoverDialog: String get() = readRepoFile(TAKEOVER_DIALOG)
    private val vaultListScreen: String get() = readRepoFile(VAULT_LIST_SCREEN)
    private val syncController: String get() = readRepoFile(SYNC_CONTROLLER)
    private val removalDialog: String get() = readRepoFile(REMOVAL_DIALOG)
    private val cloudImporter: String get() = readRepoFile(CLOUD_IMPORTER)

    @Test
    fun `库身份覆盖对话框声明并渲染第三条出口`() {
        assertTrue(
            "[$TAKEOVER_DIALOG] 未声明 onConfirmKeepingCopy 回调（第三条出口不存在 = 用户仍只有覆盖/取消）",
            takeoverDialog.contains("onConfirmKeepingCopy")
        )
        assertTrue(
            "[$TAKEOVER_DIALOG] 未渲染新出口文案键 sync_vault_takeover_confirm_keep_copy",
            takeoverDialog.contains("R.string.sync_vault_takeover_confirm_keep_copy")
        )
    }

    @Test
    fun `列表页把第三条出口接到 ViewModel 真实动作`() {
        assertTrue(
            "[$VAULT_LIST_SCREEN] 对话框未接上 onConfirmKeepingCopy",
            vaultListScreen.contains("onConfirmKeepingCopy = viewModel::confirmBindingTakeoverKeepingCopy")
        )
    }

    @Test
    fun `同步控制器先另存副本_成功才执行覆盖`() {
        val body = syncController.substringAfter("fun confirmBindingTakeoverKeepingCopy", missingDelimiterValue = "")
        assertTrue("[$SYNC_CONTROLLER] 未切到 confirmBindingTakeoverKeepingCopy 方法体（锚点是否已变更？）", body.isNotBlank())
        val backup = body.indexOf("backupCloudVaultCopy(")
        val takeover = body.indexOf("confirmVaultBindingTakeover(")
        assertTrue("[$SYNC_CONTROLLER] 未调用云端副本另存", backup >= 0)
        assertTrue("[$SYNC_CONTROLLER] 未调用整库覆盖", takeover >= 0)
        assertTrue("[$SYNC_CONTROLLER] 顺序错：必须先另存副本、再覆盖（顺序即语义）", backup < takeover)
        assertTrue(
            "[$SYNC_CONTROLLER] 覆盖必须由「另存成功」这一分支守卫（失败即中止，绝不覆盖）",
            body.contains("KdbxResult.Success")
        )
    }

    @Test
    fun `移除对话框按 saveCopyRes 呈现新出口`() {
        assertTrue(
            "[$REMOVAL_DIALOG] 未按 confirmation.saveCopyRes 呈现「另存副本后移除」按钮",
            removalDialog.contains("confirmation.saveCopyRes")
        )
        assertTrue(
            "[$REMOVAL_DIALOG] 新按钮必须接上 onSaveCopy 回调（否则是假按钮）",
            removalDialog.contains("onClick = onSaveCopy")
        )
    }

    @Test
    fun `云端同名导入走统一副本命名且不再把冲突当错误拒绝`() {
        assertTrue(
            "[$CLOUD_IMPORTER] 未走统一副本命名（VaultCopyNaming）——命名会与其余三处漂移",
            cloudImporter.contains("VaultCopyNaming.uniqueCopyTarget(")
        )
        assertFalse(
            "[$CLOUD_IMPORTER] 又用 picker_cloud_local_conflict 构造 isError = true —— " +
                "同名冲突已改为一律以副本落地，不得再退回「拒绝 + 让用户改名」",
            cloudImporter.contains("R.string.picker_cloud_local_conflict, isError = true")
        )
    }

    @Test
    fun `冲突策略文案显式给出保留两份口径`() {
        listOf(zhStrings, enStrings).forEachIndexed { index, source ->
            val label = if (index == 0) "values" else "values-en"
            val desc = Regex("<string name=\"sync_conflict_prompt_desc\">([^<]*)</string>").find(source)
            assertTrue("$label 缺少键 sync_conflict_prompt_desc", desc != null)
            assertTrue(
                "$label 的「每次询问」说明必须点明「保留两份」（AC① 第四处：策略项语义明确），实际：${desc!!.groupValues[1]}",
                desc.groupValues[1].contains("keep both") || desc.groupValues[1].contains("保留两份")
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

        const val TAKEOVER_DIALOG =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListDialogs.kt"
        const val VAULT_LIST_SCREEN =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListScreen.kt"
        const val SYNC_CONTROLLER =
            "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListSyncController.kt"
        const val REMOVAL_DIALOG =
            "app/src/main/java/com/keepasskey/app/ui/screens/database/VaultRemovalConfirmDialog.kt"
        const val CLOUD_IMPORTER =
            "app/src/main/java/com/keepasskey/app/sync/CloudVaultImporter.kt"
    }
}
