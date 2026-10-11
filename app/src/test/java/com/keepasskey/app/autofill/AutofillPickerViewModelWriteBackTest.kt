package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.app.testutil.MainDispatcherGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ISSUE-P3-571` 方案 A 询问流的编排级锁定（`AutofillPickerViewModel.offerAppBindingWriteBack`）：
 * 判定不满足 → 不问照常交付；满足 → 置起询问、同意写回 / 拒绝不写；拒绝不持久化。
 *
 * 锁定丢弃分支（询问期间 `isLocked()` 翻转 → 返回 false）依赖 `VaultRepository.isLocked`，
 * `FakeVaultRepository` 恒 false 且为 final 不可覆写——该分支与交付链既有「回传前再次校验」
 * 同型（同仓同一谓词），本类不覆盖，批次文档如实声明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutofillPickerViewModelWriteBackTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    private fun newVm(fake: FakeVaultRepository): AutofillPickerViewModel =
        MainDispatcherGuard.track(AutofillPickerViewModel(fake))

    /**
     * Fake 的 `databaseSession` 为 null（纯 JVM），锁定观察者不生效；条目即静态 mock 投影。
     *
     * 等待走 `withContext(Dispatchers.IO)`（与 `AutofillPickerViewModelSessionLockTest` 同法）——
     * **不得**在 runBlocking 主线程 `Thread.sleep`：那会阻塞 BlockingEventLoop，
     * 使 `viewModelScope`（Main＝UnconfinedTestDispatcher）侧协程得不到推进。
     */
    private fun loadEntriesBlocking(vm: AutofillPickerViewModel) = runBlocking {
        vm.loadEntries()
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (vm.entries.value.isEmpty() && waited < 5_000) {
                Thread.sleep(10)
                waited += 10
            }
        }
    }

    /**
     * 询问流等待（同上：sleep 必须在 IO 线程，防阻塞 runBlocking event loop）。
     * [waitForAsk] 返回时询问应已置起（launch 侧用 [CoroutineStart.UNDISPATCHED]
     * 同步执行到 `decision.await()` 挂起点，ask 必然先于本函数置起）。
     */
    private suspend fun waitForAsk(vm: AutofillPickerViewModel) {
        withContext(Dispatchers.IO) {
            var waited = 0L
            while (vm.bindingWriteBackAsk.value == null && waited < 5_000) {
                Thread.sleep(10)
                waited += 10
            }
        }
    }

    @Test
    fun `满足条件时置起询问_同意后写回android绑定并清理询问`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }
        val entryId = entry.id.toHexString()

        val outcome = CompletableDeferred<Boolean>()
        // CoroutineStart.UNDISPATCHED：同步执行到 decision.await() 挂起点——否则 launch 在
        // runBlocking event loop 里排队，主线程 Thread.sleep 阻塞 loop 会令 ask 永不置起（挂死）
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle("aa:bb"),
                    "com.tencent.mobileqq",
                    formWebDomain = null,
                    entryId = entryId,
                    entryTitle = entry.title
                )
            )
        }
        waitForAsk(vm)
        val ask = vm.bindingWriteBackAsk.value
        assertEquals(entryId, ask?.entryId)
        assertEquals("android://com.tencent.mobileqq", ask?.proposedUrl)

        vm.completeBindingWriteBack(true)
        job.join()

        assertTrue(outcome.await())
        // 断言走 Fake 观测点而非 getKdbxEntry 往返：mock 条目 id 非合法 32-hex 时
        // KdbxUuid 转换回退随机值，getKdbxEntry 永远查不到（生产链路无此坑）
        assertEquals(
            "android://com.tencent.mobileqq",
            fake.lastWrittenUrlByEntry[entryId]
        )
        assertNull("写回完成后询问必须清空", vm.bindingWriteBackAsk.value)
    }

    @Test
    fun `拒绝时不写回_照常交付_询问清空且不持久化拒绝`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }
        val entryId = entry.id.toHexString()

        val outcome = CompletableDeferred<Boolean>()
        // 同上：UNDISPATCHED 同步执行到挂起点，防 runBlocking event loop 阻塞型挂死
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle("aa:bb"),
                    "com.tencent.mobileqq",
                    formWebDomain = null,
                    entryId = entryId,
                    entryTitle = entry.title
                )
            )
        }
        waitForAsk(vm)

        vm.completeBindingWriteBack(false)
        job.join()

        assertTrue("拒绝＝不写回但照常交付", outcome.await())
        assertTrue("拒绝不得触发写回", fake.lastWrittenUrlByEntry.isEmpty())
        assertNull(vm.bindingWriteBackAsk.value)
    }

    /**
     * 「条目 URL 非空不询问」一态**不设 VM 层用例**：mock 条目 id 非合法 32-hex 时
     * `getKdbxEntry` 往返恒空（Fake 转换回退随机 id），「条目当前 URL 非空」在本 VM 层
     * **不可构造**——该否决态由 [AutofillAppBindingWriteBackPolicyTest] 的策略用例锁定。
     */
    @Test
    fun `浏览器表单不询问_域维度不在本条`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }

        val proceed = vm.offerAppBindingWriteBack(
            CallerCertDigests.ofSingle("aa:bb"),
            "com.tencent.mobileqq",
            formWebDomain = "example.com",
            entryId = entry.id.toHexString(),
            entryTitle = entry.title
        )

        assertTrue(proceed)
        assertTrue("浏览器表单不得触发写回", fake.lastWrittenUrlByEntry.isEmpty())
        assertNull(vm.bindingWriteBackAsk.value)
    }

    @Test
    fun `只读会话不询问`() = runBlocking {
        val fake = FakeVaultRepository()
        fake.sessionReadOnly = true
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }

        val proceed = vm.offerAppBindingWriteBack(
            CallerCertDigests.ofSingle("aa:bb"),
            "com.tencent.mobileqq",
            formWebDomain = null,
            entryId = entry.id.toHexString(),
            entryTitle = entry.title
        )

        assertTrue("只读会话＝不问但照常交付（写回一票否决，不影响填充）", proceed)
        assertTrue("只读会话不得触发写回", fake.lastWrittenUrlByEntry.isEmpty())
        assertNull(vm.bindingWriteBackAsk.value)
    }
}
