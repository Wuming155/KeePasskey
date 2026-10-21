package com.keepasskey.app.ui.screens.recovery

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeyFileVaultCopyStore
import com.keepasskey.app.security.RecoveryAlertStore
import com.keepasskey.app.security.VaultRecoveryCoordinator
import com.keepasskey.app.security.VaultRecoveryStore
import com.keepasskey.app.testutil.MainDispatcherGuard
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.result.KdbxResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelPreviewBindingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        MainDispatcherGuard.tearDown()
    }

    @Test
    fun `A预览挂起时不能切换或并发预览B且迟到A不能授权恢复B`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (store, coordinator) = createCoordinator()
        val bytesA = byteArrayOf(1, 2, 3, 4)
        val bytesB = byteArrayOf(5, 6, 7, 8)
        val officialA = createOfficial("a.kdbx", byteArrayOf(11, 12))
        val officialB = createOfficial("b.kdbx", byteArrayOf(21, 22))
        writeRecovery(store, "vault-a", officialA, "A", bytesA)
        writeRecovery(store, "vault-b", officialB, "B", bytesB)
        coordinator.refresh()
        val pendingA = coordinator.pending.value.single { it.entry.vaultId == "vault-a" }
        val pendingB = coordinator.pending.value.single { it.entry.vaultId == "vault-b" }
        val viewModel = MainDispatcherGuard.track(newViewModel(coordinator))
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        var startedPreviews = 0
        viewModel.previewOperation = { target, _ ->
            startedPreviews += 1
            if (target.entry.vaultId == "vault-a") {
                enteredA.complete(Unit)
                releaseA.await()
            }
            KdbxResult.Success(previewFor(target))
        }

        viewModel.select(RecoveryViewModel.keyOf(pendingA))
        val passwordA = "fake-a".toCharArray()
        viewModel.preview(pendingA, passwordA)
        runCurrent()
        enteredA.await()
        assertTrue(viewModel.uiState.value.busy)

        viewModel.select(RecoveryViewModel.keyOf(pendingB))
        val rejectedPasswordB = "fake-b".toCharArray()
        viewModel.preview(pendingB, rejectedPasswordB)
        assertEquals(RecoveryViewModel.keyOf(pendingA), viewModel.uiState.value.selectedKey)
        assertEquals("busy期间第二次预览必须被拦截", 1, startedPreviews)
        assertTrue(rejectedPasswordB.all { it == '0' })

        releaseA.complete(Unit)
        runCurrent()
        assertFalse(viewModel.uiState.value.busy)
        assertTrue(passwordA.all { it == '0' })
        assertEquals(RecoveryViewModel.keyOf(pendingA), viewModel.uiState.value.selectedKey)
        assertEquals(pendingA.entry.payloadSha256, viewModel.uiState.value.previewBinding?.target?.payloadSha256)

        viewModel.select(RecoveryViewModel.keyOf(pendingB))
        viewModel.restore(pendingB, forceOverwrite = true)
        runCurrent()
        assertFalse(viewModel.uiState.value.busy)
        assertArrayEquals(byteArrayOf(21, 22), officialB.readBytes())
        assertPayloadEquals(bytesA, store, pendingA)
        assertPayloadEquals(bytesB, store, pendingB)

        // B 只有在自己的预览完成后才获得可恢复凭证。
        viewModel.preview(pendingB, "fake-b-valid".toCharArray())
        runCurrent()
        assertEquals(pendingB.entry.payloadSha256, viewModel.uiState.value.previewBinding?.target?.payloadSha256)
        assertEquals(RecoveryViewModel.keyOf(pendingB), viewModel.uiState.value.selectedKey)
        assertArrayEquals(byteArrayOf(11, 12), officialA.readBytes())
    }

    @Test
    fun `A预览迟到于B快速预览时最终凭证仍属于B`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (store, coordinator) = createCoordinator()
        val officialA = createOfficial("slow-a.kdbx", byteArrayOf(101, 102))
        val officialB = createOfficial("fast-b.kdbx", byteArrayOf(111, 112))
        writeRecovery(store, "slow-vault-a", officialA, "A", byteArrayOf(121, 122))
        writeRecovery(store, "fast-vault-b", officialB, "B", byteArrayOf(31, 32))
        coordinator.refresh()
        val oldA = coordinator.pending.value.single { it.entry.vaultId == "slow-vault-a" }
        val pendingB = coordinator.pending.value.single { it.entry.vaultId == "fast-vault-b" }
        val viewModel = MainDispatcherGuard.track(newViewModel(coordinator))
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        viewModel.previewOperation = { target, _ ->
            if (target.entry.vaultId == "slow-vault-a") {
                enteredA.complete(Unit)
                releaseA.await()
            }
            KdbxResult.Success(previewFor(target))
        }

        viewModel.select(RecoveryViewModel.keyOf(oldA))
        viewModel.preview(oldA, "slow-a".toCharArray())
        runCurrent()
        enteredA.await()
        // 刷新会让 A 的旧请求失效；随后 B 的新请求先完成。
        viewModel.refresh()
        viewModel.select(RecoveryViewModel.keyOf(pendingB))
        viewModel.preview(pendingB, "fast-b".toCharArray())
        runCurrent()
        assertEquals(pendingB.entry.payloadSha256, viewModel.uiState.value.previewBinding?.target?.payloadSha256)

        releaseA.complete(Unit)
        runCurrent()

        assertEquals(RecoveryViewModel.keyOf(pendingB), viewModel.uiState.value.selectedKey)
        assertEquals(pendingB.entry.payloadSha256, viewModel.uiState.value.previewBinding?.target?.payloadSha256)
        assertEquals("B", viewModel.uiState.value.preview?.vaultName)
        assertArrayEquals(byteArrayOf(101, 102), officialA.readBytes())
        assertArrayEquals(byteArrayOf(111, 112), officialB.readBytes())
    }

    @Test
    fun `keep会使迟到预览失效且不能重新选中或授权`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (store, coordinator) = createCoordinator()
        val official = createOfficial("keep.kdbx", byteArrayOf(31, 32))
        val payload = byteArrayOf(41, 42, 43)
        writeRecovery(store, "keep-vault", official, "Keep", payload)
        coordinator.refresh()
        val pending = coordinator.pending.value.single()
        val viewModel = MainDispatcherGuard.track(newViewModel(coordinator))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        viewModel.previewOperation = { target, _ ->
            entered.complete(Unit)
            release.await()
            KdbxResult.Success(previewFor(target))
        }

        viewModel.select(RecoveryViewModel.keyOf(pending))
        viewModel.preview(pending, "fake-password".toCharArray())
        runCurrent()
        entered.await()
        viewModel.keep()
        release.complete(Unit)
        runCurrent()

        assertNull(viewModel.uiState.value.selectedKey)
        assertNull(viewModel.uiState.value.preview)
        assertNull(viewModel.uiState.value.previewBinding)
        assertFalse(viewModel.uiState.value.busy)
        assertArrayEquals(byteArrayOf(31, 32), official.readBytes())
        assertPayloadEquals(payload, store, pending)
    }

    @Test
    fun `当前预览取消后退出busy且不留下恢复凭证`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (store, coordinator) = createCoordinator()
        val official = createOfficial("cancel.kdbx", byteArrayOf(35, 36))
        val payload = byteArrayOf(45, 46, 47)
        writeRecovery(store, "cancel-vault", official, "Cancel", payload)
        coordinator.refresh()
        val pending = coordinator.pending.value.single()
        val viewModel = MainDispatcherGuard.track(newViewModel(coordinator))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        viewModel.previewOperation = { _, _ ->
            entered.complete(Unit)
            release.await()
            throw CancellationException("synthetic preview cancellation")
        }

        viewModel.select(RecoveryViewModel.keyOf(pending))
        viewModel.preview(pending, "fake-password".toCharArray())
        runCurrent()
        entered.await()
        assertTrue(viewModel.uiState.value.busy)
        release.complete(Unit)
        runCurrent()

        assertFalse(viewModel.uiState.value.busy)
        assertNull(viewModel.uiState.value.preview)
        assertNull(viewModel.uiState.value.previewBinding)
        assertArrayEquals(byteArrayOf(35, 36), official.readBytes())
        assertPayloadEquals(payload, store, pending)
    }

    @Test
    fun `refresh替换payload时旧预览迟到结果不能授权新版本恢复`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val (store, coordinator) = createCoordinator()
        val officialA = createOfficial("refresh-a.kdbx", byteArrayOf(51, 52))
        val officialB = createOfficial("refresh-b.kdbx", byteArrayOf(61, 62))
        val payloadA = byteArrayOf(71, 72)
        val payloadB = byteArrayOf(81, 82)
        val replacementA = byteArrayOf(91, 92, 93)
        writeRecovery(store, "refresh-vault-a", officialA, "A", payloadA)
        writeRecovery(store, "refresh-vault-b", officialB, "B", payloadB)
        coordinator.refresh()
        val oldA = coordinator.pending.value.single { it.entry.vaultId == "refresh-vault-a" }
        val pendingB = coordinator.pending.value.single { it.entry.vaultId == "refresh-vault-b" }
        val viewModel = MainDispatcherGuard.track(newViewModel(coordinator))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        viewModel.previewOperation = { target, _ ->
            entered.complete(Unit)
            release.await()
            KdbxResult.Success(previewFor(target))
        }

        viewModel.select(RecoveryViewModel.keyOf(oldA))
        viewModel.preview(oldA, "fake-password".toCharArray())
        runCurrent()
        entered.await()
        assertTrue(store.write("refresh-vault-a", "A", officialA.absolutePath, replacementA))
        viewModel.refresh()
        val newA = viewModel.uiState.value.pending.single { it.entry.vaultId == "refresh-vault-a" }
        assertTrue(oldA.entry.payloadSha256 != newA.entry.payloadSha256)
        release.complete(Unit)
        runCurrent()

        assertEquals(
            "替换后的副本键必须随载荷身份变化（旧预览不得授权新版本）",
            false,
            RecoveryViewModel.keyOf(oldA) == RecoveryViewModel.keyOf(newA)
        )
        assertNull("副本内容被替换后旧选择必须失效（键含载荷身份）", viewModel.uiState.value.selectedKey)
        assertNull(viewModel.uiState.value.preview)
        assertNull(viewModel.uiState.value.previewBinding)
        viewModel.select(RecoveryViewModel.keyOf(newA))
        viewModel.restore(newA, forceOverwrite = true)
        runCurrent()
        assertFalse(viewModel.uiState.value.busy)
        assertArrayEquals("未预览新版本时不得放行恢复", byteArrayOf(51, 52), officialA.readBytes())
        assertPayloadEquals(replacementA, store, newA)
        assertPayloadEquals(payloadB, store, pendingB)
        assertEquals(2, store.list().size)
    }

    private fun createCoordinator(): Pair<VaultRecoveryStore, VaultRecoveryCoordinator> {
        val store = VaultRecoveryStore(null, DebugLogBuffer()).apply {
            baseDirOverride = tempFolder.newFolder("recovery")
        }
        val coordinator = VaultRecoveryCoordinator(
            context = null,
            store = store,
            alertStore = RecoveryAlertStore(null),
            keyFileCopyStore = KeyFileVaultCopyStore(null, null, DebugLogBuffer()),
            debugLog = DebugLogBuffer()
        ).apply { filesDirOverride = tempFolder.root }
        return store to coordinator
    }

    private fun newViewModel(coordinator: VaultRecoveryCoordinator) =
        RecoveryViewModel(coordinator, StringsProvider { _, _ -> "recovery preview" })

    private fun createOfficial(name: String, bytes: ByteArray): File =
        File(tempFolder.root, name).apply { writeBytes(bytes) }

    private suspend fun writeRecovery(
        store: VaultRecoveryStore,
        vaultId: String,
        official: File,
        name: String,
        payload: ByteArray
    ) {
        assertTrue(store.write(vaultId, name, official.absolutePath, payload))
    }

    private suspend fun assertPayloadEquals(
        expected: ByteArray,
        store: VaultRecoveryStore,
        pending: VaultRecoveryCoordinator.Pending
    ) {
        val bytes = store.readPayload(pending.entry)
        assertNotNull("恢复载荷必须完整且仍可读取", bytes)
        requireNotNull(bytes).also {
            try {
                assertArrayEquals(expected, it)
            } finally {
                it.fill(0)
            }
        }
    }

    private fun previewFor(target: VaultRecoveryCoordinator.Pending) =
        VaultRecoveryCoordinator.Preview(target.entry.vaultName, 1, 0, 1, 0, 0)
}
