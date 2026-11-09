package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ISSUE-P3-644 / ENG-20261028-12：分组图标保存结果与只读路径反馈的控制器行为用例。
 *
 * 分工与替代口径：
 * 1. 控制器反馈行为（JVM 单测）：本用例经 [VaultListActionController] 真实执行换图标动作，
 *    按成功、失败与只读三态断言仓库调用次数与上报的 [UiMessage] 资源 ID，替代原先锁死控制器
 *    内部 `is KdbxResult.Success` 语法结构的源码文本断言。
 * 2. 真实 UI 呈现与写盘不变性（设备验收）：只读会话下的分组行菜单置灰/原因展示、顶栏溢出菜单
 *    隐藏批量入口、批量工具栏按钮置灰/原因展示，以及真实 IO 失败、外部漂移中止与只读态下的磁盘
 *    文件保全，已由 androidTest 同名设备用例 [com.keepasskey.app.ui.VaultGroupWriteFeedbackDeviceTest]
 *    在真实 Compose UI 树与无障碍节点上端到端验证，原 `readSource` 源码锁定断言退役。
 */
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
}
