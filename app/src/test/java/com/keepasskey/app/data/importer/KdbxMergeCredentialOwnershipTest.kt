package com.keepasskey.app.data.importer

import android.content.Context
import android.content.ContextWrapper
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.sync.SyncSessionState
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.importer.VaultImportController
import com.keepasskey.app.ui.screens.settings.SettingsImportPresenter
import com.keepasskey.database.session.DatabaseSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-580：`.kdbx` 并入凭据链的**原件清零所有权契约**（宿主纯 JVM）。
 *
 * 契约（本批立，登记于 `docs/architecture/敏感缓冲所有权契约.md` §4 #20 / §8.1）：
 * 1. **原件清零收口点唯一** = `SettingsImportPresenter.submitMergeCredentials`——提交返回前
 *    （正常 / 异常 / 控制器缺失三条路径一致）对第二库主密码 `CharArray` 与密钥文件 `ByteArray`
 *    两**原件**清零；清除的是原件，后台消费用的是 `KdbxMergeController` 的 `copyOf` 副本，
 *    两者无别名面（副本的 `copyOf` 与消费协程的 `launch` 均发生在 `submitCredentials` 同步返回前）；
 * 2. `VaultImportController.submitMergeCredentials` 与 `KdbxMergeController.submitCredentials`
 *    为**只读借用**（B 类）——任何路径都不得代擦原件（R2）——负向断言锁定该分层，
 *    防回退成「下游顺手清零」而使三层 KDoc 再度失实。
 *
 * 覆盖边界（如实声明）：`android.net.Uri` 无 JVM 实现（本仓惯例见 `VaultImportProgressCancelTest`），
 * `pendingUri` 只能经 `beginWithUri` 注入，故链路驱动的是「控制器在场 + 无待并入 Uri」形态；
 * 含 `copyOf` 正常消费分支的形态无法在宿主构造，其顺序安全由上述结构保证而非本用例实测。
 */
class KdbxMergeCredentialOwnershipTest {

    private val fakeContext: Context = object : ContextWrapper(null) {}

    private fun newMergeController(): KdbxMergeController = KdbxMergeController(
        context = fakeContext,
        databaseSession = DatabaseSession(),
        syncSessionState = SyncSessionState(),
        debugLog = DebugLogBuffer()
    )

    private fun newVaultImportController(merge: KdbxMergeController): VaultImportController =
        VaultImportController(
            context = fakeContext,
            registry = ImporterRegistry(emptySet(), DebugLogBuffer()),
            vaultImporter = VaultImporter(
                FakeVaultRepository(),
                StringsProvider { _, _ -> "" },
                DebugLogBuffer()
            ),
            debugLog = DebugLogBuffer(),
            kdbxMergeController = merge
        )

    @Test
    fun `控制器在场时提交返回后收口点清零主密码与密钥文件原件`() {
        val presenter = SettingsImportPresenter(
            controller = newVaultImportController(newMergeController()),
            appContext = null
        )
        val password = "第二库假主密码".toCharArray()
        val keyBytes = byteArrayOf(1, 2, 3, 4)

        presenter.submitMergeCredentials(password, keyBytes)

        assertTrue(
            "提交返回后主密码原件须被收口点清零",
            password.all { it == ERASED_CHAR }
        )
        assertTrue(
            "提交返回后密钥文件字节原件须被收口点清零",
            keyBytes.all { it == ERASED_BYTE }
        )
    }

    @Test
    fun `控制器缺失时提交返回后收口点仍清零两原件`() {
        val presenter = SettingsImportPresenter(controller = null, appContext = null)
        val password = "无控制器假密码".toCharArray()
        val keyBytes = byteArrayOf(5, 6)

        presenter.submitMergeCredentials(password, keyBytes)

        assertTrue(
            "控制器缺失路径同样须清零（收口点不依赖下游是否存在）",
            password.all { it == ERASED_CHAR }
        )
        assertTrue(
            "控制器缺失路径同样须清零（收口点不依赖下游是否存在）",
            keyBytes.all { it == ERASED_BYTE }
        )
    }

    @Test
    fun `Uri 入口仅密码提交时主密码原件仍清零`() {
        val presenter = SettingsImportPresenter(controller = null, appContext = null)
        val password = "仅密码假凭据".toCharArray()

        presenter.submitMergeCredentials(password, keyFileUri = null)

        assertTrue(
            "Uri 入口必须经同一收口点清零（旁路直调下游即失败）",
            password.all { it == ERASED_CHAR }
        )
    }

    @Test
    fun `门面为纯透传_不代擦借用原件`() {
        val facade = newVaultImportController(newMergeController())
        val password = "门面假密码".toCharArray()
        val keyBytes = byteArrayOf(7, 7)

        facade.submitMergeCredentials(password, keyBytes)

        assertArrayEquals(
            "VaultImportController 为只读借用：不得代擦主密码原件（清零归收口点）",
            "门面假密码".toCharArray(),
            password
        )
        assertArrayEquals(
            "VaultImportController 为只读借用：不得代擦密钥文件字节原件（清零归收口点）",
            byteArrayOf(7, 7),
            keyBytes
        )
    }

    @Test
    fun `合并控制器为借用方_早退分支不代擦原件`() {
        val merge = newMergeController()
        val password = "控制器假密码".toCharArray()
        val keyBytes = byteArrayOf(9)

        // pendingUri == null ⇒ 走早退分支（整改前该分支会顺手清零调用方原件）
        merge.submitCredentials(password, keyBytes)

        assertArrayEquals(
            "KdbxMergeController 为只读借用：早退分支亦不得代擦主密码原件",
            "控制器假密码".toCharArray(),
            password
        )
        assertArrayEquals(
            "KdbxMergeController 为只读借用：早退分支亦不得代擦密钥文件字节原件",
            byteArrayOf(9),
            keyBytes
        )
    }

    private companion object {
        /** 收口点擦除字符（与仓内既有 CharArray `fill('0')` 口径一致） */
        const val ERASED_CHAR = '0'

        /** 收口点擦除字节 */
        const val ERASED_BYTE: Byte = 0
    }
}
