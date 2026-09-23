package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.ConflictResolutionChoice
import com.keepasskey.sync.merge.ConflictedEntryPair
import com.keepasskey.sync.merge.KdbxMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ISSUE-P2-281 AC②／AC③／AC④：自定义字段分歧可见可裁决 + 条目级整条兜底入口回归。
 *
 * ## 锁定的缺陷
 *
 * 整改前冲突界面自造五字段 diff（**不含自定义字段**）⇒ 仅自定义字段分歧时界面无可裁决行，
 * `fieldResolutions` 恒为空表 ⇒ 合并器直接返回本地条目，**远端改动必然丢失**；
 * 且无「整条取云端」入口，`DUPLICATE_BOTH` 分支不可达。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolutionCustomFieldTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private class Harness(
        val viewModel: ConflictResolutionViewModel,
        val conflictFlow: MutableStateFlow<List<ConflictedEntryPair>>
    ) {
        var resolvedMap: Map<String, ConflictResolutionChoice>? = null
        var resolvedFieldMap: Map<String, Map<String, ConflictResolutionChoice>>? = null
    }

    private fun newHarness(pairs: List<ConflictedEntryPair>): Harness {
        val conflictFlowInternal = MutableStateFlow(pairs)
        val tempDir = java.nio.file.Files.createTempDirectory("conflict_custom_test").toFile()
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

        lateinit var harness: Harness
        val mockCoordinator = object : SyncCoordinator(
            fakeContext, session, creds, com.keepasskey.app.data.logger.DebugLogBuffer()
        ) {
            override val conflictFlow: StateFlow<List<ConflictedEntryPair>>
                get() = conflictFlowInternal.asStateFlow()

            override suspend fun resolveConflicts(
                resolutions: Map<String, ConflictResolutionChoice>,
                fieldResolutions: Map<String, Map<String, ConflictResolutionChoice>>
            ): SyncOutcome {
                harness.resolvedMap = resolutions
                harness.resolvedFieldMap = fieldResolutions
                conflictFlowInternal.value = emptyList()
                return SyncOutcome.MergedAndUploaded
            }
        }
        val fakeStrings = StringsProvider { _, _ -> "" }
        harness = Harness(ConflictResolutionViewModel(mockCoordinator, stringsProvider = fakeStrings), conflictFlowInternal)
        MainDispatcherGuard.track(harness.viewModel)
        return harness
    }

    private fun customOnlyPair(): ConflictedEntryPair {
        val entryId = ENTRY_ID
        val local = KdbxEntry(
            id = entryId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("同一标题", false)),
            customFields = listOf(KdbxCustomField("OTP", ProtectedString("local-otp", true)))
        )
        val remote = KdbxEntry(
            id = entryId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("同一标题", false)),
            customFields = listOf(KdbxCustomField("OTP", ProtectedString("remote-otp", true)))
        )
        return ConflictedEntryPair(
            entryId = entryId.toHexString(),
            localEntry = local,
            remoteEntry = remote,
            modifiedFields = listOf(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP")
        )
    }

    @Test
    fun `仅自定义字段冲突：界面出现可裁决行且选择真实下发（AC④ 用户能选）`() = runTest(testDispatcher) {
        val harness = newHarness(listOf(customOnlyPair()))
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val item = harness.viewModel.uiState.value.entries.single()
        val customRow = item.fields.singleOrNull {
            it.fieldKey == KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP"
        }
        assertTrue("仅自定义字段分歧必须在界面出现可裁决行: ${item.fields}", customRow != null)
        assertEquals("行标签应为自定义字段名", "OTP", customRow!!.fieldName)
        assertTrue("受保护的自定义字段必须掩码呈现", customRow.isSensitive)

        harness.viewModel.selectFieldChoice(item.id, customRow.fieldKey, FieldChoice.REMOTE)
        testScheduler.runCurrent()
        harness.viewModel.applyMerge()
        testScheduler.runCurrent()

        val fieldChoices = harness.resolvedFieldMap?.get(item.id)
        assertEquals(
            "用户对自定义字段的云端选择必须真实下发（不再恒为空表）",
            ConflictResolutionChoice.KEEP_REMOTE,
            fieldChoices?.get(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP")
        )
        job.cancel()
    }

    @Test
    fun `整条取云端：走 resolutions 通道且不下发字段级决策（AC② 兜底入口）`() = runTest(testDispatcher) {
        val harness = newHarness(listOf(customOnlyPair()))
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val itemId = harness.viewModel.uiState.value.entries.single().id
        harness.viewModel.selectEntryMode(itemId, EntryResolutionMode.KEEP_REMOTE)
        testScheduler.runCurrent()
        harness.viewModel.applyMerge()
        testScheduler.runCurrent()

        assertEquals(ConflictResolutionChoice.KEEP_REMOTE, harness.resolvedMap?.get(itemId))
        assertFalse(
            "整条裁决时字段级通道不得含该条目（防被字段级空表吞掉）",
            harness.resolvedFieldMap?.containsKey(itemId) == true
        )
        job.cancel()
    }

    @Test
    fun `双方保留：DUPLICATE_BOTH 决策真实可达（AC③）`() = runTest(testDispatcher) {
        val harness = newHarness(listOf(customOnlyPair()))
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val itemId = harness.viewModel.uiState.value.entries.single().id
        harness.viewModel.selectEntryMode(itemId, EntryResolutionMode.DUPLICATE_BOTH)
        testScheduler.runCurrent()
        harness.viewModel.applyMerge()
        testScheduler.runCurrent()

        assertEquals(
            "DUPLICATE_BOTH 必须真实下发（此前该分支不可达）",
            ConflictResolutionChoice.DUPLICATE_BOTH,
            harness.resolvedMap?.get(itemId)
        )
        job.cancel()
    }

    private companion object {
        val ENTRY_ID: KdbxUuid = KdbxUuid.random()
    }
}
