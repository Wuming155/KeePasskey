package com.keepasskey.app.ui.screens.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.security.AutoLockSessionGuard
import com.keepasskey.app.ui.model.VaultRemovalKind
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.database.session.SessionWriteReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 真实 MainActivity ViewModel 销毁后，已受理的应用级事务继续到盘并释放自动锁挂点。 */
@RunWith(AndroidJUnit4::class)
class CredentialRotationLifecycleAndroidTest {
    @Test fun activityDestroyDoesNotCancelCommittedRotationAndDeferredLockRuns() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.filesDir, "593-lifecycle-${UUID.randomUUID()}")
        check(directory.mkdir())
        val file = File(directory, "rotation.kdbx")
        val oldPassword = "Old#593#Lifecycle".toCharArray()
        val newPassword = "New#593#Lifecycle".toCharArray()
        var originalCiphertext: ByteArray? = null
        var previousActiveId: String? = null
        var session: DatabaseSession? = null
        var repository: VaultRepository? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var submittedInput: CharArray? = null

        var viewModel: SettingsViewModel? = null
        var runtime: CredentialRotationRuntime? = null
        var guard: AutoLockSessionGuard? = null
        var viewModelJob: Job? = null
        var runtimeJob: Job? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        try {
            val launchedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = launchedScenario
            launchedScenario.onActivity { activity ->
                viewModel = ViewModelProvider(activity).get(SettingsViewModel::class.java)
            }
            val vm = requireNotNull(viewModel)
            session = readPrivateField(vm, "databaseSession") as? DatabaseSession
                ?: error("SettingsViewModel.databaseSession has unexpected type")
            repository = readPrivateField(vm, "vaultRepository") as? VaultRepository
                ?: error("SettingsViewModel.vaultRepository has unexpected type")
            runtime = readPrivateField(vm, "credentialRotationRuntime") as? CredentialRotationRuntime
                ?: error("SettingsViewModel.credentialRotationRuntime has unexpected type")
            val actualSession = requireNotNull(session)
            val actualRepository = requireNotNull(repository)
            val actualRuntime = requireNotNull(runtime)
            val guardValue = readPrivateField(requireNotNull(runtime), "lockGuard")
            guard = guardValue as? AutoLockSessionGuard
                ?: error("CredentialRotationRuntime.lockGuard has unexpected type")
            viewModelJob = vm.viewModelScope.coroutineContext[Job]
            runtimeJob = actualRuntime.scope.coroutineContext[Job]
            check(viewModelJob?.isActive == true && runtimeJob?.isActive == true)

            previousActiveId = actualRepository.getDatabases().first().firstOrNull { it.isActive }?.id
            assertTrue("真实File建库失败", actualSession.create(file, "Rotation", oldPassword, useArgon2 = false).isSuccess)
            val path = file.absolutePath
            assertTrue("测试库登记失败", actualRepository.importExternalDatabase("593-lifecycle", path, "本地设备存储").isSuccess)
            originalCiphertext = file.readBytes()

            val core = requireNotNull(readPrivateField(actualSession, "core"))
            val saveRaw = readPrivateField(core, "saveWriter")
            val trackedRaw = readPrivateField(core, "trackedSaveWriter")
            @Suppress("UNCHECKED_CAST")
            val realSaveWriter = saveRaw as? (suspend (ByteArray) -> Unit)
                ?: error("SessionCore.saveWriter has unexpected type")
            @Suppress("UNCHECKED_CAST")
            val realTrackedWriter = trackedRaw as? (suspend (ByteArray, SessionWriteReceipt) -> Unit)
                ?: error("SessionCore.trackedSaveWriter has unexpected type")

            actualSession.close()
            assertTrue(actualSession.openStream(
                pathIdentifier = path,
                inputStreamProvider = { file.inputStream() },
                saveWriter = realSaveWriter,
                passwordChars = oldPassword,
                associatedFile = file,
                trackedSaveWriter = { bytes, receipt ->
                    entered.complete(Unit)
                    withTimeout(20_000) { release.await() }
                    realTrackedWriter(bytes, receipt)
                }
            ).isSuccess)

            val callerInput = newPassword.copyOf()
            submittedInput = callerInput
            launchedScenario.onActivity { activity ->
                requireNotNull(viewModel).masterKeyChangeController.submit(callerInput, activity = activity)
            }
            assertArrayEquals(CharArray(callerInput.size) { '0' }, callerInput)
            withTimeout(20_000) { entered.await() }
            assertTrue(actualRuntime.state.value.isChanging)

            launchedScenario.close()
            scenario = null
            assertFalse("ActivityScenario.close 必须真实清理 ViewModel", requireNotNull(viewModelJob).isActive)
            assertTrue("rotation runtime 不随页面取消", requireNotNull(runtimeJob).isActive)
            assertTrue("页面销毁后事务仍忙", requireNotNull(runtime).state.value.isChanging)
            requireNotNull(guard).triggerLock("593合成生命周期测试")
            assertEquals(DatabaseSession.SessionState.OPENED, actualSession.state.value)

            release.complete(Unit)
            withTimeout(20_000) { actualRuntime.state.first { !it.isChanging } }
            withTimeout(20_000) {
                actualSession.state.first { it == DatabaseSession.SessionState.LOCKED }
            }
            assertFalse("完成后事务挂锁已释放", requireNotNull(runtimeJob).isCancelled)
            assertNull("锁库必须擦除凭据缓存", actualSession.passwordSnapshot())
            assertNull("锁库必须失效当前票据", actualSession.captureCredentialRequest().ticket)
            val rotatedCiphertext = file.readBytes()
            try { assertFalse("轮换必须替换磁盘密文", requireNotNull(originalCiphertext).contentEquals(rotatedCiphertext)) }
            finally { rotatedCiphertext.fill(0) }
            openDatabase(file, newPassword)
            assertThrows(KdbxInvalidCredentialsException::class.java) { openDatabase(file, oldPassword) }
        } catch (error: Throwable) {
            throw error
        } finally {
            release.complete(Unit)
            scenario?.close()
            session?.close()
            val repositoryValue = repository
            if (repositoryValue != null) {
                val path = file.absolutePath
                if (repositoryValue.getDatabases().first().any { it.id == path }) {
                    repositoryValue.removeDatabase(path, VaultRemovalKind.EXTERNAL_LINK)
                }
                previousActiveId?.takeIf { id -> repositoryValue.getDatabases().first().any { it.id == id } }
                    ?.let { repositoryValue.selectDatabase(it) }
            }
            submittedInput?.fill('\u0000')
            oldPassword.fill('\u0000')
            newPassword.fill('\u0000')
            originalCiphertext?.fill(0)
            file.delete()
            directory.delete()
        }
    }

    private fun openDatabase(file: File, password: CharArray) {
        file.inputStream().use { KdbxFile.load(it, password).clearSensitiveData() }
    }

    private fun readPrivateField(owner: Any, name: String): Any? {
        val field = owner.javaClass.getDeclaredField(name)
        check(field.type != Any::class.java) { "$name reflection type must remain specific" }
        field.isAccessible = true
        return field.get(owner)
    }
}
