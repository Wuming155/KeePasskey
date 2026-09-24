package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动填充凭据取值通道回归：ISSUE-P2-88（取值不得只依赖 VM 缓存）+ ISSUE-P3-148（确认路径不装载整库）。
 *
 * ## ISSUE-P2-88 缺陷形态（真机定位）
 *
 * 本 VM 的条目缓存由选择器页异步填充，而确认页（[AutofillConfirmActivity]）在用户点
 * 「确认填充」时才**首次创建**该 VM——那一刻缓存尚未就绪，若用户名只从缓存取，就会拿到
 * 空用户名：回传数据集里只剩口令字段。真机留痕即此形态（口令 12 字符写入成功、账号框始终为空）。
 *
 * ## ISSUE-P3-148 判据
 *
 * 确认路径由 `EXTRA_ENTRY_ID` 指向**单条**，故其取数只许走
 * [VaultRepository.getKdbxEntry]（按 id 单条查询），**不得**触发
 * [VaultRepository.getKdbxEntries]（整库非敏感投影）——后者是选择器页搜索的固有开销，
 * 每次「确认填充」付一次即与库规模成正比。
 *
 * 口令仍只经既有按需解密通道（[VaultRepository.getEntryPasswordChars]）取得。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutofillPickerViewModelCredentialLookupTest {

    /**
     * `ISSUE-P2-307`：本类三个用例均构造 ViewModel 并依赖 `viewModelScope.launch` 的**实时执行**
     * （下方 `awaitEntries` 的实时等待模式）——必须显式装 eager Main（[UnconfinedTestDispatcher]，
     * 与新 JVM 的 ServiceLoader 初始态等价），不得继承上一个测试类「装而不卸」遗留的
     * `StandardTestDispatcher`（无人推进 ⇒ 实时等待超时 ⇒ 本文件三连假红的根因）。
     * 收尾走守卫「装新不卸」（不调 `resetMain`，见 `MainDispatcherGuard` 类 KDoc）。
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `确认路径不装载整库投影_且仍取到用户名与口令`() = runBlocking {
        val entryUuid = KdbxUuid.random()
        var wholeVaultLoads = 0
        var singleLookups = 0
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> {
                wholeVaultLoads++
                return listOf(entry(entryUuid))
            }

            override suspend fun getKdbxEntry(entryId: String): KdbxEntry? {
                singleLookups++
                return entry(entryUuid)
            }

            override suspend fun getEntryPasswordChars(entryId: String): CharArray? =
                TEST_PASSWORD.toCharArray()
        }

        // 确认页形态：VM 按需创建，**从不**调用 loadEntries（该页不做整库装载）
        val viewModel = MainDispatcherGuard.track(AutofillPickerViewModel(repository))
        val credentials = viewModel.resolveCredentials(entryUuid.toHexString())

        assertEquals("用户名须经单条查询取回（不得只依赖缓存）", TEST_USERNAME, credentials?.username)
        assertEquals("口令仍走既有按需解密通道", TEST_PASSWORD, credentials?.password)
        assertEquals("确认路径不得装载整库非敏感投影", 0, wholeVaultLoads)
        assertEquals("用户名须经按 id 的单条查询", 1, singleLookups)
        assertTrue("确认路径不得留下整库缓存", viewModel.entries.value.isEmpty())
    }

    @Test
    fun `选择器页缓存命中时不回查仓库单条`() = runBlocking {
        val entryUuid = KdbxUuid.random()
        var singleLookups = 0
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> = listOf(entry(entryUuid))

            override suspend fun getKdbxEntry(entryId: String): KdbxEntry? {
                singleLookups++
                return entry(entryUuid)
            }

            override suspend fun getEntryPasswordChars(entryId: String): CharArray? =
                TEST_PASSWORD.toCharArray()
        }

        // 选择器页形态：显式发起整库装载（搜索用），之后选中条目
        val viewModel = MainDispatcherGuard.track(AutofillPickerViewModel(repository))
        viewModel.loadEntries()
        awaitEntries(viewModel)

        val credentials = viewModel.resolveCredentials(entryUuid.toHexString())

        assertEquals(TEST_USERNAME, credentials?.username)
        assertEquals("缓存命中即免一次仓库单条查询", 0, singleLookups)
    }

    @Test
    fun `条目取不回时用户名按空降级而非崩溃`() = runBlocking {
        // 锁定态 / 条目已不存在：仓库单条查询返回 null（P2-52 fail-safe 语义不回归为抛错或放行）
        val repository = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> =
                error("确认路径不得装载整库")

            override suspend fun getKdbxEntry(entryId: String): KdbxEntry? = null

            override suspend fun getEntryPasswordChars(entryId: String): CharArray? = null
        }

        val viewModel = MainDispatcherGuard.track(AutofillPickerViewModel(repository))
        val credentials = viewModel.resolveCredentials(KdbxUuid.random().toHexString())

        assertEquals("条目不可读 ⇒ 空用户名降级", "", credentials?.username)
        assertEquals("无口令 ⇒ 空口令降级", "", credentials?.password)
    }

    private fun entry(id: KdbxUuid) = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.USER_NAME to ProtectedString(TEST_USERNAME, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(TEST_PASSWORD, isProtected = true)
        )
    )

    private suspend fun awaitEntries(viewModel: AutofillPickerViewModel) {
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (viewModel.entries.value.isEmpty() && waited < 5_000) {
                Thread.sleep(10)
                waited += 10
            }
        }
    }

    private companion object {
        // 虚构测试值（敏感纪律：不得使用真实凭据）
        const val TEST_USERNAME = "issue-p2-88-user"
        const val TEST_PASSWORD = "IssueP288#2026"
    }
}
