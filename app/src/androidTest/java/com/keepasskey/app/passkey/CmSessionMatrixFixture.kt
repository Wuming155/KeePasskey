package com.keepasskey.app.passkey

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
        // 跨用例复用同一系统 CM：上一个用例遗留的客户端 Activity / 选择器窗口必须先退场，
        // 否则新请求会排在旧窗口后面（连跑时表现为「status=requesting」永不推进、
        // 或「系统必须呈现目标候选」直接超时，而单跑每个用例都通过）。
        //
        // 不能用 `am force-stop`：测试客户端 Activity 与测试包同进程，停掉它等于杀掉
        // instrumentation 自身。改为结束该 Activity 并等系统选择器窗口退场。
        runCatching { client("finish") }
        awaitSystemSheetGone()
        SystemClock.sleep(PREPARE_SETTLE)
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

    /** 等系统 CM 选择器窗口退场（跨用例时不留下悬挂的 sheet）。 */
    private fun awaitSystemSheetGone() {
        val deadline = SystemClock.uptimeMillis() + PREPARE_SETTLE * 4
        while (SystemClock.uptimeMillis() < deadline) {
            val root = runCatching {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.rootInActiveWindow
            }.getOrNull()
            val showing = root != null && root.packageName?.toString() == CM_PACKAGE
            if (!showing) return
            instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
            SystemClock.sleep(POLL)
        }
    }

    private suspend fun seed(file: File, label: String, count: Int) {
        val isolated = DatabaseSession()
        try {
            assertTrue(isolated.create(file, label, password, useArgon2 = false).isSuccess)
            // `userHandle` 按 WebAuthn 契约存 **base64url 字节串**（生产创建路径取 RP 下发的
            // `user.id`，同样是 base64url 文本）。此前直接塞明文标签，断言输出与独立验签器
            // 的解码契约不符（报 `Only base64 data is allowed`）。
            val key = PasskeyCryptoEngine.generateEs256KeyPair(
                relyingPartyId = RP,
                userName = "cm-matrix-$label",
                userHandle = base64Url("CM Matrix $label".toByteArray()),
                userDisplayName = "CM Matrix $label"
            ).copy(signCount = count)
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

    /**
     * 在真实系统选择器里点选候选。
     *
     * 系统呈现的是**用户名**（`userName`），不是条目标题——曾按标题匹配导致
     * 「系统必须呈现目标候选」整轮超时，而候选其实已经显示。
     *
     * 候选行的文字分散在多个子节点里（`textOf` 只取单节点），故按**子树文本**定位；
     * 且不能直接用 `awaitActiveNode`——它要求谓词**恰好命中一个**节点，
     * 而子树匹配会同时命中候选行及其多层祖先（实测永远等不到）。
     */
    fun select(label: String) {
        val row = awaitDeepest(label)
        assertNotNull("系统必须呈现目标候选；${probe.snapshot()}", row)
        assertTrue("候选必须可点选；${probe.snapshot()}", probe.click(requireNotNull(row)))
        SystemClock.sleep(SELECT_SETTLE)
        // 点选后系统可能把候选折叠为「已选中」态：再点一次可点击容器推进到下一步
        awaitDeepest(label)?.let { probe.click(it) }
    }

    /** 轮询活动窗口，返回子树含 [label] 的**最深**节点（最贴近候选行的那个）。 */
    private fun awaitDeepest(label: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            val root = runCatching {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    .uiAutomation.rootInActiveWindow
            }.getOrNull()
            val hits = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null) return
                if (node.packageName?.toString() == CM_PACKAGE && subtreeText(node).contains(label)) {
                    hits += node to depth
                }
                for (index in 0 until node.childCount) walk(node.getChild(index), depth + 1)
            }
            walk(root, 0)
            if (hits.isNotEmpty()) return hits.maxBy { it.second }.first
            SystemClock.sleep(POLL)
        }
        return null
    }

    /** 节点子树内全部文本拼接（用于跨子节点匹配候选行内容）。 */
    private fun subtreeText(node: AccessibilityNodeInfo?): String = buildString {
        fun walk(current: AccessibilityNodeInfo?, depth: Int) {
            if (current == null || depth > 6) return
            current.text?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
            current.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { append(it).append(' ') }
            for (index in 0 until current.childCount) walk(current.getChild(index), depth + 1)
        }
        walk(node, 0)
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
        val label = context.getString(R.string.passkey_confirm_ok)
        // 系统在点选后可能先弹一次「Continue」确认，也可能直接进生产窗口；
        // 两种时序都要接住，且要持续处理（曾只等 1.5s，确认弹窗晚到即整轮超时）。
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            confirmSystemIfPresent()
            val node = probe.awaitActiveNode("生产验证确认页", POLL) {
                it.packageName?.toString() == context.packageName && probe.textOf(it) == label
            }
            if (node != null) {
                assertTrue("必须真实进入 ${type.simpleName}；${probe.snapshot()}", arrivals.any { type.isInstance(it) })
                return
            }
        }
        fail("生产验证确认页始终未出现；${probe.snapshot()}")
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
            // 存储值本就是 base64url 字节串（见 `seed`），直接透传即可
            .put("userHandle", data.userHandle)
        clientData?.let { manifest.put("clientDataJSON", it) }
        File(evidence, "$label-expected.json").writeText(manifest.toString())
        println("CM_MATRIX_EVIDENCE=$label")
    }

    private fun base64Url(value: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)

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
        const val SELECT_SETTLE = 500L
        const val CM_PACKAGE = "com.android.credentialmanager"
        const val PREPARE_SETTLE = 1_500L
        val UUID: KdbxUuid = KdbxUuid.fromHexString("64164200000000000000000000000001")
    }
}
