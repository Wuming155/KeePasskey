package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ConflictResolutionViewModel 冲突解决接线单元测试 (Wave 3-E P0-5)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolutionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `观察 conflictFlow 映射冲突字段并在 applyMerge 时调用 resolveConflicts`() = runTest(testDispatcher) {
        val entryId = KdbxUuid.random()
        val local = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Local Title", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("LocalPass", true)
            )
        )
        val remote = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Remote Title", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("RemotePass", true)
            )
        )
        val conflictPair = ConflictedEntryPair(
            entryId = entryId.toHexString(),
            localEntry = local,
            remoteEntry = remote,
            modifiedFields = listOf("标题 (Title)", "密码 (Password)")
        )

        val conflictFlowInternal = MutableStateFlow<List<ConflictedEntryPair>>(listOf(conflictPair))

        var resolvedMap: Map<String, ConflictResolutionChoice>? = null
        var resolvedFieldMap: Map<String, Map<String, ConflictResolutionChoice>>? = null

        // 使用 ContextWrapper + DatabaseSession 创建一个用于测试的 Coordinator 实例
        val tempDir = java.nio.file.Files.createTempDirectory("coord_test").toFile()
        val fakeContext: android.content.Context = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): java.io.File = tempDir
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences =
                Proxy.newProxyInstance(
                    android.content.SharedPreferences::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences::class.java)
                ) { _, method, args ->
                    if (method.name == "getString") args.getOrNull(1) else null
                } as android.content.SharedPreferences
        }

        val session = com.keepasskey.database.session.DatabaseSession()
        val creds = com.keepasskey.app.sync.SyncCredentialsStore(fakeContext, null)

        val mockCoordinator = object : SyncCoordinator(fakeContext, session, creds, com.keepasskey.app.data.logger.DebugLogBuffer()) {
            override val conflictFlow: StateFlow<List<ConflictedEntryPair>>
                get() = conflictFlowInternal.asStateFlow()

            override suspend fun resolveConflicts(
                resolutions: Map<String, ConflictResolutionChoice>,
                fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>>
            ): SyncOutcome {
                resolvedMap = resolutions
                resolvedFieldMap = fieldResolutions
                conflictFlowInternal.value = emptyList()
                return SyncOutcome.MergedAndUploaded
            }
        }

        // P3-23：文案资源化后，单测注入按资源 ID 映射的假 StringsProvider（无 Android 资源环境），
        // 断言语义与资源化前等价（字段标签仍为「标题/密码」中文文案）
        val fakeStrings = com.keepasskey.app.ui.model.StringsProvider { id, _ ->
            when (id) {
                com.keepasskey.app.R.string.conflict_field_title -> "标题 (Title)"
                com.keepasskey.app.R.string.conflict_field_password -> "密码 (Password)"
                else -> ""
            }
        }
        val viewModel = ConflictResolutionViewModel(mockCoordinator, stringsProvider = fakeStrings)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.entries.size)
        val item = state.entries.first()
        assertEquals(entryId.toHexString(), item.id)
        assertTrue(item.fields.any { it.fieldName.contains("标题") })
        assertTrue(item.fields.any { it.fieldName.contains("密码") })

        // 用户选择 REMOTE（标题字段）
        viewModel.selectFieldChoice(item.id, item.fields.first().fieldKey, FieldChoice.REMOTE)
        testScheduler.runCurrent()

        // 提交合并
        var resolveSuccessEmitted = false
        val eventJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect {
                if (it is ConflictResolutionEvent.ResolveSuccess) {
                    resolveSuccessEmitted = true
                }
            }
        }

        viewModel.applyMerge()
        testScheduler.runCurrent()

        assertTrue("应触发 ResolveSuccess 事件", resolveSuccessEmitted)
        assertNotNull(resolvedMap)
        assertEquals(ConflictResolutionChoice.KEEP_REMOTE, resolvedMap!![entryId.toHexString()])
        // TASK-30：字段级决策必须随行下发——标题（用户钦点）为 KEEP_REMOTE，
        // 密码（未触碰）保持 KEEP_LOCAL，不再塌缩为整条目二选一
        val fieldChoices = resolvedFieldMap!![entryId.toHexString()]
        assertNotNull(fieldChoices)
        assertEquals(ConflictResolutionChoice.KEEP_REMOTE, fieldChoices!![KdbxConstants.Fields.TITLE])
        assertEquals(ConflictResolutionChoice.KEEP_LOCAL, fieldChoices[KdbxConstants.Fields.PASSWORD])

        job.cancel()
        eventJob.cancel()
    }
}
