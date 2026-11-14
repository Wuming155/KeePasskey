package com.keepasskey.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.MainActivity
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.RealSettingsRepository
import com.keepasskey.app.data.repository.UserSettings
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.notification.NotificationIntents
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.screens.unlock.BiometricAutoPrompt
import com.keepasskey.app.ui.screens.unlock.BiometricUnlockCoordinator
import com.keepasskey.app.ui.screens.unlock.BiometricUnlockPrompt
import com.keepasskey.app.ui.screens.unlock.UnlockMode
import com.keepasskey.app.ui.screens.unlock.UnlockStage
import com.keepasskey.app.ui.screens.unlock.UnlockUiState
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 专用 AVD 上验证通知锁定与密码回退；真实指纹授权仍需已录入指纹的设备验收。 */
@RunWith(AndroidJUnit4::class)
class NotificationLockUnlockDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val graph by lazy {
        EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)
    }
    private val repository get() = graph.vaultRepository()
    private val session get() = graph.databaseSession()
    private val settings by lazy { RealSettingsRepository(context) }
    private val storage by lazy { BiometricCredentialStorage(context) }
    private var previousSettings: UserSettings? = null
    private var previousActivePath: String? = null
    private var vault: File? = null
    private var databaseId: String? = null
    private val password = "Notification-Regression-Master".toCharArray()

    @Before
    fun prepareVault() = runBlocking {
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        previousSettings = settings.getSettings().first()
        previousActivePath = repository.getDatabases().first().firstOrNull { it.isActive }?.path
        settings.setBiometricEnabled(false)
        settings.setAutoLockBackground(false)
        settings.setAutoLockForegroundEnabled(false)
        val serviceInfo = automation.serviceInfo ?: AccessibilityServiceInfo()
        serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        val folder = File(context.cacheDir, "notification-unlock-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, "vault.kdbx")
        vault = file
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "Notification Regression",
            rootGroup = KdbxGroup(name = "Root")
        )
        try {
            file.outputStream().use { KdbxFile.save(it, database, password, null) }
        } finally {
            database.clearSensitiveData()
        }
        assertTrue(repository.importExternalDatabase("Notification Regression", file.absolutePath) is KdbxResult.Success)
        repository.selectDatabase(file.absolutePath)
        databaseId = requireNotNull(repository.databaseInfoForPath(file.absolutePath)).id
    }

    @After
    fun cleanup() = runBlocking {
        repository.lockDatabase()
        databaseId?.let {
            storage.clearCredential(it)
            KeystoreManager(context).deleteKey(KeystoreManager.sealAliasFor(it))
        }
        vault?.let { file ->
            val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
            catalog.saveKnownDatabases(catalog.loadKnownDatabases().filterNot { it.path == file.absolutePath })
            file.delete()
            File(file.absolutePath + ".bak").delete()
            file.parentFile?.delete()
        }
        previousActivePath?.let { repository.selectDatabase(it) }
        previousSettings?.let {
            settings.setBiometricEnabled(it.biometricEnabled)
            settings.setAutoLockBackground(it.autoLockBackground)
            settings.setAutoLockTimeoutSeconds(it.autoLockTimeoutSeconds)
            settings.setAutoLockForegroundEnabled(it.autoLockForegroundEnabled)
        }
        password.fill('\u0000')
    }

    @Test
    fun notificationAndAutomaticBackgroundLockAllowPasswordUnlock() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            unlockThroughUi()
            storage.saveEncryptedCredential(requireNotNull(databaseId), ByteArray(12) { 1 }, ByteArray(32) { 2 })
            runBlocking { settings.setBiometricEnabled(true) }

            scenario.moveToState(Lifecycle.State.CREATED)
            NotificationIntents.lockVaultNow(context).send()
            await("后台通知锁定必须完成") { session.state.value == DatabaseSession.SessionState.LOCKED }
            scenario.moveToState(Lifecycle.State.RESUMED)
            unlockThroughUi()

            NotificationIntents.lockVaultNow(context).send()
            await("前台通知锁定必须完成") { session.state.value == DatabaseSession.SessionState.LOCKED }
            unlockThroughUi()

            runBlocking {
                settings.setAutoLockTimeoutSeconds(0)
                settings.setAutoLockBackground(true)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            await("后台自动锁定必须完成") { session.state.value == DatabaseSession.SessionState.LOCKED }
            scenario.moveToState(Lifecycle.State.RESUMED)
            unlockThroughUi()
        }
    }

    @Test
    fun savedHostDoesNotConsumeAutoPromptAndIgnoredSystemRequestTimesOut() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }
            scenario.moveToState(Lifecycle.State.CREATED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val state = MutableStateFlow(
                UnlockUiState(
                    hasDatabase = true,
                    isQuickUnlockAvailable = true,
                    unlockMode = UnlockMode.QUICK_UNLOCK,
                    biometricAutoPrompt = BiometricAutoPrompt.PENDING
                )
            )
            val auth = BiometricAuthManager(KeystoreManager(context))
            val prompt = BiometricUnlockPrompt(scope, timeoutMillis = 200L)
            val coordinator = BiometricUnlockCoordinator(
                scope = scope,
                uiState = state,
                events = MutableSharedFlow(),
                vaultRepository = repository,
                activeDbId = { databaseId },
                biometricAuthManager = auth,
                biometricCredentialStorage = storage,
                prompt = prompt)
            try {
                instrumentation.runOnMainSync {
                    assertTrue(activity.supportFragmentManager.isStateSaved)
                    assertFalse(coordinator.onBiometricAutoPromptRequested(activity))
                    assertEquals(BiometricAutoPrompt.PENDING, state.value.biometricAutoPrompt)
                    assertFalse(state.value.isLoading)
                    state.value = state.value.copy(
                        isLoading = true,
                        loadStage = UnlockStage.UNSEALING,
                        biometricAutoPrompt = BiometricAutoPrompt.CONSUMED
                    )
                    prompt.start(
                        onResult = {
                            coordinator.handleBiometricResult(it, storage, requireNotNull(databaseId), ByteArray(0))
                        }
                    ) { callback ->
                        auth.authenticate(activity, title = "Notification Regression", onResult = callback)
                    }
                }
                await("系统忽略认证后必须退出加载态") { !state.value.isLoading }
                assertEquals(UnlockMode.STANDARD, state.value.unlockMode)
                assertEquals(R.string.sec_biometric_timeout, state.value.errorMessage?.resId)
                assertEquals(null, state.value.loadStage)
                scenario.moveToState(Lifecycle.State.RESUMED)
                instrumentation.runOnMainSync {
                    assertFalse(coordinator.onBiometricAutoPromptRequested(activity))
                }
            } finally {
                instrumentation.runOnMainSync { prompt.cancel() }
                scope.cancel()
            }
        }
    }

    private fun unlockThroughUi() {
        automation.waitForIdle(300L, 5_000L)
        await("必须恢复主密码输入框") { nodes().any { it.className == "android.widget.EditText" && it.isEnabled } }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, String(password))
        }
        await("密码输入必须生效") {
            nodes().firstOrNull { it.className == "android.widget.EditText" && it.isEnabled }
                ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true
        }
        val label = context.getString(R.string.unlock_btn_unlock)
        await("主密码解锁按钮必须可用") { nodes().any { it.text?.toString() == label && it.isEnabled } }
        await("必须通过真实解锁按钮提交") {
            var button = nodes().firstOrNull { it.text?.toString() == label && it.isEnabled }
            while (button != null && !button.isClickable && button.parent != null) button = button.parent
            button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        await("主密码必须真实打开合成密码库") {
            session.state.value == DatabaseSession.SessionState.OPENED &&
                session.currentFile?.absolutePath == vault?.absolutePath
        }
        await("解锁成功必须离开解锁页") { nodes().none { it.text?.toString() == label } }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        collect(automation.rootInActiveWindow)
        return result
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50L)
        }
        assertTrue(message, condition())
    }
}
