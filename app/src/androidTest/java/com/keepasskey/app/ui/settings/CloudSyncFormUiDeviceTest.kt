package com.keepasskey.app.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.ui.CloudSyncDeviceHostActivity
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.session.DatabaseSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-651/652`：**真实 UI + 真实 HTTPS 接收方**验收。
 *
 * ## 为什么必须设备侧
 *
 * 两项问题的关键结论都不是「代码里有没有守卫」，而是**线上有没有包**、以及**用户不重输秘密能不能重试**：
 * - P2-651：失败后输入框仍显示内容，但提交数组已被擦零 ⇒ 修正端点再次提交会被全零拒绝。判据是
 *   「同一份草稿（不重输）能保存成功」，只能经 `SecurePasswordField` 的真实显示态验证；
 * - P2-652：保存失败仍去测连/同步旧目标。判据是「接收方一个请求都没收到」，必须有一个真实
 *   TLS 服务端来计数。
 *
 * ## 装置
 *
 * - 宿主 `CloudSyncDeviceHostActivity` 挂的是**生产** `CloudSyncScreen`（表单本地状态、
 *   `SecurePasswordField`、离页 `DisposableEffect`、两处入口的 `persistThenContinue` 守卫）；
 * - 控制器 / 协调器 / 凭据库都是生产实现，**真实 AndroidKeyStore 封印**（不注入测试加解密钩子）；
 * - 接收方由 `tools/device/run_p2_651_652_ui.py` 启动：真实 TLS、Basic 认证、按 method/path 记账，
 *   并带 `/__control/` 门闩端点（hold/release/reset/state）供「测连在途」场景编排。
 *
 * ## 边界
 *
 * 只证明「设备 → 本机 TLS 接收方」这一跳，不证明商业 WebDAV 服务端行为；「封印失败」与
 * 「全零串拒绝」两类分支由 JVM `CloudSyncSaveRetryContractTest` 覆盖（设备上无法自然制造
 * Keystore 失效，也不应为此破坏真实密钥）。本用例不建库，故同步阶段必然以「库未解锁」收尾——
 * 这正是要观察的：**同步没跑起来也不能把 B 标成已验证**。
 */
@RunWith(AndroidJUnit4::class)
class CloudSyncFormUiDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val automation by lazy { instrumentation.uiAutomation }
    private val port: Int by lazy {
        requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_PORT)?.toIntOrNull()) {
            "缺少 $ARG_PORT（由 tools/device/run_p2_651_652_ui.py 提供）"
        }
    }
    private val baseUrl: String by lazy { "https://127.0.0.1:$port" }
    private val controlClient: OkHttpClient by lazy {
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: DatabaseSession
    private lateinit var credentials: SyncCredentialsStore
    private lateinit var setup: CloudSyncDeviceHostActivity.Setup
    private var scenario: ActivityScenario<CloudSyncDeviceHostActivity>? = null

    @Before
    fun setUp() {
        assumeTrue("本用例只由 run_p2_651_652_ui.py 驱动（缺少端口参数）", hasRunnerArgument())
        enableAccessibilityRetrieval()
        session = DatabaseSession()
        credentials = SyncCredentialsStore(
            context = context,
            keystoreManager = KeystoreManager(context, DebugLogBuffer())
        )
        setup = CloudSyncDeviceHostActivity.buildSetup(
            applicationContext = context.applicationContext,
            credentials = credentials,
            session = session,
            scope = scope,
            strings = StringsProvider { id, args -> context.getString(id, *args) }
        )
        CloudSyncDeviceHostActivity.suppliedSetup = setup
        control("/__control/reset", post = true)
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        CloudSyncDeviceHostActivity.suppliedSetup = null
        scope.cancel()
        runBlocking { session.close() }
    }

    @Test
    fun httpEndpointRejectedSendsNoRequest() {
        launch()
        fillWebDavForm(url = "http://127.0.0.1:$port/sync", path = "/p2-651-a.kdbx")

        clickText(context.getString(R.string.sync_save_and_sync_btn))

        waitUntil("保存被拒并上浮 HTTPS 要求") {
            setup.controller.state.value.syncFeedbackMessage?.resId == R.string.sync_error_https_required
        }
        // 给「万一有请求」留出到达时间，避免用「查询太快」冒充零请求
        SystemClock.sleep(SETTLE_MS)
        val requests = receiverRequests()
        assertTrue("保存失败不得向接收方发出任何请求，实际: $requests", requests.isEmpty())
        assertFalse("保存失败不得置位已验证", setup.controller.state.value.isConnectionVerified)
        assertFalse("保存失败不得停在同步中", setup.controller.state.value.isSyncing)

        assertEquals(
            "失败后 URL 输入框必须仍显示用户输入",
            "http://127.0.0.1:$port/sync",
            textOfField(Field.URL)
        )
        assertFalse(
            "失败后密码输入框必须仍显示已输入内容（否则用户被迫重输）；" +
                "注意密码字段的无障碍文本是掩码，故只断言非空",
            textOfField(Field.PASSWORD).isEmpty()
        )
    }

    @Test
    fun correctedHttpsRetriesWithSameDraft() {
        launch()
        fillWebDavForm(url = "http://127.0.0.1:$port/sync", path = "/p2-651-b.kdbx")
        clickText(context.getString(R.string.sync_save_config_btn))
        waitUntil("首次保存被拒") {
            setup.controller.state.value.syncFeedbackMessage?.resId == R.string.sync_error_https_required
        }

        // 只改 scheme；秘密字段不重新输入
        setTextField(Field.URL, "$baseUrl/sync")
        clickText(context.getString(R.string.sync_save_config_btn))
        waitUntil("修正后保存成功") {
            setup.controller.state.value.syncFeedbackMessage?.resId == R.string.sync_webdav_saved
        }

        val saved = requireNotNull(credentials.loadWebDavConfig()) { "修正端点后必须已落盘配置" }
        try {
            assertEquals("端点按用户修正值落盘", "$baseUrl/sync", saved.url)
            assertEquals("用户名落盘", "ui-user", saved.username)
            assertEquals("远程路径落盘", "/p2-651-b.kdbx", saved.remotePath)
            assertEquals(
                "落盘口令必须仍是首次输入的那一份（未重输、也未被擦零顶掉）",
                SYNTHETIC_PASSWORD,
                String(saved.password)
            )
        } finally {
            saved.password.fill('0')
        }
        assertTrue(
            "「保存配置」不得触发任何联网动作，实际: ${receiverRequests()}",
            receiverRequests().isEmpty()
        )
        assertEquals("保存成功后密码输入框必须清空", "", textOfField(Field.PASSWORD))
    }

    @Test
    fun inFlightTestKeepsAAndDoesNotVerifyB() {
        launch()
        fillWebDavForm(url = "$baseUrl/sync", path = "/p2-651-a.kdbx")

        // 让接收方把 A 的测连 PROPFIND 挂住
        control("/__control/hold", post = true)
        clickText(context.getString(R.string.sync_save_and_sync_btn))
        waitUntil("A 的测连请求已到达接收方并被挂住") { receiverState().optBoolean("held") }

        // 在途期间改存 B：只改远程路径，仍走同一接收方（可区分请求归属）
        setTextField(Field.PATH, "/p2-651-b.kdbx")
        clickText(context.getString(R.string.sync_save_config_btn))
        waitUntil("B 保存成功") {
            setup.controller.state.value.syncFeedbackMessage?.resId == R.string.sync_webdav_saved
        }
        assertFalse(
            "改存 B 后旧回执立即失效：A 尚在途时不得宣称 B 已验证",
            setup.controller.state.value.isConnectionVerified
        )

        control("/__control/release", post = true)
        waitUntil("在途编排收尾") { !setup.controller.state.value.isSyncing }

        val requests = receiverRequests()
        // 测连对 serverUrl 本身发 PROPFIND（Depth:0），同步阶段因库未解锁而早退、不再触达网络。
        assertEquals(
            "本次编排只允许触达 A 的端点一次，实际: $requests",
            1,
            requests.size
        )
        assertEquals("A 的测连必须真实到达接收方", "PROPFIND", requests.single().optString("method"))
        assertEquals("请求路径必须是 A 的 serverUrl", "/sync", requests.single().optString("path"))
        assertEquals(
            "请求必须带 A 的凭据用户名",
            WEBDAV_USER,
            requests.single().optString("user")
        )
        assertFalse(
            "A 的迟到结论不得把期间保存的 B 标成已验证",
            setup.controller.state.value.isConnectionVerified
        )
        assertEquals(
            "迟到的 A 结束后徽章必须仍是「未验证」",
            context.getString(R.string.sync_status_unverified),
            syncStatusText()
        )
    }

    @Test
    fun leavingPageWipesDraftAndResetsBusy() {
        launch()
        // 先存一份 A（成功），再在表单里改成**未保存**的 B：离页后 B 不得残留
        fillWebDavForm(url = "$baseUrl/sync", path = "/p2-651-a.kdbx")
        clickText(context.getString(R.string.sync_save_config_btn))
        // 成功提示由页面组件经全局 Snackbar 呈现（控制器只承载失败/连接类反馈），
        // 故基线的判据取**控制器状态反映的真实保存结果**（URL 已归一化写入表单状态）。
        waitUntil("基线配置 A 保存成功") {
            setup.controller.state.value.webdavRemotePath == "/p2-651-a.kdbx" &&
                setup.controller.state.value.syncFeedbackMessage?.resId != R.string.sync_config_save_failed
        }
        setTextField(Field.PATH, "/p2-651-unsaved.kdbx")
        setTextField(Field.PASSWORD, "Synthetic-Unsaved-Draft")

        control("/__control/hold", post = true)
        clickText(context.getString(R.string.sync_save_and_sync_btn))
        waitUntil("测连请求在途") { receiverState().optBoolean("held") }

        // 离页（等价于用户返回）：表单 DisposableEffect 必须擦除本地草稿
        scenario?.close()
        scenario = null
        control("/__control/release", post = true)
        waitUntil("离页后编排收尾") { !setup.controller.state.value.isSyncing }

        // 重进页面：只能回显已保存的 A，绝不能回显未保存的 B
        launch()
        assertEquals(
            "重进页面必须回显已保存的配置（预填通道生效）",
            "$baseUrl/sync",
            textOfField(Field.URL)
        )
        assertEquals(
            "重进页面后远程路径必须是已保存的 A，不得残留未保存草稿",
            "/p2-651-a.kdbx",
            textOfField(Field.PATH)
        )
        assertNotEquals(
            "重进页面后密码框不得回显未保存的草稿值",
            "Synthetic-Unsaved-Draft",
            textOfField(Field.PASSWORD)
        )
    }

    // ------------------------------------------------------------ 装置

    private fun hasRunnerArgument(): Boolean =
        InstrumentationRegistry.getArguments().getString(ARG_PORT)?.toIntOrNull() != null

    private fun launch() {
        scenario = ActivityScenario.launch(CloudSyncDeviceHostActivity::class.java)
        instrumentation.waitForIdleSync()
        waitUntil("同步表单未渲染") { editableTexts().size >= WEBDAV_FIELD_COUNT }
        assertEquals(
            "WebDAV 表单应恰有 4 个可编辑字段（URL/用户名/密码/远程路径）",
            WEBDAV_FIELD_COUNT,
            editableTexts().size
        )
    }

    private fun fillWebDavForm(url: String, path: String) {
        setTextField(Field.URL, url)
        setTextField(Field.USERNAME, WEBDAV_USER)
        setTextField(Field.PASSWORD, SYNTHETIC_PASSWORD)
        setTextField(Field.PATH, path)
    }

    private fun enableAccessibilityRetrieval() {
        val info = automation.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        automation.serviceInfo = info
    }

    private fun control(path: String, post: Boolean) {
        val request = if (post) {
            Request.Builder().url("$baseUrl$path").post(ByteArray(0).toRequestBody(JSON)).build()
        } else {
            Request.Builder().url("$baseUrl$path").get().build()
        }
        controlClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "接收方控制端点 $path 返回 ${response.code}" }
        }
    }

    private fun receiverState(): JSONObject {
        val body = controlClient.newCall(Request.Builder().url("$baseUrl/__control/state").build())
            .execute().use { it.body.string() }
        return JSONObject(body)
    }

    private fun receiverRequests(): List<JSONObject> {
        val array = receiverState().optJSONArray("requests") ?: return emptyList()
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    /** 当前无障碍树中所有可编辑节点的文本（按树序，回收后返回）。 */
    private fun editableTexts(): List<String> {
        val collected = mutableListOf<String>()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            all += node
            if (node.isEditable) collected += node.text?.toString().orEmpty()
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
        all.forEach { it.recycle() }
        return collected
    }

    private fun textOfField(field: Field): String = editableTexts().getOrElse(field.index) {
        error("第 ${field.index} 个可编辑字段不存在（字段顺序可能已变，需同步更新本用例）")
    }

    /**
     * 按语义定位字段：WebDAV 表单的可编辑节点顺序为 URL / 用户名 / 密码 / 远程路径，
     * 密码节点带 `password()` 语义（`SecurePasswordField` 显式声明）。
     * 这里对「密码位确实是密码节点」做前提断言——顺序一旦漂移，用例应当红而不是写错字段。
     */
    private fun setTextField(field: Field, value: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            val editables = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                nodes += node
                if (node.isEditable) editables += node
                repeat(node.childCount) { visit(node.getChild(it)) }
            }
            visit(automation.rootInActiveWindow)
            val target = editables.getOrNull(field.index)
            if (target != null) {
                if (field == Field.PASSWORD) {
                    assertTrue("第 2 个可编辑字段必须是密码节点（SecurePasswordField 语义）", target.isPassword)
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                val applied = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                nodes.forEach { it.recycle() }
                assertTrue("字段 ${field.name} 无法写入文本", applied)
                SystemClock.sleep(INPUT_SETTLE_MS)
                return
            }
            nodes.forEach { it.recycle() }
            SystemClock.sleep(50)
        }
        error("等待字段 ${field.name} 超时")
    }

    private fun clickText(label: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var scrollAttempts = 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = automation.rootInActiveWindow
            val match = findText(root, label)
            if (match != null) {
                var current: AccessibilityNodeInfo? = match
                repeat(MAX_ANCESTORS) {
                    val candidate = current ?: return@repeat
                    if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return
                    }
                    current = candidate.parent
                }
                // Compose Button 的可点击语义可能落在与文本并列的兄弟语义节点上；
                // 找不到可点击祖先时按文本节点中心坐标点击（与人工点击等价）。
                // 点击前重新取一次节点：滚动后旧实例的 bounds 可能已过期。
                val fresh = findText(automation.rootInActiveWindow, label) ?: match
                val bounds = Rect().also { fresh.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0) {
                    automation.executeShellCommand(
                        "input tap ${bounds.centerX()} ${bounds.centerY()}"
                    ).use { }
                    SystemClock.sleep(CONTROL_SETTLE_MS)
                    return
                }
                error("文本「$label」没有可点击祖先且无有效坐标")
            }
            // 表单是 LazyColumn：按钮可能在视口下方，未组合就永远等不到。
            // 先回到顶部再逐段向下扫，避免一次滚过头把目标表单区滚出视口。
            if (scrollAttempts % RESCAN_EVERY == 0) scrollToTop()
            scrollForwardOnce()
            scrollAttempts++
            SystemClock.sleep(60)
        }
        error("等待可点击文本「$label」超时（${UI_TIMEOUT_MS}ms）；页面=${currentTexts()}")
    }

    /** 快速回到列表顶部（连续上滑），避免扫描越过目标表单区。 */
    private fun scrollToTop() {
        repeat(6) { scrollBackwardOnce() }
    }

    /** 向上滚动一次设置二级页。 */
    private fun scrollBackwardOnce() {
        val bounds = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        if (bounds.height() <= 0) return
        val x = bounds.centerX()
        val start = bounds.top + bounds.height() * SCROLL_END_PERCENT / PERCENT
        val end = bounds.top + bounds.height() * SCROLL_START_PERCENT / PERCENT
        automation.executeShellCommand("input swipe $x $start $x $end 120").use { }
        SystemClock.sleep(80)
    }

    /** 向下滚动一次设置二级页（LazyColumn）。无可滚动节点时静默跳过。 */
    private fun scrollForwardOnce() {
        val bounds = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        if (bounds.height() <= 0) return
        val x = bounds.centerX()
        val start = bounds.top + bounds.height() * SCROLL_START_PERCENT / PERCENT
        val end = bounds.top + bounds.height() * SCROLL_END_PERCENT / PERCENT
        automation.executeShellCommand("input swipe $x $start $x $end 120").use { }
    }

    private fun currentTexts(): String = buildString {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { append("'").append(it).append("' ") }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { append("desc='").append(it).append("' ") }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow)
    }

    private fun findText(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun syncStatusText(): String {
        val badges = mutableListOf<String>()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            all += node
            node.text?.toString()?.let { badges += it }
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
        all.forEach { it.recycle() }
        return badges.firstOrNull {
            it == context.getString(R.string.sync_status_unverified) ||
                it == context.getString(R.string.sync_status_verified_only) ||
                it == context.getString(R.string.sync_status_synced) ||
                it == context.getString(R.string.sync_status_syncing)
        }.orEmpty()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("$message（超时 ${UI_TIMEOUT_MS}ms）", condition())
    }

    private enum class Field(val index: Int) {
        URL(0),
        USERNAME(1),
        PASSWORD(2),
        PATH(3)
    }

    private companion object {
        const val ARG_PORT = "p2_651_port"
        const val UI_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 800L
        const val INPUT_SETTLE_MS = 80L
        const val MAX_ANCESTORS = 8
        const val PERCENT = 100
        const val SCROLL_START_PERCENT = 75
        const val SCROLL_END_PERCENT = 30
        const val RESCAN_EVERY = 6
        const val CONTROL_SETTLE_MS = 250L
        const val WEBDAV_FIELD_COUNT = 4
        const val WEBDAV_USER = "ui-user"
        const val SYNTHETIC_PASSWORD = "Synthetic-P2-651-Ui-Password"
        val JSON = "application/json".toMediaType()
    }
}
