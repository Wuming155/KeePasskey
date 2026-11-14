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
 * 应用关联写回询问流的编排级锁定（[AutofillPickerViewModel.offerAppBindingWriteBack]）：
 * 判定不满足 → 不问照常交付；满足 → 置起询问、同意写 `AndroidApp*` 自定义字段 / 拒绝不写；
 * 写入成功后同一会话内「该包名已绑定」不再重复询问。
 *
 * 锁定丢弃分支（询问期间 `isLocked()` 翻转 → 返回 false）依赖 `VaultRepository.isLocked`，
 * `FakeVaultRepository` 恒 false 且为 final 不可覆写——该分支与交付链既有「回传前再次校验」
 * 同型（同仓同一谓词），本类不覆盖，批次文档如实声明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutofillPickerViewModelWriteBackTest {

    private val fingerprint = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"

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
     * 等待走 `withContext(Dispatchers.IO)`（与 `AutofillPickerViewModelSessionLockTest` 同法）——
     * **不得**在 runBlocking 主线程 `Thread.sleep`：那会阻塞 BlockingEventLoop。
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

    /** 询问流等待（同上：sleep 必须在 IO 线程，防阻塞 runBlocking event loop）。 */
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
    fun `满足条件时置起询问_同意后写入应用关联自定义字段并清理询问`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }
        val entryId = entry.id.toHexString()

        val outcome = CompletableDeferred<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle(fingerprint),
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
        assertEquals("com.tencent.mobileqq", ask?.proposedPackage)

        vm.completeBindingWriteBack(true)
        job.join()

        assertTrue(outcome.await())
        val call = fake.lastAppBindingByEntry[entryId]
        assertEquals("com.tencent.mobileqq", call?.packageName)
        assertEquals(fingerprint, call?.fingerprintHex)
        assertEquals("URL 标准字段不得被改写", "", entry.url)
        assertNull("写回完成后询问必须清空", vm.bindingWriteBackAsk.value)
    }

    @Test
    fun `条目已有网址仍询问_网址不被覆盖`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isNotBlank() }
        val originalUrl = entry.url
        val entryId = entry.id.toHexString()

        val outcome = CompletableDeferred<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle(fingerprint),
                    "com.tencent.mobileqq",
                    formWebDomain = null,
                    entryId = entryId,
                    entryTitle = entry.title
                )
            )
        }
        waitForAsk(vm)
        assertEquals("com.tencent.mobileqq", vm.bindingWriteBackAsk.value?.proposedPackage)
        vm.completeBindingWriteBack(true)
        job.join()

        assertTrue(outcome.await())
        assertEquals(fingerprint, fake.lastAppBindingByEntry[entryId]?.fingerprintHex)
        assertEquals("条目投影 URL 必须原样保留", originalUrl, entry.url)
    }

    @Test
    fun `表单带自报域仍询问_网页表单同样可写包名`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }

        val outcome = CompletableDeferred<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle(fingerprint),
                    "com.tencent.mobileqq",
                    formWebDomain = "example.com",
                    entryId = entry.id.toHexString(),
                    entryTitle = entry.title
                )
            )
        }
        waitForAsk(vm)
        assertEquals("com.tencent.mobileqq", vm.bindingWriteBackAsk.value?.proposedPackage)
        vm.completeBindingWriteBack(false)
        job.join()

        assertTrue(outcome.await())
        assertTrue("拒绝不得触发写回", fake.lastAppBindingByEntry.isEmpty())
        assertNull(vm.bindingWriteBackAsk.value)
    }

    @Test
    fun `拒绝时不写回_照常交付_询问清空且不持久化拒绝`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }

        val outcome = CompletableDeferred<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle(fingerprint),
                    "com.tencent.mobileqq",
                    formWebDomain = null,
                    entryId = entry.id.toHexString(),
                    entryTitle = entry.title
                )
            )
        }
        waitForAsk(vm)

        vm.completeBindingWriteBack(false)
        job.join()

        assertTrue("拒绝＝不写回但照常交付", outcome.await())
        assertTrue("拒绝不得触发写回", fake.lastAppBindingByEntry.isEmpty())
        assertNull(vm.bindingWriteBackAsk.value)
    }

    @Test
    fun `写入成功后同一会话内该包名已绑定_不再重复询问`() = runBlocking {
        val fake = FakeVaultRepository()
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }
        val entryId = entry.id.toHexString()

        val first = CompletableDeferred<Boolean>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) {
            first.complete(
                vm.offerAppBindingWriteBack(
                    CallerCertDigests.ofSingle(fingerprint),
                    "com.tencent.mobileqq",
                    formWebDomain = null,
                    entryId = entryId,
                    entryTitle = entry.title
                )
            )
        }
        waitForAsk(vm)
        vm.completeBindingWriteBack(true)
        firstJob.join()
        assertTrue(first.await())

        val second = vm.offerAppBindingWriteBack(
            CallerCertDigests.ofSingle(fingerprint),
            "com.tencent.mobileqq",
            formWebDomain = null,
            entryId = entryId,
            entryTitle = entry.title
        )
        assertTrue("已绑定＝不问但照常交付", second)
        assertNull("已绑定不得再置起询问", vm.bindingWriteBackAsk.value)
        assertEquals("不得重复写入", 1, fake.lastAppBindingByEntry.size)
    }

    @Test
    fun `只读会话不询问`() = runBlocking {
        val fake = FakeVaultRepository()
        fake.sessionReadOnly = true
        val vm = newVm(fake)
        loadEntriesBlocking(vm)
        val entry = vm.entries.value.first { it.url.isBlank() }

        val proceed = vm.offerAppBindingWriteBack(
            CallerCertDigests.ofSingle(fingerprint),
            "com.tencent.mobileqq",
            formWebDomain = null,
            entryId = entry.id.toHexString(),
            entryTitle = entry.title
        )

        assertTrue("只读会话＝不问但照常交付（写回一票否决，不影响填充）", proceed)
        assertTrue("只读会话不得触发写回", fake.lastAppBindingByEntry.isEmpty())
        assertNull(vm.bindingWriteBackAsk.value)
    }
}
