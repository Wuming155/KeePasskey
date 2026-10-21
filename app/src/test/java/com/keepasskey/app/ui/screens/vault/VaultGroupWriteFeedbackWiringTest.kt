package com.keepasskey.app.ui.screens.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy

/** ISSUE-P3-644：分组图标保存结果与只读菜单语义的 JVM 接线守卫。 */
class VaultGroupWriteFeedbackWiringTest {

    @Test
    fun `图标更新成功失败和只读路径反馈均与写入结果一致`() = runTest {
        val testScope = this
        suspend fun exercise(readOnly: Boolean, result: KdbxResult<Unit>): Pair<Int, List<UiMessage>> {
            var saveCalls = 0
            val repository = Proxy.newProxyInstance(
                VaultRepository::class.java.classLoader,
                arrayOf(VaultRepository::class.java)
            ) { _, method, _ ->
                if (method.name == "saveGroup") {
                    saveCalls++
                    result
                } else {
                    null
                }
            } as VaultRepository
            val messages = mutableListOf<UiMessage>()
            val controller = VaultListActionController(
                repository = repository,
                scope = testScope,
                strings = StringsProvider { _, _ -> "failure" },
                clipboardSecurityManager = null,
                host = VaultListActionHost(
                    isReadOnly = { readOnly },
                    currentGroupId = { null },
                    currentGroups = { emptyList() },
                    currentEntryIds = { emptyList() },
                    onMessage = messages::add
                )
            )
            controller.changeGroupIcon(VaultGroup(id = "g1", name = "Logins"), "bank")
            advanceUntilIdle()
            return saveCalls to messages
        }

        val success = exercise(readOnly = false, result = KdbxResult.Success(Unit))
        assertEquals(1, success.first)
        assertEquals(R.string.vault_group_icon_updated, success.second.single().resId)

        val failure = exercise(
            readOnly = false,
            result = KdbxResult.Failure(IllegalStateException("simulated save failure"))
        )
        assertEquals(1, failure.first)
        assertEquals(R.string.op_failed, failure.second.single().resId)

        val readOnly = exercise(readOnly = true, result = KdbxResult.Success(Unit))
        assertEquals(0, readOnly.first)
        assertEquals(R.string.readonly_save_rejected, readOnly.second.single().resId)
    }

    @Test
    fun `图标反馈必须按实际保存结果分支`() {
        val body = functionBody(readSource(CONTROLLER), "fun changeGroupIcon(")
        val successCall = "onMessage(UiMessage(R.string.vault_group_icon_updated))"
        val successOccurrences = Regex(Regex.escape(successCall)).findAll(body).toList()
        assertEquals(
            "成功文案只能有一处（多处即可能出现在错误分支里）",
            1,
            successOccurrences.size
        )
        val successIndex = successOccurrences.single().range.first

        // 守卫的**判据**是「保存成功才报成功」，不锁定具体语法形态：
        // 生产实现与同类的 `renameGroup` / `createGroup` / `deleteGroup` 一致，用
        // `if (result is KdbxResult.Success) … else …`；历史上恢复版的该项曾用 `when` 分支。
        // 两种写法表达同一语义，故本条按「守卫位置 + 分支顺序」判定，判定强度不降：
        // 成功文案既须位于成功守卫之后，又须严格早于失败分支，失败分支仍须如实上报 op_failed。
        val successGuard = body.indexOf("is KdbxResult.Success")
        assertTrue("保存结果必须显式判断 KdbxResult.Success", successGuard >= 0)
        assertTrue("成功文案必须位于成功守卫之后", successIndex > successGuard)

        val failureBranch = listOf("is KdbxResult.Failure", "} else {")
            .map { body.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        assertNotNull("保存失败必须有独立分支", failureBranch)
        assertTrue(
            "成功文案必须早于失败分支（不得在失败分支内谎报成功）",
            successIndex < requireNotNull(failureBranch)
        )
        assertTrue("保存失败须如实显示失败原因", body.substring(requireNotNull(failureBranch)).contains("R.string.op_failed"))
    }

    @Test
    fun `只读分组菜单禁用写入口并说明原因且装配只读状态`() {
        val row = readSource(ROW)
        // ISSUE-P3-644 拆出 GroupRowMenu 后，禁用与原因说明落在该菜单件里；
        // 母件负责把只读状态传下去。两者都锁，避免「只传参不生效」或「只生效没接线」。
        val menuBody = functionBody(row, "private fun GroupRowMenu(")
        assertTrue("只读状态必须禁用重命名", menuBody.contains("enabled = !readOnly"))
        assertEquals(
            "重命名 / 换图标 / 删除三个写菜单项都必须受只读状态禁用",
            3,
            Regex("enabled = !readOnly").findAll(menuBody).count()
        )
        assertTrue("只读菜单须说明不能保存的原因", menuBody.contains("stringResource(R.string.readonly_save_rejected)"))
        val rowBody = functionBody(row, "fun KeePassGroupRow(")
        assertTrue("分组行必须把真实只读状态传给菜单件", rowBody.contains("readOnly = readOnly"))
        val content = readSource(CONTENT)
        assertTrue("行菜单必须接收真实会话只读状态", content.contains("readOnly = uiState.isReadOnly"))
        val controller = readSource(CONTROLLER)
        val body = functionBody(controller, "fun changeGroupIcon(")
        assertTrue("控制器仍保留只读硬守卫", body.contains("if (isReadOnly())"))
        // 拒绝反馈经控制器唯一的收口方法给出（`rejectReadOnlyWrite()`，全部写操作共用同一文案），
        // 故「给了可理解反馈」的判据 = 守卫调用该收口 + 收口内确实是只读拒绝文案。
        assertTrue("控制器只读拒绝必须走统一反馈收口", body.contains("rejectReadOnlyWrite()"))
        assertTrue(
            "控制器只读拒绝须给可理解反馈",
            functionBody(controller, "private fun rejectReadOnlyWrite(")
                .contains("UiMessage(R.string.readonly_save_rejected)")
        )
    }

    @Test
    fun `只读状态隐藏批量入口并禁用批量写按钮且说明原因`() {
        val topBars = readSource(TOP_BARS)
        assertTrue("只读下不得提供进入批量模式的溢出入口", topBars.contains("onSelectEntriesClick != null && !isReadOnly"))
        val batchBarBody = functionBody(topBars, "fun VaultListBatchModeTopBar(")
        assertEquals(
            "全选、移动、删除三个批量按钮都必须受只读状态禁用",
            3,
            Regex("enabled = !isReadOnly").findAll(batchBarBody).count()
        )
        assertTrue("批量工具栏须说明只读原因", batchBarBody.contains("stringResource(R.string.readonly_save_rejected)"))
        val content = readSource(CONTENT)
        assertTrue("批量工具栏须接列表真实只读状态", content.contains("isReadOnly = uiState.isReadOnly"))
        assertTrue("顶栏溢出菜单入口须接列表真实只读状态", content.contains("isReadOnly = uiState.isReadOnly"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "未找到函数签名：$signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, index + 1)
            }
        }
        error("函数体不完整：$signature")
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码不存在：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        val repositoryRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        const val CONTROLLER = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListActionController.kt"
        const val ROW = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultGroupRow.kt"
        const val CONTENT = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListContent.kt"
        const val TOP_BARS = "app/src/main/java/com/keepasskey/app/ui/screens/vault/VaultListTopBars.kt"
    }
}
