package com.keepasskey.app.passkey

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillNodeIdentity
import com.keepasskey.app.autofill.DeviceAutofillProbe
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P2-620: A real framework Credential Manager prepare handle survives provider-process death across two approved origins. */
@RunWith(AndroidJUnit4::class)
class CredentialManagerCrossDomainDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext
    private val testContext: Context = instrumentation.context
    private val probe = DeviceAutofillProbe()

    @Test
    fun prepareOldDomainCandidate(): Unit = runBlocking {
        val runId = System.nanoTime().toString()
        val vaultFile = File(targetContext.filesDir, "p2-620-cm-$runId.kdbx")
        val state = targetContext.getSharedPreferences(TARGET_STATE, Context.MODE_PRIVATE)
        val activeIds = ActiveDatabaseIdStore(targetContext)
        val browserStore = PasskeyPrivilegedBrowserStore(targetContext)
        val settings = ExtendedSettingsStore(targetContext)
        val previousActiveId = activeIds.load()
        val originalCredentialService = nullableSetting(shell("settings get secure credential_service"))
        val originalProviderEnabled = settings.load().credentialProviderEnabled
        val originalBrowserEnabled = browserStore.installedBrowsers()
            .firstOrNull { it.packageName == testContext.packageName }?.enabled == true
        val expectedCredentialService = "${targetContext.packageName}/${KeePasskeyCredentialProviderService::class.java.name}"
        val session = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            AutofillDeviceGraph::class.java
        ).databaseSession()
        val password = MASTER_PASSWORD.toCharArray()
        state.edit().clear()
            .putString(KEY_RUN_ID, runId)
            .putString(KEY_VAULT_PATH, vaultFile.absolutePath)
            .putString(KEY_ORIGINAL_CREDENTIAL_SERVICE, originalCredentialService ?: NULL_VALUE)
            .putBoolean(KEY_ORIGINAL_PROVIDER_ENABLED, originalProviderEnabled)
            .putBoolean(KEY_ORIGINAL_BROWSER_ENABLED, originalBrowserEnabled)
            .putString(KEY_PREVIOUS_ACTIVE_ID, previousActiveId ?: NULL_VALUE)
            .commit()

        try {
            assertTrue("test browser package must be admitted by the provider origin allowlist",
                browserStore.setEnabled(testContext.packageName, true))
            settings.save(settings.load().copy(credentialProviderEnabled = true))
            shell("settings put secure credential_service $expectedCredentialService")
            assertEquals(expectedCredentialService, shell("settings get secure credential_service").trim())
            shell("run-as ${testContext.packageName} rm -f files/${CredentialBrowserGetClientActivity.STATUS_FILE}")
            assertTrue("synthetic vault creation failed",
                runBlocking { session.create(vaultFile, "P2-620 CM A/B", password, useArgon2 = false) } is KdbxResult.Success)
            session.saveEntry(passwordEntry("P2-620 A entry", "https://cm-p2-620-a.localhost:18443/login", USER_A, PASSWORD_A))
            session.saveEntry(passwordEntry("P2-620 B entry", "https://cm-p2-620-b.localhost:18443/login", USER_B, PASSWORD_B))
            assertTrue("synthetic A/B entries must persist", runBlocking { session.save() } is KdbxResult.Success)
            activeIds.save(vaultFile.absolutePath)
            state.edit()
                .putInt(KEY_PREPARE_APP_PID, android.os.Process.myPid())
                .commit()

            shell("logcat -c")
            startBrowserCommand(CredentialBrowserGetClientActivity.ACTION_PREPARE_A, ORIGIN_A)
            val prepared = awaitBrowserStatusPrefix(
                "prepared:${CredentialBrowserGetClientActivity.ACTION_PREPARE_A}:",
                CLIENT_TIMEOUT_MS
            )
            val observedStatus = clientStatus()
            assertTrue(
                "real Credential Manager did not prepare domain A; prepared=$prepared status=$observedStatus " +
                    "testPackage=${testContext.packageName} files=${testContext.filesDir}",
                prepared?.endsWith("password=true") == true
            )
            val browserPid = clientStatus()["pid"]?.toIntOrNull() ?: -1
            assertTrue("independent browser-origin client process must be live", browserPid > 0)
            state.edit().putInt(KEY_BROWSER_PID, browserPid).commit()
            val systemCredentialLog = shell("logcat -d -s CredentialManager")
            assertTrue(
                "Android system must route the real password option to the production provider; $systemCredentialLog",
                systemCredentialLog.contains("Remote provider responded with a valid response") &&
                    systemCredentialLog.contains(KeePasskeyCredentialProviderService::class.java.name)
            )
            startBrowserCommand(CredentialBrowserGetClientActivity.ACTION_GET_A, null)
            val heldCandidateA = probe.awaitActiveNode(
                "system-held Credential Manager A candidate", CM_PICKER_TIMEOUT_MS,
                pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
            ) { node ->
                val text = probe.textOf(node)
                text.contains(TITLE_A) || text.contains(USER_A)
            }
            assertNotNull("Credential Manager must display the prepared A candidate before provider death; ${probe.snapshot()}",
                heldCandidateA)
        } catch (failure: Throwable) {
            runCatching { startBrowserCommand(CredentialBrowserGetClientActivity.ACTION_FINISH, null) }
            restore(originalCredentialService, originalProviderEnabled, originalBrowserEnabled, previousActiveId, vaultFile, session)
            state.edit().clear().commit()
            throw failure
        } finally {
            password.fill('\u0000')
        }
    }

    @Test
    fun resumeOldCandidateAfterProviderProcessDeath(): Unit = runBlocking {
        val state = targetContext.getSharedPreferences(TARGET_STATE, Context.MODE_PRIVATE)
        val runId = requireNotNull(state.getString(KEY_RUN_ID, null)) { "prepare phase state missing" }
        val vaultPath = requireNotNull(state.getString(KEY_VAULT_PATH, null))
        val vaultFile = File(vaultPath)
        val originalCredentialService = state.getString(KEY_ORIGINAL_CREDENTIAL_SERVICE, NULL_VALUE)
        val originalProviderEnabled = state.getBoolean(KEY_ORIGINAL_PROVIDER_ENABLED, false)
        val originalBrowserEnabled = state.getBoolean(KEY_ORIGINAL_BROWSER_ENABLED, false)
        val previousActiveId = state.getString(KEY_PREVIOUS_ACTIVE_ID, NULL_VALUE)
        val oldAppPid = state.getInt(KEY_PREPARE_APP_PID, -1)
        val oldBrowserPid = state.getInt(KEY_BROWSER_PID, -1)
        val currentBrowserPid = clientStatus()["pid"]?.toIntOrNull() ?: -1
        val session = EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            AutofillDeviceGraph::class.java
        ).databaseSession()
        val password = MASTER_PASSWORD.toCharArray()

        try {
            assertNotEquals("provider process must be newly started", oldAppPid, android.os.Process.myPid())
            assertEquals("old A pending handle must remain in the independent browser client process",
                oldBrowserPid, currentBrowserPid)
            assertTrue("fresh process must reopen the synthetic vault",
                runBlocking { session.open(vaultFile, password) } is KdbxResult.Success)
            ActiveDatabaseIdStore(targetContext).save(vaultPath)

            val oldCandidateBeforeB = probe.awaitActiveNode(
                "old A selector after provider process death", CM_PICKER_TIMEOUT_MS,
                pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
            ) { node ->
                val text = probe.textOf(node)
                text.contains(TITLE_A) || text.contains(USER_A)
            }
            assertNotNull("old A selector must remain visible after provider process death; ${probe.snapshot()}", oldCandidateBeforeB)
            startBackgroundPrepareB()
            val receiverResult = awaitBackgroundPrepareB(CLIENT_TIMEOUT_MS)
            assertTrue(
                "the system must complete the B prepare callback while old A selector is retained; result=$receiverResult",
                receiverResult == "prepared:password=true" || receiverResult == "prepared:password=false"
            )
            if (receiverResult == "prepared:password=false") {
                Log.i(TAG, "Android Credential Manager deferred B's password candidate while A was retained; verify A, then retry B after A resolves")
            }
            val oldCandidateAfterB = probe.awaitActiveNode(
                "old A selector after new B prepare", CM_PICKER_TIMEOUT_MS,
                pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
            ) { node ->
                val text = probe.textOf(node)
                text.contains(TITLE_A) || text.contains(USER_A)
            }
            assertNotNull("A candidate must remain selectable while B is prepared; ${probe.snapshot()}", oldCandidateAfterB)
            assertTrue("selecting old A candidate must be possible", probe.click(oldCandidateAfterB!!))
            val continueOldA = probe.awaitActiveNode(
                "Credential Manager A selection confirmation", 5_000L,
                pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
            ) { node ->
                node.packageName?.toString() == "com.android.credentialmanager" &&
                    probe.textOf(node) in setOf("Continue", "继续")
            }
            assertNotNull("the system must expose its explicit A selection confirmation; ${probe.snapshot()}", continueOldA)
            val selectedContinue = requireNotNull(continueOldA)
            val staleARejected = if (manualCmConfirmationRequested()) {
                awaitManualCredentialManagerContinue("A", runId)
            } else {
                assertTrue("confirming the A candidate must be possible", probe.click(selectedContinue))
                false
            }
            SystemClock.sleep(750L)
            if (!staleARejected) confirmPasswordDeliveryIfRequested()
            val resultA = awaitBrowserStatusPrefix("result:${CredentialBrowserGetClientActivity.ACTION_GET_A}:", 8_000L)
            assertNotNull(
                "system Credential Manager did not complete/cancel the old A request; " +
                    "client=${clientStatus()} windows=${probe.snapshot()}",
                resultA
            )
            assertFalse("old domain A request must never receive domain B's password; $resultA", resultA!!.endsWith(":B"))
            assertTrue("old A may return only its own value or fail closed; $resultA",
                resultA.endsWith(":A") || resultA.contains(":cancelled:"))

            startBrowserCommand(CredentialBrowserGetClientActivity.ACTION_PREPARE_B, ORIGIN_B)
            val preparedB = awaitBrowserStatusPrefix(
                "prepared:${CredentialBrowserGetClientActivity.ACTION_PREPARE_B}:",
                CLIENT_TIMEOUT_MS
            )
            assertTrue("fresh domain B request must have its own candidate", preparedB?.endsWith("password=true") == true)
            startBrowserCommand(CredentialBrowserGetClientActivity.ACTION_GET_B, null)
            val candidateB = probe.awaitActiveNode(
                "Credential Manager current B row", CM_PICKER_TIMEOUT_MS,
                pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
            ) { node ->
                val text = probe.textOf(node)
                text.contains(TITLE_B) || text.contains(USER_B)
            }
            assertNotNull("the current B request must still display its own candidate; ${probe.snapshot()}", candidateB)
            assertTrue("selecting domain B candidate must be possible", probe.click(candidateB!!))
            val continueB = probe.awaitActiveNode(
                "Credential Manager B selection confirmation", 5_000L,
                pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
            ) { node ->
                node.packageName?.toString() == "com.android.credentialmanager" &&
                    probe.textOf(node) in setOf("Continue", "继续")
            }
            continueB?.let {
                if (manualCmConfirmationRequested()) {
                    val staleBRejected = awaitManualCredentialManagerContinue("B", runId)
                    assertFalse("fresh B candidate must not fail the same request-identity check", staleBRejected)
                } else {
                    assertTrue("confirming the B candidate in Credential Manager must be possible", probe.tapNode(it))
                    val stillConfirming = probe.awaitActiveNode(
                        "Credential Manager B confirmation dismissal", 1_500L,
                        pollIntervalMs = CM_PICKER_POLL_INTERVAL_MS
                    ) { node ->
                        node.packageName?.toString() == "com.android.credentialmanager" &&
                            probe.textOf(node) in setOf("Continue", "继续")
                    }
                    if (stillConfirming != null) {
                        val bounds = Rect().also(stillConfirming::getBoundsInScreen)
                        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    }
                }
            }
            confirmPasswordDeliveryIfRequested()
            val resultB = awaitBrowserStatusPrefix("result:${CredentialBrowserGetClientActivity.ACTION_GET_B}:", CLIENT_TIMEOUT_MS)
            assertEquals(
                "the fresh B request must receive B; client=${clientStatus()} windows=${probe.snapshot()}",
                "result:${CredentialBrowserGetClientActivity.ACTION_GET_B}:B",
                resultB
            )
        } finally {
            runCatching { startBrowserCommand(CredentialBrowserGetClientActivity.ACTION_FINISH, null) }
            restore(
                originalCredentialService?.takeUnless { it == NULL_VALUE },
                originalProviderEnabled,
                originalBrowserEnabled,
                previousActiveId?.takeUnless { it == NULL_VALUE },
                vaultFile,
                session
            )
            password.fill('\u0000')
            state.edit().clear().commit()
        }
    }

    private fun confirmPasswordDeliveryIfRequested() {
        val confirmLabel = targetContext.getString(R.string.autofill_confirm_ok)
        val button = probe.awaitActiveNode("CM password manual confirmation", 12_000L) { node ->
            node.packageName?.toString() == targetContext.packageName && probe.textOf(node) == confirmLabel
        } ?: return // A device with a configured strong authenticator may use the system biometric prompt.
        assertTrue("manual password confirmation must be accepted", probe.click(button))
    }

    private fun startBrowserCommand(command: String, origin: String?) {
        val intent = Intent()
            .setClassName(testContext.packageName, CredentialBrowserGetClientActivity::class.java.name)
            .putExtra(CredentialBrowserGetClientActivity.EXTRA_COMMAND, command)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (origin != null) intent.putExtra(CredentialBrowserGetClientActivity.EXTRA_ORIGIN, origin)
        testContext.startActivity(intent)
    }

    private fun startBackgroundPrepareB() {
        val intent = Intent(CredentialBrowserPrepareReceiver.ACTION)
            .setClassName(testContext.packageName, CredentialBrowserPrepareReceiver::class.java.name)
            .putExtra(CredentialBrowserPrepareReceiver.EXTRA_ORIGIN, ORIGIN_B)
        testContext.sendBroadcast(intent)
    }

    private fun awaitBackgroundPrepareB(timeoutMillis: Long): String? {
        val command = "run-as ${testContext.packageName} cat files/${CredentialBrowserPrepareReceiver.STATUS_FILE}"
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val status = shell(command).lineSequence().firstOrNull { it.startsWith("status=") }
                ?.removePrefix("status=")
            if (status?.startsWith("prepared:") == true || status?.startsWith("error:") == true) return status
            SystemClock.sleep(200L)
        }
        return shell(command).lineSequence().firstOrNull { it.startsWith("status=") }?.removePrefix("status=")
    }

    private fun awaitBrowserStatus(expected: String, timeoutMillis: Long): String? =
        awaitBrowserStatusPrefix("$expected", timeoutMillis)?.takeIf { it == expected }

    private fun awaitBrowserStatusPrefix(prefix: String, timeoutMillis: Long): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            val status = clientStatus()["status"]
            if (status?.startsWith(prefix) == true) return status
            SystemClock.sleep(200L)
        }
        return clientStatus()["status"]?.takeIf { it.startsWith(prefix) }
    }

    private fun clientStatus(): Map<String, String> {
        val text = shell("run-as ${testContext.packageName} cat files/${CredentialBrowserGetClientActivity.STATUS_FILE}")
        val status = Regex("P2-620-CM-STATUS:([^\\s]+)").find(text)?.groupValues?.getOrNull(1)
        val pid = Regex("pid=(\\d+)").find(text)?.groupValues?.getOrNull(1)
        return buildMap {
            status?.let { put("status", it) }
            pid?.let { put("pid", it) }
        }
    }


    private suspend fun restore(
        originalCredentialService: String?,
        originalProviderEnabled: Boolean,
        originalBrowserEnabled: Boolean,
        previousActiveId: String?,
        vaultFile: File,
        session: DatabaseSession
    ) {
        runCatching { shell("input keyevent KEYCODE_BACK") }
        runCatching { runBlocking { session.close() } }
        runCatching {
            PasskeyPrivilegedBrowserStore(targetContext).setEnabled(testContext.packageName, originalBrowserEnabled)
        }
        runCatching {
            val store = ExtendedSettingsStore(targetContext)
            store.save(store.load().copy(credentialProviderEnabled = originalProviderEnabled))
        }
        if (originalCredentialService == null) {
            runCatching { shell("settings delete secure credential_service") }
        } else {
            runCatching { shell("settings put secure credential_service $originalCredentialService") }
        }
        ActiveDatabaseIdStore(targetContext).save(previousActiveId)
        shell("run-as ${testContext.packageName} rm -f files/${CredentialBrowserGetClientActivity.STATUS_FILE}")
        testContext.getSharedPreferences(CredentialBrowserGetClientActivity.STATE_PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(vaultFile.absolutePath + ".bak").delete()
        vaultFile.delete()
    }

    private fun shell(command: String): String = probe.shell(command)

    private fun nullableSetting(raw: String): String? = raw.trim().takeUnless { it.isEmpty() || it == "null" }

    private fun passwordEntry(title: String, url: String, username: String, secret: String) = KdbxEntry(
        id = KdbxUuid.random(),
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString(username, isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(secret, isProtected = true)
        )
    )

    private fun statePrefs() = targetContext.getSharedPreferences(TARGET_STATE, Context.MODE_PRIVATE)

    private fun masterPassword() = MASTER_PASSWORD.toCharArray()

    private fun manualCmConfirmationRequested(): Boolean =
        InstrumentationRegistry.getArguments().getString("p2_620ManualCMConfirm") == "true"

    private fun awaitManualCredentialManagerContinue(domain: String, runId: String): Boolean {
        val evidenceRunId = InstrumentationRegistry.getArguments().getString("p2_620_run_id") ?: runId
        Log.i(TAG, "P2_620_MANUAL_CONFIRM_${domain}_READY run_id=$evidenceRunId: tap Continue once for the synthetic $domain entry on Redmi")
        val deadline = SystemClock.uptimeMillis() + MANUAL_CONFIRM_WINDOW_MS
        var nextRejectionCheck = SystemClock.uptimeMillis()
        var continueWasVisible = false
        while (SystemClock.uptimeMillis() < deadline) {
            val snapshot = probe.snapshot()
            val continueVisible = snapshot.contains("package=com.android.credentialmanager") &&
                (snapshot.contains("text=\"Continue\"") || snapshot.contains("text=\"继续\""))
            if (continueVisible) continueWasVisible = true
            if (domain == "A" && SystemClock.uptimeMillis() >= nextRejectionCheck) {
                nextRejectionCheck = SystemClock.uptimeMillis() + REJECTION_LOG_POLL_MS
                val rejectionLog = probe.shell("logcat -d -s PasswordFillActivity:I")
                if (rejectionLog.contains(PASSWORD_FILL_IDENTITY_REJECTION)) {
                    Log.i(TAG, "P2_620_STALE_A_REJECTED run_id=$evidenceRunId: dismiss the still-visible old CM sheet")
                    val resultPrefix = "result:${CredentialBrowserGetClientActivity.ACTION_GET_A}:"
                    repeat(STALE_A_CANCEL_ATTEMPTS) {
                        probe.shell("input keyevent KEYCODE_BACK")
                        val result = awaitBrowserStatusPrefix(resultPrefix, STALE_A_CANCEL_WAIT_MS)
                        if (result != null) {
                            assertFalse("old A must never receive B after identity rejection; $result", result.endsWith(":B"))
                            assertTrue("old A must return A or cancellation after identity rejection; $result",
                                result.endsWith(":A") || result.contains(":cancelled:"))
                            Log.i(TAG, "P2_620_STALE_A_CLOSED run_id=$evidenceRunId outcome=${result.substringAfterLast(':')}")
                            return true
                        }
                    }
                    throw AssertionError(
                        "PasswordFillActivity rejected stale A, but the CM request did not cancel after " +
                            "$STALE_A_CANCEL_ATTEMPTS Back actions; client=${clientStatus()} windows=${probe.snapshot()}"
                    )
                }
            }
            if (continueWasVisible && !continueVisible) return false
            SystemClock.sleep(MANUAL_CONFIRM_POLL_MS)
        }
        throw AssertionError("manual $domain Credential Manager confirmation did not resolve; ${probe.snapshot()}")
    }

    private companion object {
        const val TAG = "P2_620_CM_Test"
        const val MANUAL_CONFIRM_WINDOW_MS = 60_000L
        const val MANUAL_CONFIRM_POLL_MS = 250L
        const val REJECTION_LOG_POLL_MS = 1_000L
        const val CM_PICKER_POLL_INTERVAL_MS = 1_000L
        const val STALE_A_CANCEL_ATTEMPTS = 2
        const val STALE_A_CANCEL_WAIT_MS = 2_000L
        const val PASSWORD_FILL_IDENTITY_REJECTION = "凭据入口身份或最终系统请求不可验证，拒绝回填密码"
        const val TARGET_STATE = "p2_620_cm_cross_domain_state"
        const val KEY_RUN_ID = "run_id"
        const val KEY_VAULT_PATH = "vault_path"
        const val KEY_PREVIOUS_ACTIVE_ID = "previous_active_id"
        const val KEY_ORIGINAL_CREDENTIAL_SERVICE = "original_credential_service"
        const val KEY_ORIGINAL_PROVIDER_ENABLED = "original_provider_enabled"
        const val KEY_ORIGINAL_BROWSER_ENABLED = "original_browser_enabled"
        const val KEY_PREPARE_APP_PID = "prepare_app_pid"
        const val KEY_BROWSER_PID = "browser_pid"
        const val NULL_VALUE = "__null__"
        const val MASTER_PASSWORD = "Synthetic-P2-620-Process-Password"
        const val USER_A = "cm-p2-620-a-user"
        const val USER_B = "cm-p2-620-b-user"
        const val PASSWORD_A = "Synthetic-P2-620-A-Password"
        const val PASSWORD_B = "Synthetic-P2-620-B-Password"
        const val TITLE_A = "CM-P2-620-A-Entry"
        const val TITLE_B = "CM-P2-620-B-Entry"
        const val ORIGIN_A = "https://cm-p2-620-a.localhost:18443"
        const val ORIGIN_B = "https://cm-p2-620-b.localhost:18443"
        // The platform picker can keep the old provider session alive until the selection UI times out.
        const val CLIENT_TIMEOUT_MS = 45_000L
        const val CM_PICKER_TIMEOUT_MS = 20_000L
    }
}
