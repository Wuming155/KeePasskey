package com.keepasskey.app.passkey

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillNodeIdentity
import com.keepasskey.app.autofill.DeviceAutofillProbe
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ISSUE-P1-590：从 Credential Manager 真请求进入生产 Activity，并注入持久化 Failure。 */
@RunWith(AndroidJUnit4::class)
class PasskeyPersistenceActivityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val probe = DeviceAutofillProbe()
    private val persistCalls = AtomicInteger()
    private val activityCount = AtomicInteger()
    private lateinit var application: Application
    private lateinit var settingsStore: ExtendedSettingsStore
    private lateinit var originalSettings: com.keepasskey.app.ui.screens.settings.ExtendedSettings
    private lateinit var session: DatabaseSession
    private lateinit var vaultFile: File
    private lateinit var vaultPassword: CharArray
    private lateinit var initialVaultBytes: ByteArray
    private lateinit var failingRepository: VaultRepository
    private var originalCredentialService: String? = null
    private var activityFailureWasPresented = false
    private var activityResultCode: Int? = null
    private var activityResultCaptureError: Throwable? = null

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
            if (activity is PasskeyCreateActivity) {
                // Hilt 已注入生产对象；替换点仅存在于 androidTest，失败仍穿过真实 Activity。
                activity.vaultRepository = failingRepository
                activityCount.incrementAndGet()
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is PasskeyCreateActivity) {
                activityFailureWasPresented = true
                runCatching {
                    Activity::class.java.getDeclaredField("mResultCode").apply { isAccessible = true }.getInt(activity)
                }.onSuccess { activityResultCode = it }
                    .onFailure { activityResultCaptureError = it }
            }
        }
    }

    @Before
    fun prepare() {
        probe.requireAutomation()
        probe.prepare()
        application = targetContext.applicationContext as Application
        originalCredentialService = nullableSetting(probe.shell("settings get secure credential_service"))
        val provider = "${targetContext.packageName}/${KeePasskeyCredentialProviderService::class.java.name}"
        probe.shell("settings put secure credential_service $provider")
        assertEquals(provider, probe.shell("settings get secure credential_service").trim())

        settingsStore = ExtendedSettingsStore(targetContext.applicationContext)
        originalSettings = settingsStore.load()
        settingsStore.save(
            originalSettings.copy(
                credentialProviderEnabled = true,
                passkeySupportEnabled = true,
                skipDalVerification = true
            )
        )

        val graph = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            AutofillDeviceGraph::class.java
        )
        // 创建测试库后再首次取 VaultRepository；其构造期会扫描 filesDir。
        session = graph.databaseSession()
        vaultFile = File(targetContext.filesDir, "p1-590-cm-${System.nanoTime()}.kdbx")
        vaultPassword = "P1-590-Activity-Probe-2026".toCharArray()
        assertTrue(
            "生产 DatabaseSession 必须能创建并打开活动测试库",
            runBlocking { session.create(vaultFile, "P1-590 Activity Probe", vaultPassword, useArgon2 = false) }.isSuccess
        )
        initialVaultBytes = vaultFile.readBytes()
        failingRepository = failureInjecting(graph.vaultRepository())
        application.registerActivityLifecycleCallbacks(activityCallbacks)
        probe.shell("logcat -c")
    }

    @After
    fun restore() {
        runCatching { probe.shell("input keyevent KEYCODE_BACK") }
        runCatching { probe.shell("input keyevent KEYCODE_BACK") }
        runCatching { probe.shell("am force-stop ${testContext.packageName}") }
        runCatching { application.unregisterActivityLifecycleCallbacks(activityCallbacks) }
        if (::session.isInitialized) runCatching { runBlocking { session.close() } }
        if (::settingsStore.isInitialized) runCatching { settingsStore.save(originalSettings) }
        if (originalCredentialService == null) {
            runCatching { probe.shell("settings delete secure credential_service") }
        } else {
            runCatching { probe.shell("settings put secure credential_service $originalCredentialService") }
        }
        if (::vaultPassword.isInitialized) vaultPassword.fill('\u0000')
        if (::initialVaultBytes.isInitialized) initialVaultBytes.fill(0)
        if (::vaultFile.isInitialized) {
            vaultFile.delete()
            File(vaultFile.absolutePath + ".bak").delete()
        }
    }

    @Test
    fun `Credential Manager持久化失败必须取消而不回传成功凭据`() {
        startPasskeyClient()
        selectProviderEntry()
        confirmCreate()
        confirmPersistenceFailure()
        assertCanceledFailureDelivery()
    }

    private fun startPasskeyClient() {
        val intent = Intent()
            .setClassName(testContext.packageName, CredentialSaveClientActivity::class.java.name)
            .putExtra(CredentialSaveClientActivity.EXTRA_MODE, CredentialSaveClientActivity.MODE_PASSKEY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        testContext.startActivity(intent)
        assertTrue(
            "真实系统创建请求必须到达 provider；logcat=${probe.shell("logcat -d -s $PROVIDER_TAG")}",
            awaitProviderCreateCallback()
        )
    }

    private fun awaitProviderCreateCallback(): Boolean {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (probe.shell("logcat -d -s $PROVIDER_TAG").contains(CREATE_CALLBACK_MARKER)) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun selectProviderEntry() {
        val entry = probe.awaitNode("系统创建面板中的 Passkey 候选", TIMEOUT_MS) {
            val text = probe.textOf(it)
            it.isVisibleToUser && it.isEnabled &&
                (text.contains("cm-probe-user", ignoreCase = true) || text.contains("CM Probe User", ignoreCase = true))
        }
        assertNotNull("系统面板必须显示本 provider 的创建候选；窗口=${probe.snapshot()}", entry)
        assertTrue("系统面板候选必须可点选", probe.click(requireNotNull(entry)))
    }

    private fun confirmCreate() {
        val confirm = probe.awaitActiveNode("Passkey 创建的用户确认", TIMEOUT_MS) {
            AutofillNodeIdentity.appButton(
                it,
                targetContext.packageName,
                targetContext.getString(R.string.passkey_confirm_ok)
            )
        }
        assertNotNull("真实 PasskeyCreateActivity 必须显示创建确认页；窗口=${probe.snapshot()}", confirm)
        assertTrue("创建确认按钮必须能触发真实 Activity 回调", probe.click(requireNotNull(confirm)))
    }

    private fun confirmPersistenceFailure() {
        val message = targetContext.getString(R.string.passkey_reject_persistence_failed)
        val rejection = probe.awaitActiveNode("持久化失败的拒绝页", TIMEOUT_MS) {
            AutofillNodeIdentity.appNode(it, targetContext.packageName) &&
                AutofillNodeIdentity.exactText(it, message)
        }
        assertNotNull("真实 Activity 必须呈现持久化失败原因；窗口=${probe.snapshot()}", rejection)
        assertEquals("失败注入必须只命中一次", 1, persistCalls.get())
        assertEquals("失败拒签分支必须真实启动 PasskeyCreateActivity", 1, activityCount.get())

        val acknowledge = probe.awaitActiveNode("持久化失败拒绝页确认按钮", TIMEOUT_MS) {
            AutofillNodeIdentity.appButton(
                it,
                targetContext.packageName,
                targetContext.getString(R.string.cred_reject_confirm)
            )
        }
        assertNotNull("失败说明必须由用户确认后才返回取消", acknowledge)
        assertTrue("拒绝页确认必须结束真实 Activity", probe.click(requireNotNull(acknowledge)))
    }

    private fun assertCanceledFailureDelivery() {
        awaitActivityDestruction()
        assertTrue("失败确认后真实 PasskeyCreateActivity 必须销毁；窗口=${probe.snapshot()}", activityFailureWasPresented)
        assertNull("必须能读取 Android Activity 的原始 resultCode", activityResultCaptureError)
        assertEquals(
            "真实 Activity 必须向 Credential Manager 返回 RESULT_CANCELED",
            Activity.RESULT_CANCELED,
            activityResultCode
        )
        assertNull(
            "Credential Manager 客户端不得收到 CreatePublicKeyCredentialResponse",
            probe.awaitNode("禁止的成功回执", SHORT_SETTLE_MS) {
                it.packageName?.toString() == testContext.packageName &&
                    probe.textOf(it).startsWith(CLIENT_SUCCESS_STATUS)
            }
        )
        assertEquals("只有被注入的持久化失败分支可以到达", 1, persistCalls.get())
        assertArrayEquals("失败路径不得改写正式测试库字节", initialVaultBytes, vaultFile.readBytes())
        assertFalse(
            "未确认落盘的凭据不得出现在活动数据库树",
            session.databaseFlow.value!!.rootGroup.allEntries().any {
                PasskeyData.fromCustomFields(it.customFields)?.userName == "cm-probe-user"
            }
        )
    }

    private fun awaitActivityDestruction() {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!activityFailureWasPresented && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun failureInjecting(delegate: VaultRepository): VaultRepository =
        Proxy.newProxyInstance(
            VaultRepository::class.java.classLoader,
            arrayOf(VaultRepository::class.java)
        ) { _, method, args ->
            if (method.name == "saveOrReplacePasskeyEntry") {
                persistCalls.incrementAndGet()
                KdbxResult.Failure(
                    IOException("synthetic-activity-persistence-failure"),
                    code = KdbxError.SAVE_FAILED
                )
            } else {
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            }
        } as VaultRepository

    private fun nullableSetting(raw: String): String? = raw.trim().takeUnless { it.isEmpty() || it == "null" }

    private companion object {
        const val CREATE_CALLBACK_MARKER = "onBeginCreateCredentialRequest"
        const val CLIENT_ERROR_PREFIX = "CM-SAVE-STATUS:onError:"
        const val CLIENT_SUCCESS_STATUS = "CM-SAVE-STATUS:onResult"
        const val PROVIDER_TAG = "KeePasskeyCredProvider"
        const val TIMEOUT_MS = 15_000L
        const val SHORT_SETTLE_MS = 1_000L
        const val POLL_INTERVAL_MS = 500L
    }
}
