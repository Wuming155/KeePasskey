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

    /**
     * 接收方的 Basic 凭据由夹具下发：接收方对不匹配的用户名直接回 401，
     * 测连 PROPFIND 会在鉴权分支提前返回（门闩永不置位，in-flight 编排失去判别力）。
     * 用例写死用户名曾导致整条链路静默降级，故改为参数传入、缺参即失败。
     */
    private val webDavUser: String by lazy {
        requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_USER)) {
            "缺少 $ARG_USER（由 tools/device/run_p2_651_652_ui.py 提供）"
        }
    }
    private val webDavPassword: String by lazy {
        requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_PASSWORD)) {
            "缺少 $ARG_PASSWORD（由 tools/device/run_p2_651_652_ui.py 提供）"
        }
    }

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
        // 每个用例都从**无已保存配置**起步：残留配置会被 hydrate() 异步回灌进表单，
        // 覆盖用例刚写入的字段（曾因此把「http 应被拒」变成「https 保存成功」）。
        credentials.clear()
        CloudSyncDeviceHostActivity.updateCalls.set(0)
        CloudSyncDeviceHostActivity.lastSubmittedUrl = ""
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
        // 前置：字段写入必须真的落到 Compose 状态上，否则后续点击提交的是空表单
        assertEquals(
            "URL 字段写入应立即可读；实际全部可编辑字段=${editableTexts()}",
            "http://127.0.0.1:$port/sync",
            textOfField(Field.URL)
        )

        clickTextUntil(context.getString(R.string.sync_save_and_sync_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > 0
        }
        assertTrue(
            "点击「保存并同步」后必须真的进入保存入口（否则问题在驱动而非产品）；" +
                "updateCalls=${CloudSyncDeviceHostActivity.updateCalls.get()}，" +
                "lastSubmittedUrl='${CloudSyncDeviceHostActivity.lastSubmittedUrl}'，" +
                "页面=${currentTexts()}",
            CloudSyncDeviceHostActivity.updateCalls.get() > 0
        )

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
        clickTextUntil(context.getString(R.string.sync_save_config_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > 0
        }
        waitUntil("首次保存被拒") {
            setup.controller.state.value.syncFeedbackMessage?.resId == R.string.sync_error_https_required
        }

        // 只改 scheme；秘密字段不重新输入
        setTextField(Field.URL, "$baseUrl/sync")
        val callsBefore = CloudSyncDeviceHostActivity.updateCalls.get()
        clickTextUntil(context.getString(R.string.sync_save_config_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > callsBefore
        }
        // 成功提示经页面 Snackbar 呈现，控制器成功分支会清空 `syncFeedbackMessage`；
        // 故判据取**真实落盘结果**（这正是本问题要证明的事）。
        waitUntil("修正后保存成功（已落盘）") {
            credentials.loadWebDavConfig()?.url == "$baseUrl/sync"
        }

        val saved = requireNotNull(credentials.loadWebDavConfig()) { "修正端点后必须已落盘配置" }
        try {
            assertEquals("端点按用户修正值落盘", "$baseUrl/sync", saved.url)
            assertEquals("用户名落盘", webDavUser, saved.username)
            assertEquals("远程路径落盘", "/p2-651-b.kdbx", saved.remotePath)
            assertEquals(
                "落盘口令必须仍是首次输入的那一份（未重输、也未被擦零顶掉）",
                webDavPassword,
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
        clickTextUntil(context.getString(R.string.sync_save_and_sync_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > 0
        }
        waitUntil("A 的测连请求已到达接收方并被挂住") { receiverState().optBoolean("held") }

        // 在途期间改存 B：只改远程路径，仍走同一接收方（可区分请求归属）
        setTextField(Field.PATH, "/p2-651-b.kdbx")
        val callsBeforeB = CloudSyncDeviceHostActivity.updateCalls.get()
        clickTextUntil(context.getString(R.string.sync_save_config_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > callsBeforeB
        }
        waitUntil("B 保存成功（已落盘）") {
            credentials.loadWebDavConfig()?.remotePath == "/p2-651-b.kdbx"
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
            webDavUser,
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
        // 1) 基线：保存 A
        fillWebDavForm(url = "$baseUrl/sync", path = "/p2-651-a.kdbx")
        clickTextUntil(context.getString(R.string.sync_save_config_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > 0
        }
        waitUntil("基线配置 A 保存成功") {
            credentials.loadWebDavConfig()?.remotePath == "/p2-651-a.kdbx"
        }

        // 2) 只改草稿、**不提交**：离页后草稿不得残留。
        //    注意不能借「保存并同步」制造在途——那个入口会先把草稿落盘，B 就成了已保存值，
        //    「不得残留未保存草稿」的断言随即失去意义（曾因此把正确的 B 误判成缺陷）。
        setTextField(Field.PATH, "/p2-651-unsaved.kdbx")
        setTextField(Field.PASSWORD, "Synthetic-Unsaved-Draft")
        scenario?.close()
        scenario = null

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

        // 3) 在途离页：编排被打断后忙态必须复位（否则指示器永久停在「同步中」）。
        //    此时表单值与已保存的 A 一致，提交不会改变落盘结果。
        setTextField(Field.PASSWORD, webDavPassword)
        control("/__control/hold", post = true)
        val callsBeforeSync = CloudSyncDeviceHostActivity.updateCalls.get()
        clickTextUntil(context.getString(R.string.sync_save_and_sync_btn)) {
            CloudSyncDeviceHostActivity.updateCalls.get() > callsBeforeSync
        }
        waitUntil("测连请求在途") { receiverState().optBoolean("held") }

        scenario?.close()
        scenario = null
        control("/__control/release", post = true)
        waitUntil("离页后编排收尾") { !setup.controller.state.value.isSyncing }
    }

    // ------------------------------------------------------------ 装置

    private fun hasRunnerArgument(): Boolean =
        InstrumentationRegistry.getArguments().getString(ARG_PORT)?.toIntOrNull() != null

    private fun launch() {
        CloudSyncDeviceHostActivity.updateCalls.set(0)
        CloudSyncDeviceHostActivity.lastSubmittedUrl = ""
        scenario = ActivityScenario.launch(CloudSyncDeviceHostActivity::class.java)
        instrumentation.waitForIdleSync()
        waitUntil("同步表单未渲染") { editableTexts().size >= WEBDAV_FIELD_COUNT }
        assertEquals(
            "WebDAV 表单应恰有 4 个可编辑字段（URL/用户名/密码/远程路径）",
            WEBDAV_FIELD_COUNT,
            editableTexts().size
        )
        // `hydrate()` 在页面组合时异步回灌已保存配置；它若在本用例写字段**之后**落地，
        // 就会把用户输入整段覆盖（这正是此前「点击无反应」的真实原因）。
        // 等待条件取「控制器侧已给出非空端点」：清空配置后 hydrate 会填默认端点，
        // 因此该条件与「回灌已收尾」等价，且不会把「还没有任何回灌」误判成已完成。
        waitUntil("控制器水合（已保存配置回灌）未收尾") {
            setup.controller.state.value.webdavUrl.isNotBlank()
        }
        SystemClock.sleep(HYDRATE_SETTLE_MS)
    }

    private fun fillWebDavForm(url: String, path: String) {
        setTextField(Field.URL, url)
        setTextField(Field.USERNAME, webDavUser)
        setTextField(Field.PASSWORD, webDavPassword)
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

    /**
     * 取当前视口内的表单字段，**按语义**而非树序或纯位置定位。
     *
     * 树序在同一进程内跑多个用例时并不可靠（曾观察到密码节点被排到首位，导致「写入 URL」
     * 实际写进密码框、断言读到的是掩码）；纯位置排序在滚动动画期间也会抖动。
     *
     * 关键约束：**只有四个字段同时在视口内时映射才可信**。滚动后视口常只剩部分字段，
     * 此时「第一个文本节点 = URL」会把远程路径错认成 URL（曾因此报出
     * 「URL 显示成了 /p2-651-a.kdbx」）。字段不齐时返回空表，由调用方滚动后重试。
     */
    private fun <T> withFormFields(block: (Map<Field, AccessibilityNodeInfo>) -> T): T {
        val all = mutableListOf<AccessibilityNodeInfo>()
        val editables = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            all += node
            if (node.isEditable) editables += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
        val password = editables.firstOrNull { it.isPassword }
        val texts = editables.filter { it !== password }.sortedWith(
            compareBy<AccessibilityNodeInfo> { Rect().also { r -> it.getBoundsInScreen(r) }.top }
                .thenBy { Rect().also { r -> it.getBoundsInScreen(r) }.left }
        )
        val fields = if (password != null && texts.size == WEBDAV_FIELD_COUNT - 1) {
            mapOf(
                Field.URL to texts[0],
                Field.USERNAME to texts[1],
                Field.PATH to texts[2],
                Field.PASSWORD to password
            )
        } else {
            emptyMap()
        }
        try {
            return block(fields)
        } finally {
            all.forEach { it.recycle() }
        }
    }

    /** 当前无障碍树中所有可编辑节点的文本（密码节点按语义排在末位，便于诊断阅读）。 */
    private fun editableTexts(): List<String> = withFormFields { fields ->
        listOfNotNull(
            fields[Field.URL], fields[Field.USERNAME], fields[Field.PASSWORD], fields[Field.PATH]
        ).map { it.text?.toString().orEmpty() }
    }

    /**
     * 读字段值。表单是 `LazyColumn`：滚到按钮处后上方字段可能已被回收，
     * 故先回到顶部再读，避免把「不在视口」误报成「字段不存在」。
     */
    private fun textOfField(field: Field): String {
        repeat(FIELD_READ_ATTEMPTS) {
            withFormFields { fields -> fields[field] }?.let { return it.text?.toString().orEmpty() }
            scrollToTop()
            SystemClock.sleep(CONTROL_SETTLE_MS)
        }
        error("字段 ${field.name} 不在视口（当前字段=${editableTexts()}，页面=${currentTexts()}）")
    }

    /** 写入单个字段；非密码字段回读校验，写不进去就重试（原因见方法内注释）。 */
    private fun setTextField(field: Field, value: String) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var lastSeen: String? = null
        var attemptsWithoutFields = 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val wrote = withFormFields { fields ->
                val target = fields[field] ?: return@withFormFields false
                if (field == Field.PASSWORD) {
                    assertTrue("密码字段必须带 password() 语义（SecurePasswordField）", target.isPassword)
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            if (!wrote) {
                // 四字段必须同时在视口内映射才可信；不齐就回顶部重扫
                // （上一场景可能把列表滚到了按钮处，上方字段已被 LazyColumn 回收）。
                attemptsWithoutFields++
                if (attemptsWithoutFields % FIELD_RESCAN_EVERY == 0) scrollToTop()
                SystemClock.sleep(60)
                continue
            }
            SystemClock.sleep(INPUT_SETTLE_MS)
            // 密码字段的无障碍文本是掩码，不能按值比对；其余字段做回读校验。
            // `ACTION_SET_TEXT` 可能返回 true 却没落到真实状态（Compose 的文本编辑语义节点
            // 在写入后立刻被外部状态重播种时会这样）——写不进去就重试，
            // 否则断言会在「输入没进去」上误报成「产品守卫失效」。
            if (field == Field.PASSWORD) return
            lastSeen = readField(field)
            if (lastSeen == value) return
            SystemClock.sleep(INPUT_RETRY_MS)
        }
        error("字段 ${field.name} 写入未能生效（期望 '$value'，实际 '$lastSeen'）；全部字段=${editableTexts()}")
    }

    /** 读单个字段当前值（找不到节点时返回 null，供写入回读校验用）。 */
    private fun readField(field: Field): String? =
        withFormFields { fields -> fields[field]?.text?.toString() }

    private fun clickText(label: String) = clickTextUntil(label) { true }

    /**
     * 点击文本直到 [applied] 成立。
     *
     * Compose 的点击语义有时落在与文本并列的兄弟节点上，坐标点击也可能被滚动动画吃掉——
     * 单次点击「成功返回」并不等于动作真的发生。这里在每次点击后校验 [applied]，
     * 未生效就重新定位再点，直到超时。
     */
    private fun clickTextUntil(label: String, applied: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        var clicks = 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = automation.rootInActiveWindow
            val match = findText(root, label)
            if (match != null) {
                // 目标已可见就直接点。不做「先滚进中部」：那会把紧邻目标的表单 item
                // 整块滚出视口而被 LazyColumn 回收（clicks=0 的根因）。
                var clicked = false
                var current: AccessibilityNodeInfo? = match
                repeat(MAX_ANCESTORS) {
                    val candidate = current ?: return@repeat
                    if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true
                        return@repeat
                    }
                    current = candidate.parent
                }
                if (!clicked) {
                    // Compose Button 的可点击语义可能落在与文本并列的兄弟语义节点上；
                    // 找不到可点击祖先时按文本节点中心坐标点击（与人工点击等价）。
                    val fresh = findText(automation.rootInActiveWindow, label) ?: match
                    val bounds = Rect().also { fresh.getBoundsInScreen(it) }
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        automation.executeShellCommand(
                            "input tap ${bounds.centerX()} ${bounds.centerY()}"
                        ).use { }
                        clicked = true
                    } else {
                        error("文本「$label」没有可点击祖先且无有效坐标")
                    }
                }
                clicks++
                SystemClock.sleep(CONTROL_SETTLE_MS)
                instrumentation.waitForIdleSync()
                if (applied()) return
                SystemClock.sleep(INPUT_RETRY_MS)
                continue
            }
            // 表单是 LazyColumn：目标可能在视口下方。**单调向下**扫描，滚到底才回到顶部
            // 重扫——周期性回顶部会把已经越过的目标反复跨过去（曾整轮 20s 都没命中按钮）。
            val before = viewportText()
            scrollForwardOnce()
            if (viewportText() == before) {
                // 已到底：回到顶部再扫一轮
                scrollToTop()
            }
            SystemClock.sleep(60)
        }
        error(
            "等待可点击文本「$label」超时（${UI_TIMEOUT_MS}ms，已点击 $clicks 次）；" +
                "updateCalls=${CloudSyncDeviceHostActivity.updateCalls.get()}，" +
                "页面=${currentTexts()}"
        )
    }

    /** 快速回到列表顶部（连续上滑），避免扫描越过目标表单区。 */
    private fun scrollToTop() {
        clearFocus()
        repeat(MAX_SCROLL_TO_TOP) {
            val before = viewportText()
            scrollBackwardOnce()
            if (viewportText() == before) return
        }
    }

    /**
     * 收起软键盘并清掉输入焦点。
     *
     * 键盘弹出后视口只剩一半，`input swipe` 的起点常落在键盘上——滑动因此被吞掉，
     * 表现为「滚不到按钮」（曾把 Save and sync / Save configuration 等按钮判成不存在）。
     */
    private fun clearFocus() {
        val focused = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isFocused) focused += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
        focused.forEach { it.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
        automation.executeShellCommand("input keyevent 111").use { }
        SystemClock.sleep(CONTROL_SETTLE_MS)
    }

    /** 在可滚动容器上执行一次滚动动作；并校验确实发生了移动，否则返回 false。 */
    private fun scrollByAction(forward: Boolean): Boolean {
        val before = viewportText()
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            all += node
            if (node.isScrollable) scrollables += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val applied = scrollables.any { it.performAction(action) }
        all.forEach { it.recycle() }
        SystemClock.sleep(CONTROL_SETTLE_MS)
        // Compose 的滚动节点可能返回「已处理」却没真的移动（无脑重放会原地空转）。
        // 只有视口内容真的变了，才算滚动成功；否则交给 swipe 兜底。
        return applied && viewportText() != before
    }

    /** 当前视口内全部可见文本（滚动前后比对用）。 */
    private fun viewportText(): String {
        val sb = StringBuilder()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('|') }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow)
        return sb.toString()
    }

    /** 向上滚动一次设置二级页。 */
    private fun scrollBackwardOnce() {
        if (scrollByAction(forward = false)) return
        val bounds = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        if (bounds.height() <= 0) return
        val x = bounds.centerX()
        val start = bounds.top + bounds.height() * SCROLL_BACK_START_PERCENT / PERCENT
        val end = bounds.top + bounds.height() * SCROLL_BACK_END_PERCENT / PERCENT
        automation.executeShellCommand("input swipe $x $start $x $end 200").use { }
        SystemClock.sleep(SCROLL_SETTLE_MS)
    }

    /** 向下滚动一次设置二级页（LazyColumn）。无可滚动节点时静默跳过。 */
    private fun scrollForwardOnce() {
        if (scrollByAction(forward = true)) return
        val bounds = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        if (bounds.height() <= 0) return
        val x = bounds.centerX()
        // 步长刻意取小：表单 item（含 Save and sync / Save configuration）整体约一屏的 1/3，
        // 大步长（曾用 45% 屏高）会一次跨过它，导致「滚到底也扫不到按钮」。
        val start = bounds.top + bounds.height() * SCROLL_FWD_START_PERCENT / PERCENT
        val end = bounds.top + bounds.height() * SCROLL_FWD_END_PERCENT / PERCENT
        automation.executeShellCommand("input swipe $x $start $x $end 200").use { }
        SystemClock.sleep(SCROLL_SETTLE_MS)
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
        const val SCROLL_FWD_START_PERCENT = 62
        const val SCROLL_FWD_END_PERCENT = 45
        const val SCROLL_BACK_START_PERCENT = 45
        const val SCROLL_BACK_END_PERCENT = 62
        const val SCROLL_SETTLE_MS = 220L
        const val MAX_SCROLL_TO_TOP = 12



        const val CONTROL_SETTLE_MS = 250L
        const val HYDRATE_SETTLE_MS = 400L
        const val INPUT_RETRY_MS = 120L
        const val WEBDAV_FIELD_COUNT = 4
        const val ARG_USER = "p2_651_user"
        const val ARG_PASSWORD = "p2_651_password"
        const val FIELD_READ_ATTEMPTS = 6
        const val FIELD_RESCAN_EVERY = 4
        val JSON = "application/json".toMediaType()
    }
}
