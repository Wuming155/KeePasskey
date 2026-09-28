package com.keepasskey.app.ui.screens.conflict

import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.sync.merge.ConflictedEntryPair
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * ISSUE-P3-360 AC①：冲突屏可读性整改回归。
 *
 * 锁定的缺陷：
 * 1. `localModifiedTime` / `remoteModifiedTime` 已填充但 UI 无消费点，用户看不到哪边更新
 *    —— 本用例锁 `newerSide`（按 Instant 比较，不比格式化文案）的三态；
 * 2. 敏感字段两侧掩码为完全相同的静态串，无法区分差异 —— 本用例锁
 *    「长度 + 字符形态」差异线索存在、两侧可区分、且**不泄露任何明文**。
 * 3. 批量全选入口的 VM 侧语义（Screen 已改为先弹确认，确认后仍走同一 `selectAll`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolutionReadabilityTest {

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
    )

    private fun fakeStrings(): StringsProvider = StringsProvider { id, args ->
        when (id) {
            com.keepasskey.app.R.string.conflict_field_title -> "标题"
            com.keepasskey.app.R.string.conflict_field_password -> "密码"
            com.keepasskey.app.R.string.conflict_group_path -> "根目录"
            com.keepasskey.app.R.string.conflict_time_unknown -> "未知"
            com.keepasskey.app.R.string.time_today -> "今天"
            com.keepasskey.app.R.string.time_yesterday -> "昨天"
            com.keepasskey.app.R.string.date_pattern_month_day -> "MM-dd"
            com.keepasskey.app.R.string.conflict_mask_local -> "••••••••（本地版本）"
            com.keepasskey.app.R.string.conflict_mask_remote -> "••••••••（云端版本）"
            com.keepasskey.app.R.string.conflict_sensitive_hint -> "••••••••（%1\$d 位 · %2\$s）".format(*args)
            com.keepasskey.app.R.string.conflict_sensitive_hint_length_only ->
                "••••••••（%1\$d 位）".format(*args)
            com.keepasskey.app.R.string.conflict_sensitive_cat_upper -> "大写"
            com.keepasskey.app.R.string.conflict_sensitive_cat_lower -> "小写"
            com.keepasskey.app.R.string.conflict_sensitive_cat_digit -> "数字"
            com.keepasskey.app.R.string.conflict_sensitive_cat_symbol -> "符号"
            com.keepasskey.app.R.string.conflict_sensitive_cat_separator -> "、"
            else -> ""
        }
    }

    private fun newHarness(pairs: List<ConflictedEntryPair>): Harness {
        val conflictFlowInternal = MutableStateFlow(pairs)
        val tempDir = java.nio.file.Files.createTempDirectory("conflict_readability_test").toFile()
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
        val mockCoordinator = object : SyncCoordinator(
            fakeContext, session, creds, com.keepasskey.app.data.logger.DebugLogBuffer()
        ) {
            override val conflictFlow: StateFlow<List<ConflictedEntryPair>>
                get() = conflictFlowInternal.asStateFlow()

            override suspend fun resolveConflicts(
                resolutions: Map<String, com.keepasskey.sync.merge.ConflictResolutionChoice>,
                fieldResolutions: Map<String, Map<String, com.keepasskey.sync.merge.ConflictResolutionChoice>>
            ): SyncOutcome {
                conflictFlowInternal.value = emptyList()
                return SyncOutcome.MergedAndUploaded
            }
        }
        val viewModel = ConflictResolutionViewModel(mockCoordinator, stringsProvider = fakeStrings())
        MainDispatcherGuard.track(viewModel)
        return Harness(viewModel, conflictFlowInternal)
    }

    private fun pairWithTimes(localTime: Instant, remoteTime: Instant): ConflictedEntryPair {
        val entryId = KdbxUuid.random()
        val local = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("本地标题", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Local-Secret-123!xyz", true)
            ),
            times = KdbxTimes(lastModificationTime = localTime)
        )
        val remote = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("云端标题", false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("abc456", true)
            ),
            times = KdbxTimes(lastModificationTime = remoteTime)
        )
        return ConflictedEntryPair(
            entryId = entryId.toHexString(),
            localEntry = local,
            remoteEntry = remote,
            modifiedFields = listOf(KdbxConstants.Fields.TITLE, KdbxConstants.Fields.PASSWORD)
        )
    }

    @Test
    fun `本地较新时 newerSide 标注 LOCAL`() = runTest(testDispatcher) {
        val harness = newHarness(
            listOf(
                pairWithTimes(
                    localTime = Instant.parse("2020-01-02T10:00:00Z"),
                    remoteTime = Instant.parse("2020-01-01T10:00:00Z")
                )
            )
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val state = harness.viewModel.uiState.value
        assertEquals(ModifiedTimeSide.LOCAL, state.newerSide)
        assertTrue("本地时间串不应为空", state.localModifiedTime.isNotBlank())
        assertTrue("云端时间串不应为空", state.remoteModifiedTime.isNotBlank())
        job.cancel()
    }

    @Test
    fun `云端较新时 newerSide 标注 REMOTE，时间相同则不标注`() = runTest(testDispatcher) {
        val remoteNewer = newHarness(
            listOf(
                pairWithTimes(
                    localTime = Instant.parse("2020-01-01T10:00:00Z"),
                    remoteTime = Instant.parse("2020-01-02T10:00:00Z")
                )
            )
        )
        val jobA = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            remoteNewer.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        assertEquals(ModifiedTimeSide.REMOTE, remoteNewer.viewModel.uiState.value.newerSide)
        jobA.cancel()

        val sameTime = Instant.parse("2020-01-01T10:00:00Z")
        val equal = newHarness(listOf(pairWithTimes(localTime = sameTime, remoteTime = sameTime)))
        val jobB = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            equal.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()
        assertNull("时间相同不得标注任一侧较新", equal.viewModel.uiState.value.newerSide)
        jobB.cancel()
    }

    @Test
    fun `敏感字段给出长度与形态差异线索且两侧可区分、不泄露明文`() = runTest(testDispatcher) {
        val harness = newHarness(
            listOf(
                pairWithTimes(
                    localTime = Instant.parse("2020-01-02T10:00:00Z"),
                    remoteTime = Instant.parse("2020-01-01T10:00:00Z")
                )
            )
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val passwordField = harness.viewModel.uiState.value.entries.single()
            .fields.first { it.fieldKey == KdbxConstants.Fields.PASSWORD }
        assertTrue("密码字段必须保持敏感标记", passwordField.isSensitive)
        assertTrue(
            "本地线索应含长度（20 位）：${passwordField.localValue}",
            passwordField.localValue.contains("20")
        )
        assertTrue(
            "云端线索应含长度（6 位）：${passwordField.remoteValue}",
            passwordField.remoteValue.contains("6")
        )
        assertTrue(
            "两侧线索必须可区分（整改前为完全相同的静态掩码）",
            passwordField.localValue != passwordField.remoteValue
        )
        assertFalse("线索不得含本地明文", passwordField.localValue.contains("Secret"))
        assertFalse("线索不得含云端明文", passwordField.remoteValue.contains("abc456"))
        assertFalse("线索不得含本地口令的任何片段", passwordField.localValue.contains("Local"))
        job.cancel()
    }

    @Test
    fun `selectAll 批量切换仍即时改写全部字段（确认后执行的同一入口）`() = runTest(testDispatcher) {
        val harness = newHarness(
            listOf(
                pairWithTimes(
                    localTime = Instant.parse("2020-01-02T10:00:00Z"),
                    remoteTime = Instant.parse("2020-01-01T10:00:00Z")
                )
            )
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.viewModel.uiState.collect {}
        }
        testScheduler.runCurrent()

        val state = harness.viewModel.uiState.value.entries.single()
        assertNotNull(state.fields.firstOrNull())
        assertTrue(
            "字段默认 LOCAL 语义必须保留（未触碰即本地）",
            state.fields.all { it.selectedChoice == FieldChoice.LOCAL }
        )

        harness.viewModel.selectAll(FieldChoice.REMOTE)
        testScheduler.runCurrent()
        assertTrue(
            "确认后全选云端必须改写全部字段",
            harness.viewModel.uiState.value.entries.single()
                .fields.all { it.selectedChoice == FieldChoice.REMOTE }
        )
        job.cancel()
    }
}
