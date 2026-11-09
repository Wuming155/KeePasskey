package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
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
 * ISSUE-P2-52（审计 F-22）回归：选择器缓存的「活」`KdbxEntry` 树与会话生命周期的对齐。
 *
 * - 锁定时 `DatabaseSession` 先对库树 `ProtectedString` 就地清零、后通知观察者——
 *   VM 必须注册 [com.keepasskey.core.session.SessionLockObserver] 在锁定时清空缓存，
 *   否则锁定后任何 `title` / `userName` 读取都会抛 `IllegalStateException`。
 * - 清零与通知之间存在固有竞态窗口：`search` / `resolveCredentials` 的非敏感字段读取
 *   必须 fail-safe（空结果 / 空用户名），不得让锁定竞态演变为选择器崩溃。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutofillPickerViewModelSessionLockTest {

    /**
     * `ISSUE-P2-307`：本类用例依赖 `viewModelScope.launch` 的**实时执行**（下方实时等待模式）——
     * 必须显式装 eager Main（[UnconfinedTestDispatcher]，与新 JVM 的 ServiceLoader 初始态等价），
     * 不得继承上一个测试类「装而不卸」遗留的 `StandardTestDispatcher`（无人推进 ⇒ 实时等待超时
     * ⇒「测试前提：选择器应已缓存条目」类假红的根因）。收尾走守卫「装新不卸」。
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
    fun `会话锁定后选择器缓存条目被清空`() {
        val session = DatabaseSession()
        val vm = MainDispatcherGuard.track(AutofillPickerViewModel(FakeVaultRepository(), session))
        // ISSUE-P3-148：整库装载不再于 init 自动发生，改由选择器页显式发起（本用例模拟该页）
        vm.loadEntries()

        // 装载为异步（Fake 即时返回）→ 先等待列表就绪
        runBlocking {
            withContext(Dispatchers.IO) {
                var waited = 0L
                while (vm.entries.value.isEmpty() && waited < 5_000) {
                    Thread.sleep(10)
                    waited += 10
                }
            }
        }
        assertTrue("测试前提：选择器应已缓存条目", vm.entries.value.isNotEmpty())

        runBlocking { session.lock() }

        assertTrue("锁定后选择器缓存必须清空（活树已清零，继续持有即读即炸）", vm.entries.value.isEmpty())
    }

    @Test
    fun `锁定竞态下读取已清零条目_search与用户名按空降级而非崩溃`() = runBlocking {
        // 直接构造「已被锁定清零」的条目，模拟清零→通知窗口内的缓存内容
        val clearedEntry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Title").apply { clear() },
                KdbxConstants.Fields.USER_NAME to ProtectedString("user@example.com").apply { clear() },
                KdbxConstants.Fields.URL to ProtectedString("https://example.com").apply { clear() }
            )
        )
        val repo = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getKdbxEntries(): List<KdbxEntry> = listOf(clearedEntry)

            // ISSUE-P2-341：选择器改走「可用条目」读口；委托型桩不转发就永远拿不到这份数据
            override suspend fun getUsableKdbxEntries(): List<KdbxEntry> = getKdbxEntries()
        }
        val vm = MainDispatcherGuard.track(AutofillPickerViewModel(repo, null))
        // ISSUE-P3-148：显式触发整库装载（选择器页路径）
        vm.loadEntries()

        // 等待异步装载完成
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (vm.entries.value.isEmpty() && waited < 5_000) {
                Thread.sleep(10)
                waited += 10
            }
        }
        assertEquals(1, vm.entries.value.size)

        // search 命中已清零条目：title/userName 读取本会抛 IllegalStateException → 按空结果降级
        assertEquals(emptyList<KdbxEntry>(), vm.search("Title"))

        // resolveCredentials 读取已清零条目的 userName：按空用户名降级而非崩溃
        val entryId = vm.entries.value.first().id.toHexString()
        val creds = vm.resolveCredentials(entryId)
        assertEquals("", creds?.username)
        assertEquals("", creds?.password)
    }

    /**
     * `ENG-20261028-11`：缓存租约只覆盖**一代树快照**——会话树换代后缓存按新快照重载，
     * 且锁定后不得被重载逻辑重新填满。
     *
     * 判别点：若没有「换树即重载」的重载器，或重载器不判锁定/空库，缓存会停在旧一代
     * （换树后看不到新条目）或在锁定后回流活条目（锁后仍持有明文引用）。
     */
    @Test
    fun `库树换代后缓存重载为新树内容且锁定后不回流`() {
        val session = DatabaseSession()
        session.setDatabaseForTesting(databaseWithTitle("旧标题"))
        val repo = object : VaultRepository by FakeVaultRepository() {
            override suspend fun getUsableKdbxEntries(): List<KdbxEntry> =
                session.databaseFlow.value?.rootGroup?.allEntries().orEmpty()
        }
        val vm = MainDispatcherGuard.track(AutofillPickerViewModel(repo, session))
        vm.loadEntries()
        awaitEntries(vm) { it.singleOrNull()?.displayTitle() == "旧标题" }

        // 会话树换代（模拟同步合并 / 批量导入后的整树替换）
        session.setDatabaseForTesting(databaseWithTitle("新标题"))
        awaitEntries(vm) { it.singleOrNull()?.displayTitle() == "新标题" }

        // 锁定：缓存必须保持为空，重载器不得把活条目填回来
        runBlocking { session.lock() }
        Thread.sleep(50)
        assertTrue("锁定后缓存必须保持为空（不得被重载器回流）", vm.entries.value.isEmpty())
    }

    private fun databaseWithTitle(title: String) = com.keepasskey.database.file.KdbxDatabase(
        header = com.keepasskey.database.file.KdbxHeader.createDefault(),
        rootGroup = com.keepasskey.core.model.KdbxGroup(
            name = "Root",
            entries = listOf(
                KdbxEntry(fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false)))
            )
        )
    )

    private fun awaitEntries(vm: AutofillPickerViewModel, predicate: (List<KdbxEntry>) -> Boolean) {
        runBlocking {
            withContext(Dispatchers.IO) {
                var waited = 0L
                while (!predicate(vm.entries.value) && waited < 5_000) {
                    Thread.sleep(10)
                    waited += 10
                }
            }
        }
        assertTrue("等待选择器缓存进入预期状态超时", predicate(vm.entries.value))
    }
}
