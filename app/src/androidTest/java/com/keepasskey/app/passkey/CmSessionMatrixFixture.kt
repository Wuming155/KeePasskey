package com.keepasskey.app.passkey

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillNodeIdentity
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
import com.keepasskey.app.security.CallerCertDigests
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
    /** ENG-20261028-06：原生调用方签名的真实存储（与生产注入的是同一 `SharedPreferences` 文件） */
    private val callerTrustStore = CredentialManagerCallerTrustStore(context)
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
            // ENG-20261028-05：通道开关的交付落点全部纳入观测面——密码填充与链式解锁页同样
            // 必须能被「是否被系统真实拉起」「以什么 resultCode 结束」两个读数判据覆盖。
            if (activity is PasskeyAssertionActivity || activity is PasskeyCreateActivity ||
                activity is PasswordSaveActivity || activity is PasswordDraftActivity ||
                activity is PasswordFillActivity || activity is CredentialUnlockActivity) arrivals += activity
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
        dismissSystemSheetAndAwaitGone()
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
        // 清除任何遗留的异签名绑定，恢复默认状态：仅真实签名受信任
        runCatching { boundForeignDigest?.let { callerTrustStore.untrust(clientCallerPackage(), it) } }
        boundForeignDigest = null
        // ENG-20261028-06：原生调用签发要求「包名 + 签名」已登记。本夹具的调用方是**真实安装**的
        // 测试 APK，故此处登记的是它**系统背书**的签名摘要（PackageManager 读出的真实证书），
        // 不是伪造值——语义等价于「用户此前已在保存 / 注册流程里授权过这个应用」。
        // 需要验证「未登记」或「已登记异签名」的用例各自显式改写（见 unbindClientCaller /
        // bindClientCallerToOtherRealCertificate）。
        assertTrue(
            "夹具必须能登记调用方真实签名（否则所有正对照都无意义）",
            bindClientCaller()
        )
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
            // ENG-20261028-05：同库再放一条**口令**条目，供「密码通道」时序用例经真实 CM
            // 取得候选。URL 与断言 origin 同域（浏览器委派 flow），账号/口令与
            // `CredentialBrowserGetClientActivity` 的固定比较值一致，故该客户端回读
            // `result:a:A` / `result:b:B` 即为「真实收到了本库口令」的独立读数。
            isolated.saveEntry(passwordEntry(label))
            assertTrue(isolated.save().isSuccess)
        } finally { isolated.close() }
    }

    /**
     * 同库的**口令**条目（ENG-20261028-05 密码通道时序用例的数据面）。
     *
     * 账号 / 口令取 [CredentialBrowserGetClientActivity] 的固定比较值：该 Java 客户端的常量是私有的，
     * 测试侧不可引用，故此处以同值复制；两处漂移会让正向用例直接红（不会假绿）。
     */
    private fun passwordEntry(label: String): KdbxEntry {
        val suffix = label.lowercase()
        return KdbxEntry(id = PASSWORD_UUID, fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("CM Matrix $label Password", isProtected = false),
            KdbxConstants.Fields.URL to ProtectedString(
                "https://cm-p2-620-$suffix.localhost:18443/login", isProtected = false
            ),
            KdbxConstants.Fields.USER_NAME to ProtectedString("cm-p2-620-$suffix-user", isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(
                if (suffix == "a") BROWSER_PASSWORD_A else BROWSER_PASSWORD_B, isProtected = true
            )
        ))
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
        tapCandidate(label, awaitCandidate(label))
    }

    /**
     * 「候选已生成」的**锚点**：只等系统把候选行摆出来，不点选。
     *
     * ENG-20261028-05 的时序用例必须停在这一刻——用户已经看见候选，此时关闭通道，
     * 再点选才算「候选已生成后关闭」；把它并在 `select` 里就只能测到「关闭后才发起请求」。
     */
    fun awaitCandidate(label: String): AccessibilityNodeInfo {
        val row = awaitDeepest(label)
        assertNotNull("系统必须呈现目标候选；${probe.snapshot()}", row)
        return requireNotNull(row)
    }

    /** 点选一条已就绪的候选行（[awaitCandidate] 的返回值），语义与 [select] 完全一致。 */
    fun tapCandidate(label: String, row: AccessibilityNodeInfo) {
        assertTrue("候选必须可点选；${probe.snapshot()}", probe.click(row))
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

    /** 等待某个生产 Activity 被系统真实拉起（arrivals 由 ActivityLifecycleCallbacks 记录）。 */
    fun awaitArrival(type: Class<out Activity>) {
        await { arrivals.any { type.isInstance(it) } }
    }

    /** 断言在观察窗口内**没有**某个生产 Activity 被拉起（负向判据，避免「未发生」被当作「通过」）。 */
    fun assertNoArrival(type: Class<out Activity>, windowMs: Long = NO_ARRIVAL_WINDOW_MS) {
        SystemClock.sleep(windowMs)
        assertTrue(
            "${type.simpleName} 不得被拉起；实际 arrivals=${arrivals.map { it.javaClass.simpleName }}；${probe.snapshot()}",
            arrivals.none { type.isInstance(it) }
        )
    }

    /** 计数断言前的解锁前置：仅当确实锁定才用主密码解锁**同一**活动库（不重新导入，保住本次写入）。 */
    fun unlockActiveIfLocked() {
        if (!repository.isLocked()) return
        assertTrue(
            "必须能用测试主密码解锁同一活动库以读取条目数",
            runBlocking { repository.unlockActiveDatabase(password).isSuccess }
        )
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

    // ── ENG-20261028-06：原生调用方的签名登记（真实证书，不伪造） ─────────────

    /** 调用方（测试 APK）**系统背书**的签名摘要：与签发侧 `CallingOriginResolver.certDigests` 同口径。 */
    fun clientCallerDigests(): CallerCertDigests {
        val info = clientContext.packageManager.getPackageInfo(
            clientContext.packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signing = requireNotNull(info.signingInfo) { "测试 APK 必须有可读签名" }
        val signers = signing.apkContentsSigners.orEmpty().toList() +
            signing.signingCertificateHistory.orEmpty().toList()
        return CallerCertDigests.of(signers.map { signer ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { "%02X".format(it) }
        })
    }

    /** 测试 APK 包名（真实安装的调用方包名）。 */
    fun clientCallerPackage(): String = clientContext.packageName

    /** 登记调用方签名（写入真实 `CredentialManagerCallerTrustStore`；摘要取真实证书）。 */
    fun bindClientCaller(): Boolean =
        callerTrustStore.trust(clientCallerPackage(), clientCallerDigests().primary)

    /** 撤销调用方签名登记，把夹具恢复到「未登记」状态。 */
    fun unbindClientCaller() {
        clientCallerDigests().values.forEach { digest ->
            callerTrustStore.untrust(clientCallerPackage(), digest)
        }
    }

    /**
     * 把调用方包名登记到**另一份真实证书**上（模拟「同包名、不同签名」：用户曾登记的是
     * 另一个签名者，本次调用方签名与之不符）。
     *
     * 摘要取自**设备上另一个真实安装应用**的系统签名（排除本应用与测试 APK 自身的证书），
     * 绝不使用伪造值；取不到或与调用方证书相同即返回 false，由用例显式判失败。
     */
    fun bindClientCallerToOtherRealCertificate(): Boolean {
        val otherDigest = otherAppCertDigestHex() ?: return false
        assertTrue(
            "「异签名」前提要求存在与调用方证书不同的真实已安装应用签名",
            otherDigest != clientCallerDigests().primary
        )
        // 关键隔离：必须先撤销调用方自身的真实签名绑定，确保存储中「仅」有异签名绑定！
        unbindClientCaller()
        boundForeignDigest?.let { callerTrustStore.untrust(clientCallerPackage(), it) }
        boundForeignDigest = otherDigest
        return callerTrustStore.trust(clientCallerPackage(), otherDigest)
    }

    /** 本次用例写入的「异签名」摘要（`close` 用它精确撤销，不受枚举顺序漂移影响） */
    private var boundForeignDigest: String? = null

    /**
     * 设备上**另一份真实证书**的摘要（十六进制大写），用于「同包名异签名」对照。
     *
     * 取值面：已安装且对被测应用可见的包（MAIN/LAUNCHER 查询使带桌面入口的应用可见），
     * 逐个读 `PackageManager` 的签名并排除本应用与测试 APK 的证书。
     * 这仍是**真实系统签名**，不是构造值——用例要证明的正是「存储里的旧签名 ≠ 本次系统签名」。
     */
    fun otherAppCertDigestHex(): String? {
        val own = buildSet {
            add(context.packageName)
            add(clientContext.packageName)
        }
        val ownDigests = certDigestsOf(own)
        val res = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).asSequence()
            .map { it.activityInfo.packageName }
            .filterNot { it in own }
            .distinct()
            .mapNotNull { pkg -> certDigestHexOf(pkg) }
            .firstOrNull { it !in ownDigests }
        return res
    }

    private fun certDigestsOf(packages: Set<String>): Set<String> =
        packages.mapNotNull { certDigestHexOf(it) }.toSet()

    private fun certDigestHexOf(packageName: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        java.security.MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /** 该包名当前是否已登记任一签名（真实存储读数）。 */
    fun callerBound(): Boolean = callerTrustStore.hasAnyBindingFor(clientCallerPackage())

    /** 该包名是否已登记**本次调用方真实签名**（区分「已绑定另一签名」与「已绑定本签名」）。 */
    fun callerBoundToRealSignature(): Boolean =
        callerTrustStore.isTrusted(clientCallerPackage(), clientCallerDigests())

    // ── 拒绝页驱动面（断言「呈现原因」而不是「静默失败」） ─────────────────

    /** 等待拒绝页出现（按应用自有的标题/消息文本定位；页面在受保护窗口内）。 */
    fun awaitRejectionPage(titleRes: Int = R.string.cred_reject_get_title) {
        val label = context.getString(titleRes)
        val node = probe.awaitActiveNode("拒绝页标题", TIMEOUT) {
            it.packageName?.toString() == context.packageName && probe.textOf(it) == label
        }
        assertNotNull("拒绝页必须呈现给用户（不得静默失败）；${probe.snapshot()}", node)
    }

    /**
     * 等待拒绝页出现，同时推进系统「Continue」确认弹窗。
     *
     * 拒绝发生在用户验证**之前**，系统可能仍停在确认弹窗上；只等拒绝页而不点确认会整轮超时。
     */
    fun awaitRejectionPagePumpingConfirm(titleRes: Int = R.string.cred_reject_get_title) {
        val label = context.getString(titleRes)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            confirmSystemIfPresent()
            val node = probe.awaitActiveNode("拒绝页标题", POLL) {
                it.packageName?.toString() == context.packageName && probe.textOf(it) == label
            }
            if (node != null) return
        }
        fail("拒绝页始终未出现（不得静默失败）；${probe.snapshot()}")
    }

    /** 断言拒绝页**没有**某个按钮文本（负向判据：不得给出不该给的就地动作）。 */
    fun assertNoAppText(res: Int, windowMs: Long = NO_ARRIVAL_WINDOW_MS) {
        val label = context.getString(res)
        val deadline = SystemClock.uptimeMillis() + windowMs
        while (SystemClock.uptimeMillis() < deadline) {
            val present = runCatching {
                probe.collect().any {
                    it.packageName?.toString() == context.packageName && probe.textOf(it) == label
                }
            }.getOrDefault(false)
            assertTrue("拒绝页不得出现「$label」；${probe.snapshot()}", !present)
            SystemClock.sleep(POLL)
        }
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

    // ── ENG-20261028-05：通道开关「挂起期间关闭」时序用例的驱动面 ──────────────────

    /**
     * 按**本次**值改写通道开关（未指定的项保持当前值）。
     *
     * 开关是生产 `ExtendedSettingsStore` 的真实持久化项：写入后，随后被系统拉起（或已在
     * 挂起中继续）的生产 Activity / 服务读到的是新值——这正是「用户在设置页关闭通道」
     * 与「请求已挂起」两个事实同时成立时的等价形态。
     */
    fun setChannelSwitches(
        credentialProviderEnabled: Boolean? = null,
        passkeySupportEnabled: Boolean? = null,
        autofillServiceEnabled: Boolean? = null
    ) {
        val current = settingsStore.load()
        settingsStore.save(
            current.copy(
                credentialProviderEnabled = credentialProviderEnabled ?: current.credentialProviderEnabled,
                passkeySupportEnabled = passkeySupportEnabled ?: current.passkeySupportEnabled,
                autofillServiceEnabled = autofillServiceEnabled ?: current.autofillServiceEnabled
            )
        )
    }

    /** 锁库（保留活动库选择，供「解锁页挂起」类时序用例构造锁定前置）。 */
    fun lock() = runBlocking { repository.lockDatabase() }

    /** 活动库当前条目数：口令保存的正/负对照只需计数，不读任何受保护字段。 */
    fun entryCount(): Int = session.databaseFlow.value!!.rootGroup.allEntries().size

    /** 活动库内是否存在该用户名的条目（`displayUserName` 为展示面降级读口，不因并发擦除而抛）。 */
    fun hasUserName(userName: String): Boolean =
        session.databaseFlow.value!!.rootGroup.allEntries().any { it.displayUserName() == userName }

    /**
     * 系统 CM 面板是否仍在活动窗口（关闭判据）。
     *
     * 「通道关闭⇒不交付」在本设备上的真实系统行为可能是：面板把 `Unlock KeePasskey to fill` +
     * `No sign-in info` 留在前台等用户操作，而**不回调客户端**。此时必须由用户显式取消，
     * 不能把「等待超时」当成通过——因此用例统一走 [dismissSystemSheet] + 本判据收尾。
     */
    fun systemSheetVisible(): Boolean = runCatching {
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == CM_PACKAGE
    }.getOrDefault(false)

    /** 显式取消系统 CM 面板（点「Close sheet」优先，取不到则 BACK）。 */
    fun dismissSystemSheet(): Boolean {
        val close = probe.awaitActiveNode("CM 关闭面板", 2_000L) { node ->
            node.packageName?.toString() == CM_PACKAGE && probe.textOf(node).contains("Close sheet")
        }
        if (close != null && probe.click(close)) return true
        probe.shell("input keyevent KEYCODE_BACK")
        return true
    }

    /**
     * 关闭系统 CM 面板并等它真的消失。
     *
     * 面板由系统持有：用例结束时若仍停在前台，**下一个用例的请求会排在旧面板后面**
     * （实测表现为新请求永远等不到面板、客户端状态停在 `requesting:*`）。
     * 故 `prepare` / `close` 与所有「生产页已取消」的分支都必须显式收口。
     */
    fun dismissSystemSheetAndAwaitGone(
        timeoutMs: Long = SHEET_DISMISS_TIMEOUT_MS,
        appearWaitMs: Long = SHEET_APPEAR_WAIT_MS
    ) {
        // 生产页刚结束时 CM 可能还没重新拿到焦点：先给一点时间让面板回到前台再判断「不在」，
        // 避免把「尚未回来」误判成「已经关掉」而漏掉后续对它的关闭操作。
        val appearDeadline = SystemClock.uptimeMillis() + appearWaitMs
        while (SystemClock.uptimeMillis() < appearDeadline && !systemSheetVisible()) {
            SystemClock.sleep(POLL)
        }
        if (!systemSheetVisible()) return
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!systemSheetVisible()) return
            dismissSystemSheet()
            SystemClock.sleep(POLL * 3)
        }
        assertFalse("系统 CM 面板必须能由用户显式关闭；${probe.snapshot()}", systemSheetVisible())
    }

    /** 让测试 APK 成为**特权浏览器**（浏览器委派 origin 才会被采信）；收尾由 [close] 还原。 */
    fun enableTestBrowser(): Boolean = browserStore.setEnabled(clientContext.packageName, true)

    /**
     * 页面挂起期间推进系统的「Continue」确认，同时等待生产 Activity 以 [code] 结束。
     *
     * 通道关闭后生产 Activity 会**立刻**收尾，而系统可能仍停在确认弹窗上；只等
     * [awaitResult] 而不点确认会整轮超时，故两者在同一循环里推进。
     */
    fun awaitResultPumpingSystemConfirm(type: Class<out Activity>, code: Int) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            confirmSystemIfPresent()
            if (results.any { it.first == type.simpleName && it.second == code }) return
            SystemClock.sleep(POLL)
        }
        fail("未观察到 ${type.simpleName} 以 resultCode=$code 结束；results=$results；${probe.snapshot()}")
    }

    /**
     * 在**真实解锁页**（`UnlockScreen`）输入主密码并点「解锁」。
     *
     * 与 `AutofillAuthChainDeviceTest` 同一手法：按「包名 + 可编辑 + 密码语义」定位主密码框，
     * `ACTION_SET_TEXT` 不可用时退化为触摸聚焦 + `input text`（Compose 文本框两者均支持）。
     */
    fun unlockThroughUi() {
        val field = probe.awaitActiveNode("解锁页主密码框", TIMEOUT) { node ->
            AutofillNodeIdentity.appNode(node, context.packageName) && node.isEditable && node.isPassword
        }
        assertNotNull("解锁页必须显示主密码输入框；${probe.snapshot()}", field)
        val master = String(password)
        if (!probe.setText(requireNotNull(field), master)) {
            probe.tapNode(requireNotNull(field))
            SystemClock.sleep(SELECT_SETTLE)
            probe.shell("input text $master")
        }
        SystemClock.sleep(SELECT_SETTLE)
        val buttonLabel = context.getString(R.string.unlock_btn_unlock)
        val button = probe.awaitActiveNode("解锁按钮", TIMEOUT) { node ->
            AutofillNodeIdentity.appButton(node, context.packageName, buttonLabel)
        }
        assertNotNull("解锁页必须显示解锁按钮；${probe.snapshot()}", button)
        assertTrue("解锁按钮必须可点", probe.click(requireNotNull(button)))
    }

    /** 启动浏览器委派客户端的一条命令（[CredentialBrowserGetClientActivity] 的 ACTION_*）。 */
    fun browserClient(action: String, origin: String? = null) {
        probe.shell(
            "run-as ${clientContext.packageName} rm -f files/${CredentialBrowserGetClientActivity.STATUS_FILE}"
        )
        clientContext.startActivity(
            Intent().setClassName(
                clientContext.packageName,
                CredentialBrowserGetClientActivity::class.java.name
            ).apply {
                putExtra(CredentialBrowserGetClientActivity.EXTRA_COMMAND, action)
                origin?.let { putExtra(CredentialBrowserGetClientActivity.EXTRA_ORIGIN, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    /** 浏览器委派客户端最近一次写入的状态（`prepared:prepare-a:password=true` / `result:get-a:A` 等）。 */
    fun browserStatus(): String =
        probe.shell(
            "run-as ${clientContext.packageName} cat files/${CredentialBrowserGetClientActivity.STATUS_FILE}"
        ).let { text -> Regex("P2-620-CM-STATUS:([^\\s]+)").find(text)?.groupValues?.get(1).orEmpty() }

    /**
     * 浏览器委派客户端 prepare 回执的前缀。
     *
     * 该 Java 客户端的 `slot` 取**命令常量**本身（`prepare-a`），不是短标签 `a`——
     * 首轮按 `prepared:a:` 等待导致整轮超时，实测回执为 `prepared:prepare-a:password=true`。
     */
    fun browserPreparePrefix(): String =
        "prepared:${CredentialBrowserGetClientActivity.ACTION_PREPARE_A}:"

    /** 浏览器委派客户端 get 回执的前缀（同上：slot 为 `get-a`，回执形如 `result:get-a:A`）。 */
    fun browserResultPrefix(): String =
        "result:${CredentialBrowserGetClientActivity.ACTION_GET_A}:"

    fun awaitBrowserStatus(prefix: String): String {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        while (SystemClock.uptimeMillis() < deadline) {
            val status = browserStatus()
            if (status.startsWith(prefix)) return status
            SystemClock.sleep(POLL)
        }
        val last = browserStatus()
        assertTrue("浏览器委派客户端状态未达 $prefix（实际=$last）；${probe.snapshot()}", last.startsWith(prefix))
        return last
    }

    override fun close() {
        runCatching { client("finish") }
        runCatching { browserClient(CredentialBrowserGetClientActivity.ACTION_FINISH) }
        instrumentation.runOnMainSync { arrivals.filterNot { it.isDestroyed }.forEach { it.finish() } }
        // 系统 CM 面板由系统持有：本用例结束不关，会串到下一个用例（见 dismissSystemSheetAndAwaitGone）
        runCatching { dismissSystemSheetAndAwaitGone() }
        application.unregisterActivityLifecycleCallbacks(callbacks)
        runBlocking { repository.lockDatabase() }
        settingsStore.save(originalSettings)
        browserStore.setEnabled(clientContext.packageName, originalBrowser)
        val setting = if (originalProvider.isBlank() || originalProvider == "null") "delete secure credential_service"
            else "put secure credential_service $originalProvider"
        probe.shell("settings $setting")
        com.keepasskey.app.data.repository.ActiveDatabaseIdStore(context).save(originalActive)
        // ENG-20261028-06：撤掉本夹具登记的调用方签名（含「异签名」对照那份），
        // 不把信任状态留给下一个用例 / 下次运行。
        runCatching { unbindClientCaller() }
        runCatching { boundForeignDigest?.let { callerTrustStore.untrust(clientCallerPackage(), it) } }
        boundForeignDigest = null
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

        /** 负向判据的观察窗口：足够让「被系统拉起的生产页」完成 onCreate 收尾。 */
        const val NO_ARRIVAL_WINDOW_MS = 3_000L

        /** 关闭系统 CM 面板并等其消失的上限（「Close sheet」与 BACK 多轮重试）。 */
        const val SHEET_DISMISS_TIMEOUT_MS = 10_000L

        /** 判定「面板不在」前的等待：刚结束的生产页可能还没把焦点交回 CM。 */
        const val SHEET_APPEAR_WAIT_MS = 1_500L
        val UUID: KdbxUuid = KdbxUuid.fromHexString("64164200000000000000000000000001")

        /** 同库口令条目的 UUID（与公钥条目的 [UUID] 区分，互不干扰取数口径）。 */
        val PASSWORD_UUID: KdbxUuid = KdbxUuid.fromHexString("64164200000000000000000000000002")

        /**
         * 浏览器委派 origin / 账号 / 口令（ENG-20261028-05 密码通道用例）。
         *
         * `CredentialBrowserGetClientActivity` 只接受 `https://cm-p2-620-` 前缀的 origin，
         * 并按**写死的** A/B 账号与口令把回执归类为 `result:a:A` / `result:b:B`。该客户端的
         * 常量是私有的（测试 APK 零 kotlin 依赖），故此处以同值复制；漂移会让正向用例直接红，
         * 不会产生假绿。
         */
        const val BROWSER_ORIGIN_A = "https://cm-p2-620-a.localhost:18443"
        const val BROWSER_USER_A = "cm-p2-620-a-user"
        const val BROWSER_PASSWORD_A = "Synthetic-P2-620-A-Password"
        const val BROWSER_PASSWORD_B = "Synthetic-P2-620-B-Password"
    }
}
