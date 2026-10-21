package com.keepasskey.app.passkey

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.autofill.DeviceAutofillProbe
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.data.repository.PasskeyPrivilegedBrowserStore
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.passkey.PasskeyCryptoEngine
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*

internal class CmSessionMatrixFixture : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val clientContext = instrumentation.context
    val probe = DeviceAutofillProbe()
    val graph = EntryPointAccessors.fromApplication(context.applicationContext, AutofillDeviceGraph::class.java)
    val session = graph.databaseSession()
    val repository: VaultRepository = graph.vaultRepository()
    val directory = File(context.filesDir, "cm-matrix-${System.nanoTime()}").apply { mkdirs() }
    val a = File(directory, "A.kdbx")
    val b = File(directory, "B.kdbx")
    val password = "Synthetic-CM-Matrix-2026".toCharArray()
    val publicKeys = mutableMapOf<String, String>()
    val credentialIds = mutableMapOf<String, String>()
    val arrivals = CopyOnWriteArrayList<Activity>()
    val results = CopyOnWriteArrayList<Pair<String, Int>>()
    val browserStore = PasskeyPrivilegedBrowserStore(context)
    private val settingsStore = ExtendedSettingsStore(context)
    private val originalSettings = settingsStore.load()
    private val originalProvider = probe.shell("settings get secure credential_service").trim()
    private val originalActive = com.keepasskey.app.data.repository.ActiveDatabaseIdStore(context).load()
    private val originalBrowser = browserStore.installedBrowsers().any {
        it.packageName == clientContext.packageName && it.enabled
    }
    private val application = context.applicationContext as Application
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is PasskeyAssertionActivity || activity is PasskeyCreateActivity ||
                activity is PasswordSaveActivity || activity is PasswordDraftActivity) arrivals += activity
        }
        override fun onActivityDestroyed(activity: Activity) {
            if (activity in arrivals) {
                val code = Activity::class.java.getDeclaredField("mResultCode").apply { isAccessible = true }.getInt(activity)
                results += activity.javaClass.simpleName to code
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    fun prepare() = runBlocking {
        probe.requireAutomation()
        probe.prepare()
        settingsStore.save(originalSettings.copy(credentialProviderEnabled = true, passkeySupportEnabled = true,
            skipDalVerification = true, autofillOfferCreateEntry = true))
        val provider = "${context.packageName}/${KeePasskeyCredentialProviderService::class.java.name}"
        probe.shell("settings put secure credential_service $provider")
        assertEquals(provider, probe.shell("settings get secure credential_service").trim())
        seed(a, "A", 17)
        seed(b, "B", 71)
        application.registerActivityLifecycleCallbacks(callbacks)
        activate(a)
    }

    private suspend fun seed(file: File, label: String, count: Int) {
        val isolated = DatabaseSession()
        try {
            assertTrue(isolated.create(file, label, password, useArgon2 = false).isSuccess)
            val key = PasskeyCryptoEngine.generateEs256KeyPair(RP, "cm-matrix-$label", "CM Matrix $label")
                .copy(signCount = count)
            publicKeys[label] = key.publicKeyBase64
            credentialIds[label] = key.credentialId
            isolated.saveEntry(KdbxEntry(id = UUID, fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("CM Matrix $label", isProtected = false),
                KdbxConstants.Fields.URL to ProtectedString("android://${clientContext.packageName}", isProtected = false)
            ), customFields = key.toCustomFields()))
            assertTrue(isolated.save().isSuccess)
        } finally { isolated.close() }
    }

    fun activate(file: File) = runBlocking {
        repository.lockDatabase()
        assertTrue(repository.importExternalDatabase(file.name, file.absolutePath).isSuccess)
        repository.selectDatabase(file.absolutePath)
        assertTrue(repository.unlockActiveDatabase(password).isSuccess)
        assertEquals(file.absolutePath, session.captureCredentialRequest().ticket?.pathIdentifier)
    }

    fun request(rp: String? = RP, allowed: String? = null, uv: String = "preferred"): String = JSONObject().apply {
        put("challenge", CHALLENGE)
        if (rp != null) put("rpId", rp)
        put("userVerification", uv)
        if (allowed != null) put("allowCredentials", org.json.JSONArray().put(JSONObject()
            .put("type", "public-key").put("id", allowed)))
    }.toString()

    fun client(mode: String, request: String? = null, origin: String? = null, clientData: String? = null) {
        probe.shell("run-as ${clientContext.packageName} rm -f files/${CredentialSaveClientActivity.STATUS_FILE} " +
            "files/${CredentialSaveClientActivity.RESPONSE_FILE}")
        clientContext.startActivity(Intent().setClassName(clientContext.packageName,
            CredentialSaveClientActivity::class.java.name).apply {
            putExtra(CredentialSaveClientActivity.EXTRA_MODE, mode)
            request?.let { putExtra(CredentialSaveClientActivity.EXTRA_REQUEST_JSON, it) }
            origin?.let { putExtra(CredentialSaveClientActivity.EXTRA_ORIGIN, it) }
            clientData?.let { putExtra(CredentialSaveClientActivity.EXTRA_CLIENT_DATA_JSON, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    fun status(): String = probe.shell("run-as ${clientContext.packageName} cat files/${CredentialSaveClientActivity.STATUS_FILE}")
        .lineSequence().firstOrNull().orEmpty()

    fun awaitStatus(prefix: String): String {
        await { status().startsWith(prefix) }
        return status()
    }

    fun select(label: String) {
        val row = probe.awaitActiveNode("真实 CM 候选 $label", TIMEOUT) {
            it.packageName?.toString() != context.packageName && probe.textOf(it).contains(label)
        }
        assertNotNull("系统必须呈现目标候选；${probe.snapshot()}", row)
        assertTrue(probe.click(requireNotNull(row)))
    }

    fun confirmSystemIfPresent() {
        val confirm = probe.awaitActiveNode("CM Continue", 1_500L) {
            it.packageName?.toString() == "com.android.credentialmanager" && probe.textOf(it) in setOf("Continue", "继续")
        }
        confirm?.let { assertTrue(probe.click(it)) }
    }

    fun appButton(resource: Int) {
        val label = context.getString(resource)
        val node = probe.awaitActiveNode(label, TIMEOUT) {
            it.packageName?.toString() == context.packageName && probe.textOf(it) == label && it.isEnabled
        }
        assertNotNull("生产窗口必须显示 $label；${probe.snapshot()}", node)
        assertTrue(probe.click(requireNotNull(node)))
    }

    fun awaitConfirmation(type: Class<out Activity>) {
        confirmSystemIfPresent()
        val label = context.getString(R.string.passkey_confirm_ok)
        assertNotNull(probe.awaitActiveNode("生产验证确认页", TIMEOUT) {
            it.packageName?.toString() == context.packageName && probe.textOf(it) == label
        })
        assertTrue("必须真实进入 ${type.simpleName}", arrivals.any { type.isInstance(it) })
    }

    fun awaitResult(type: Class<out Activity>, code: Int) {
        await { results.any { it.first == type.simpleName && it.second == code } }
    }

    fun exportAssertion(label: String, origin: String, count: Int, clientData: String? = null) {
        val response = probe.shell("run-as ${clientContext.packageName} cat files/${CredentialSaveClientActivity.RESPONSE_FILE}")
        JSONObject(response)
        val evidence = File(context.filesDir, "cm-matrix-evidence").apply { mkdirs() }
        File(evidence, "$label-response.json").writeText(response)
        val other = if (label.startsWith("A")) "B" else "A"
        val key = label.take(1)
        val entry = session.databaseFlow.value!!.rootGroup.allEntries().single { it.id == UUID }
        val data = requireNotNull(PasskeyData.fromCustomFields(entry.customFields))
        assertEquals(count, data.signCount)
        val manifest = JSONObject().put("credentialId", credentialIds.getValue(key)).put("challenge", CHALLENGE)
            .put("origin", origin).put("rpId", RP).put("publicKeyBase64", publicKeys.getValue(key))
            .put("forbiddenPublicKeyBase64", publicKeys.getValue(other)).put("signCount", count).put("uv", false)
            .put("userHandle", data.userHandle)
        clientData?.let { manifest.put("clientDataJSON", it) }
        File(evidence, "$label-expected.json").writeText(manifest.toString())
        println("CM_MATRIX_EVIDENCE=$label")
    }

    fun nativeOrigin(): String {
        val info = clientContext.packageManager.getPackageInfo(clientContext.packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = requireNotNull(info.signingInfo).apkContentsSigners.first()
        return "android:apk-key-hash:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()))
    }

    fun count(file: File): Int = runBlocking {
        val isolated = DatabaseSession()
        try {
            assertTrue(isolated.open(file, password).isSuccess)
            PasskeyData.readSignCount(isolated.databaseFlow.value!!.rootGroup.allEntries().single { it.id == UUID }.customFields)
        } finally { isolated.close() }
    }

    fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL)
        assertTrue("CM 矩阵等待超时；status=${status()}；${probe.snapshot()}", predicate())
    }

    override fun close() {
        runCatching { client("finish") }
        instrumentation.runOnMainSync { arrivals.filterNot { it.isDestroyed }.forEach { it.finish() } }
        application.unregisterActivityLifecycleCallbacks(callbacks)
        runBlocking { repository.lockDatabase() }
        settingsStore.save(originalSettings)
        browserStore.setEnabled(clientContext.packageName, originalBrowser)
        val setting = if (originalProvider.isBlank() || originalProvider == "null") "delete secure credential_service"
            else "put secure credential_service $originalProvider"
        probe.shell("settings $setting")
        com.keepasskey.app.data.repository.ActiveDatabaseIdStore(context).save(originalActive)
        password.fill('\u0000')
        directory.deleteRecursively()
    }

    companion object {
        const val RP = "example.com"
        const val CHALLENGE = "Y20tbWF0cml4LWNoYWxsZW5nZS0yMDI2"
        const val TIMEOUT = 20_000L
        const val POLL = 100L
        val UUID: KdbxUuid = KdbxUuid.fromHexString("64164200000000000000000000000001")
    }
}
