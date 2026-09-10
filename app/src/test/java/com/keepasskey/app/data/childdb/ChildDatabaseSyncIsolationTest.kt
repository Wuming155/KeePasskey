package com.keepasskey.app.data.childdb

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步交互不变量单测（设计要点 4）。
 *
 * 不变量：**根库同步绝不隐式上传子库文件**。`SyncCoordinator.syncNow` 只处理
 * `DatabaseSession.currentFile`（当前根库），而子库来源只落在**独立**偏好文件
 * [ChildDatabaseMountStore.PREFS_NAME] 中——既不进入库列表偏好
 * （`keepasskey_vault_meta.known_databases_v1`，库选择器与同步候选的来源），
 * 也不改变 `currentFile` / `currentPathIdentifier`。
 *
 * 因此子库路径在结构上不可能出现在任何同步候选集合里；本用例以「假 Context 记录
 * 生产代码实际访问过的偏好文件名」的方式守护该结构约束，防止将来有人把子库来源
 * 顺手写进库列表。
 */
class ChildDatabaseSyncIsolationTest {

    private val factory = RecordingContextFactory()

    private val credentials = ChildDatabaseCredentialStore()

    private val databaseSession = DatabaseSession()

    private val source = FakeChildDatabaseStreamSource()

    private val password = "child-master-pw".toCharArray()

    private fun newManager(): ChildDatabaseSessionManager = ChildDatabaseSessionManager(
        mountStore = ChildDatabaseMountStore(factory.context),
        credentials = credentials,
        streamSource = source,
        databaseSession = databaseSession,
        debugLog = DebugLogBuffer()
    )

    @Test
    fun `挂载只写子库注册表且不改动根库同步来源`() = runTest {
        source.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val manager = newManager()

        val record = manager.mount("云端子库", validContentSource(), password, null).getOrThrow()

        assertEquals(
            "生产代码只允许访问子库注册表偏好文件",
            listOf(ChildDatabaseMountStore.PREFS_NAME),
            factory.accessedNames
        )
        assertNull(
            "库列表偏好（同步候选来源）必须从未被触碰",
            factory.prefsOf(VAULT_META_PREFS)
        )
        assertNull("根库同步只处理 currentFile：子库不得成为同步来源", databaseSession.currentFile)
        assertNull(databaseSession.currentPathIdentifier)
        assertTrue(record.sourceUri.startsWith("content://"))
        assertEquals(record.sourceUri, manager.mounts.value.single().sourceUri)
    }

    @Test
    fun `卸载同样只触碰子库注册表偏好文件`() = runTest {
        source.payload = ChildDatabaseFixtures.kdbxBytes(password)
        val manager = newManager()
        val record = manager.mount("云端子库", validContentSource(), password, null).getOrThrow()

        manager.unmount(record.id)

        assertEquals(listOf(ChildDatabaseMountStore.PREFS_NAME), factory.accessedNames)
        assertNull(factory.prefsOf(VAULT_META_PREFS))
        assertNull(databaseSession.currentFile)
        assertEquals(0, manager.mountedCount.value)
    }

    private companion object {
        /** 与 `RealVaultRepository` 的库列表偏好文件名一致（同步侧读取的库候选来源） */
        const val VAULT_META_PREFS = "keepasskey_vault_meta"
    }
}
