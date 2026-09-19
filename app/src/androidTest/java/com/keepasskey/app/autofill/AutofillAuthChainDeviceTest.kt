package com.keepasskey.app.autofill

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * ISSUE-P2-73 AC③：**设备侧实测自动填充认证填充链路**。
 *
 * ## 实测覆盖（真机，非模拟器）
 *
 * - **A（认证链路不崩溃 + 认证结果被接受）**：库锁定时服务下发带 `setAuthentication` 的数据集 →
 *   系统填充 UI 展示 → 点选后由**框架**拉起 [AutofillUnlockActivity] → 在真机上完成解锁 →
 *   该页链入 [AutofillPickerActivity] 并把其认证结果原样转发给框架，全程无崩溃。
 * - **B（解锁后不经人工重请求即可填充，ISSUE-P2-86 判据）**：解锁页链入的选择器中显式指认条目后，
 *   框架把**真实凭据**写入客户端目标输入框（客户端 logcat 自述 `passwordFilled=true`），
 *   且全程客户端 `requestAutofill(` 计数不变——即不依赖任何「显式重请求」补救手法。
 * - **B（已解锁分支选择器路径）**：客户端重新拉起后请求填充 → 经选择器指认条目 → 再次被真实填充。
 * - **C（第二个认证 Activity + 真实写入，ISSUE-P2-88 判据）**：已解锁候选数据集点选后由框架拉起
 *   [AutofillConfirmActivity]，其「确认填充」经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT`
 *   回传真实 `Dataset`，框架随即把**真实凭据写入客户端两个输入框**
 *   （客户端自述 `usernameFilled=true passwordFilled=true`），且全程不崩溃。
 *
 * ## 归因纪律（关键，勿放宽）
 *
 * 客户端 [AutofillClientActivity] **每个实例只主动请求一次**填充。原因：认证 Activity 进出会令客户端
 * 多次 resume，若每次 resume 都请求，就会把「框架在认证结果后自行重发」与「客户端自己又请求了一次」
 * 混为一谈。用例在关键位置同时记录客户端请求次数，保证「框架是否自行重发」的结论可归因。
 *
 * ISSUE-P2-86 补充：**不得**再以「客户端显式重请求」作为把链路接下去的手段——该手法恰好绕过
 * 「解锁后不填充」这一缺陷本身。阶段 5 的写入断言必须在客户端请求计数不变的前提下达成。
 *
 * ## 判定口径（AGENTS.md §5）
 *
 * 以 task 结果为准：`TEST-*.xml` 的 `tests/failures/errors/skipped` 与 `test-result-exit-code.txt`。
 * 本用例**不使用** `AssumptionViolatedException`；未达成的阶段显式失败或如实记为「未覆盖边界」。
 */
@RunWith(AndroidJUnit4::class)
class AutofillAuthChainDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext

    /** 测试客户端包名：测试 APK 的包（与填充服务所在包 `com.keepasskey` 不同） */
    private val clientPackage: String = instrumentation.context.packageName
    private val probe = DeviceAutofillProbe()

    private lateinit var application: Application
    private lateinit var evidence: Evidence
    private var originalAutofillService: String? = null

    /** 进程内 Activity 生命周期观测：认证 Activity 由系统在**本进程**拉起，可被直接观测 */
    private val lifecycleTrace: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            lifecycleTrace.add("created:${activity.javaClass.name}")
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            lifecycleTrace.add("resumed:${activity.javaClass.name}")
        }

        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            lifecycleTrace.add("destroyed:${activity.javaClass.name}")
        }
    }

    @Before
    fun setUp() {
        application = targetContext.applicationContext as Application
        evidence = Evidence(targetContext)
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    @After
    fun tearDown() {
        application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        runCatching {
            if (originalAutofillService == null) {
                probe.shell("settings delete secure autofill_service")
            } else {
                probe.shell("settings put secure autofill_service $originalAutofillService")
            }
            evidence.write("收尾：自动填充服务恢复为 ${originalAutofillService ?: "空"}")
            // AGP 在 connectedDebugAndroidTest 结束后会卸载 APK 并清除应用数据，
            // 故把证据文件另存到共享存储（shell 身份可写），供宿主侧取证。
            evidence.write("证据另存: ${evidence.publish("/sdcard/Download")}")
        }.onFailure { Log.w(EVIDENCE_TAG, "收尾阶段异常: ${it.javaClass.simpleName} ${it.message}") }
        evidence.flushToLog()
    }

    @Test
    fun 设备侧实测自动填充认证填充链路() {
        // ---------------------------------------------------------------- 阶段 0：播种与前置
        evidence.section("阶段 0：播种密码库 + 环境与前置")
        // UiAutomation 延迟获取：构造期获取会与系统既有注册冲突并崩进程（见 probe KDoc）
        probe.requireAutomation()
        // 原始取值用于收尾还原（设备是共享资源，不留下全局设置改动）
        originalAutofillService = probe.shell("settings get secure autofill_service").trim().ifEmpty { null }
        val vault = AutofillTestVaultSeeder.seed(targetContext, clientPackage)
        val serviceComponent = ComponentName(
            targetContext.packageName,
            KeePasskeyAutofillService::class.java.name
        ).flattenToShortString()
        evidence.write("填充服务组件: $serviceComponent")
        evidence.write("真实客户端包名（独立于本应用，独立进程/uid）: $clientPackage")
        evidence.write(
            "UiAutomation 槽位占用者探测（Android CLI 交互辅助 APK 的 pid，空即无占用）: " +
                probe.shell("pidof com.android.cli.interact.instrumentation").trim()
        )
        evidence.write("播种密码库: ${vault.absolutePath} 存在=${vault.isFile} 大小=${vault.length()}")
        evidence.write(
            "设备信息: " + probe.shell("getprop ro.product.model").trim() +
                " / Android " + probe.shell("getprop ro.build.version.release").trim() +
                " / API " + probe.shell("getprop ro.build.version.sdk").trim() +
                " / " + probe.shell("getprop ro.product.cpu.abi").trim()
        )

        // 完整性闸门取值：必须不是 COMPROMISED（该档 disableAutofill=true 会拒绝全部数据集）。
        // 取证纪律：logcat 环形缓冲跨运行持久，直接 tag 过滤可能读到**上一次运行**的陈旧值，
        // 故只认「PID 等于本进程」的行——本进程即被测应用进程，其启动扫描/周期重扫均打此留痕。
        val integrityLevel = awaitIntegrityLevel(INTEGRITY_WAIT_MS)
        evidence.write(
            "完整性扫描留痕（本进程 pid=${android.os.Process.myPid()}）: level=${integrityLevel ?: "未捕获"}"
        )
        assertTrue(
            "本进程（pid=${android.os.Process.myPid()}）未打出完整性扫描留痕——" +
                "无法证明闸门取值，不得据此宣称链路可驱动",
            integrityLevel != null
        )
        assertEquals(
            "完整性闸门处于 COMPROMISED（disableAutofill=true）时认证链路必然不可驱动；" +
                "应先清理注入痕迹后冷启动重测，不得削弱生产判定",
            false,
            integrityLevel == "COMPROMISED"
        )

        // 清空 logcat，使后续留痕只属于本用例（放在闸门取证之后，避免误清本次扫描留痕）
        probe.shell("logcat -c")

        // ---------------------------------------------------------------- 阶段 0.5：解除锁屏（设备侧前置）
        evidence.section("阶段 0.5：唤醒屏幕并解除锁屏（认证 Activity 需在前台可见）")
        probe.shell("input keyevent 224")
        probe.shell("wm dismiss-keyguard")
        val keyguardGone = awaitKeyguardGone(KEYGUARD_WAIT_MS)
        evidence.write("锁屏已解除=$keyguardGone")
        assertTrue(
            "设备仍处于锁屏态（keyguard_root_view 在窗口树中）——认证填充链路要求设备已唤醒且解锁；" +
                "请唤醒并解锁设备后重跑（本用例不修改任何锁屏/安全设置）",
            keyguardGone
        )

        // ---------------------------------------------------------------- 阶段 1：配置填充服务
        evidence.section("阶段 1：把本应用配置为系统自动填充服务")
        probe.shell("settings put secure autofill_service $serviceComponent")
        val readBack = probe.shell("settings get secure autofill_service").trim()
        evidence.write("settings get secure autofill_service → $readBack")
        assertEquals("自动填充服务必须已切换为本应用", serviceComponent, readBack)

        // ---------------------------------------------------------------- 阶段 2：拉起真实客户端
        evidence.section("阶段 2：拉起独立包名的真实客户端登录表单")
        targetContext.startActivity(clientIntent())
        val clientWindowUp = probe.awaitText(AutofillClientActivity.TITLE, CLIENT_WAIT_MS) != null
        evidence.write("客户端窗口出现=$clientWindowUp")
        assertTrue("测试客户端 Activity 未出现在无障碍树中（跨包显式启动失败）", clientWindowUp)

        // ---------------------------------------------------------------- 阶段 3：库锁定 ⇒ 认证数据集
        evidence.section("阶段 3：框架下发并展示「认证引导」数据集（库锁定分支）")
        val unlockItem = probe.awaitText(UNLOCK_ITEM_TEXT, FILL_UI_WAIT_MS)
        if (unlockItem == null) {
            evidence.write("窗口快照:\n" + probe.snapshot())
            evidence.write("服务侧留痕:\n" + filteredLogcat(SERVICE_TAG))
            evidence.write("框架留痕:\n" + frameworkLogcat())
        }
        assertNotNull(
            "系统填充 UI 未出现认证引导数据集「$UNLOCK_ITEM_TEXT」——" +
                "即带 setAuthentication 的 FillResponse 未到达框架或未被展示",
            unlockItem
        )
        evidence.write("认证引导数据集已展示: 「${textOf(unlockItem!!)}」")
        screenshot("ac3-01-auth-dataset")

        // ---------------------------------------------------------------- 阶段 4：点选 ⇒ 框架拉起认证 Activity
        evidence.section("阶段 4：点选数据集，由**框架**拉起 AutofillUnlockActivity")
        evidence.write("点选结果=${probe.click(unlockItem)}")
        val unlockLaunched = awaitActivity(UNLOCK_ACTIVITY, ACTIVITY_WAIT_MS)
        evidence.write("生命周期留痕: ${lifecycleTrace.toList()}")
        assertTrue("框架未拉起 $UNLOCK_ACTIVITY（认证 PendingIntent 未被系统执行）", unlockLaunched)
        SystemClock.sleep(UI_SETTLE_MS)
        screenshot("ac3-02-auth-activity")

        // ---------------------------------------------------------------- 阶段 5：完成解锁 ⇒ 结果被接受
        evidence.section("阶段 5：输入主密码完成解锁，核对 setResult(int, Intent) 契约与后续行为")
        // 2026-09-17 真机踩坑（本用例侧缺陷，已收敛）：解锁页由框架拉起时**落在客户端任务内**，
        // 在解锁窗口真正成为活动窗口之前，`rootInActiveWindow` 仍是客户端窗口——此时只按
        // 「可编辑」匹配会把主密码敲进客户端的用户名框（实测出现 `username=[<主密码>]`，
        // 随后解锁按钮因密码框为空而失败，链路在 30 s 内不进入选择器）。
        // 故按**包名**把查找收敛到本应用窗口。
        val passwordField = probe.awaitActiveNode(PASSWORD_FIELD_LABEL, ACTIVITY_WAIT_MS) { node ->
            node.packageName?.toString() == targetContext.packageName &&
                (node.isEditable || node.isPassword)
        }
        assertNotNull("未在解锁页找到主密码输入框（已按包名收敛到本应用窗口）", passwordField)
        val typed = probe.setText(passwordField!!, AutofillSeedContract.MASTER_PASSWORD_TEXT)
        evidence.write(
            "ACTION_SET_TEXT 写入主密码=$typed（长度 ${AutofillSeedContract.MASTER_PASSWORD_TEXT.length}）"
        )
        if (!typed) {
            // 退化路径：真实触摸聚焦 + shell 注入文本
            probe.tapNode(passwordField)
            SystemClock.sleep(UI_SETTLE_MS)
            probe.shell("input text ${AutofillSeedContract.MASTER_PASSWORD_TEXT}")
            evidence.write("退化路径：触摸聚焦 + input text 注入")
        }
        SystemClock.sleep(UI_SETTLE_MS)

        val unlockButton = probe.awaitActiveNode(UNLOCK_BUTTON_LABEL, ACTIVITY_WAIT_MS) { node ->
            normalizedText(node).contains(UNLOCK_BUTTON_KEY)
        }
        assertNotNull("未在解锁页找到解锁按钮", unlockButton)
        val clientRequestsBeforeUnlock = clientRequestCount()
        evidence.write("解锁按钮点击=${probe.click(unlockButton!!)}（文本「${normalizedText(unlockButton)}」）")

        // (1) ISSUE-P2-86 的决定性观测：解锁页解锁成功后**链入选择器**，由本次认证结果直接
        //     交付真实 Dataset。**不**再用「客户端显式重请求」把链路接下去——该手法恰好绕过本缺陷。
        //     注意：此刻解锁页**尚未**结束——它要等选择器回传结果后才 setResult+finish。
        val pickerChained = awaitNewTrace("resumed:$PICKER_ACTIVITY", CHAIN_WAIT_MS)
        evidence.write("生命周期留痕: ${lifecycleTrace.toList()}")
        assertTrue(
            "解锁页未链入 $PICKER_ACTIVITY——ISSUE-P2-86 要求解锁成功后直接经选择器交付，" +
                "而非以空载荷结束并等待（已被证伪的）框架重发",
            pickerChained
        )
        SystemClock.sleep(UI_SETTLE_MS)
        evidence.write("解锁后链入的选择器窗口快照:\n" + probe.snapshot())
        screenshot("ac3-04-unlock-chained-picker")

        val chainedEntry = probe.awaitActiveNode(ENTRY_ITEM_LABEL, ACTIVITY_WAIT_MS) { node ->
            textOf(node).contains(AutofillSeedContract.ENTRY_TITLE)
        }
        assertNotNull(
            "解锁后链入的选择器中未找到播种条目「${AutofillSeedContract.ENTRY_TITLE}」（库未真正解锁？）",
            chainedEntry
        )
        evidence.write("点选播种条目=${probe.click(chainedEntry!!)}")

        // 判据：客户端输入框被真实凭据写入，**且客户端全程未再请求填充**（无补救驱动）
        val filledAfterUnlock = awaitNewLogcatLine(CLIENT_FILLED_TRACE, FILL_RESULT_WAIT_MS)
        evidence.write(
            "解锁后（未经客户端重请求）客户端自述:\n" + filteredLogcat(AutofillClientActivity.TAG).lines()
                .filter { line -> line.contains(CLIENT_STATUS_TRACE) }
                .takeLast(3)
                .joinToString("\n")
        )
        assertEquals(
            "客户端在解锁后又自行请求过填充——归因不干净，无法证明「不靠人工重请求也能填出凭据」",
            clientRequestsBeforeUnlock,
            clientRequestCount()
        )
        assertTrue(
            "解锁后未靠人工重请求，客户端输入框未被写入凭据（未捕获到新的「$CLIENT_FILLED_TRACE」）" +
                "——ISSUE-P2-86 修复未生效",
            filledAfterUnlock
        )
        screenshot("ac3-05-client-filled-after-unlock")

        // (2) 解锁页此时必须正常结束（认证结果已被交付，而非卡死/崩溃）
        val unlockFinished = awaitActivityFinished(UNLOCK_ACTIVITY, ACTIVITY_WAIT_MS)
        evidence.write("生命周期留痕: ${lifecycleTrace.toList()}")
        assertTrue("$UNLOCK_ACTIVITY 未结束（解锁流程卡住或 Activity 未 finish）", unlockFinished)

        // (3) 全程不得出现本应用/本客户端进程的崩溃
        val crashed = hasOwnProcessCrash()
        evidence.write("解锁全程崩溃留痕=${crashed}（框架认证结果留痕见下）")
        evidence.write(
            "框架侧认证结果留痕:\n" + logcatDump().lines()
                .filter { line -> line.contains("onAuthenticationResult") }
                .takeLast(6)
                .joinToString("\n")
        )
        assertFalse("自动填充链路在真机上出现崩溃（FATAL EXCEPTION / ANR）", crashed)

        // (4) 归因测量：客户端不再主动请求时，框架是否**自行**重发 onFillRequest？
        //     ISSUE-P2-86 已定性为**否**（基线 6/6 false）——此处仍做**测量并如实记录**。
        //     两条**独立**证据通道：① 服务侧 debug 留痕；② 框架侧 AutofillSession 会话事件。
        val sessionEventsBefore = frameworkSessionEvents()
        val measureStartedAt = SystemClock.uptimeMillis()
        val frameworkRedispatch = awaitNewLogcatLine(UNLOCKED_DISPATCH_TRACE, REDISPATCH_WAIT_MS)
        val measureElapsedMs = SystemClock.uptimeMillis() - measureStartedAt
        val clientRequestsAfterUnlock = clientRequestCount()
        val sessionEventsDelta = frameworkSessionEvents() - sessionEventsBefore
        evidence.write(
            "框架是否自行重发 onFillRequest=$frameworkRedispatch" +
                "（检测窗口=${REDISPATCH_WAIT_MS}ms，实际耗时=${measureElapsedMs}ms）；" +
                "客户端主动请求次数 解锁前=$clientRequestsBeforeUnlock 解锁后=$clientRequestsAfterUnlock"
        )
        evidence.write(
            "框架侧会话事件新增（${sessionEventsDelta.size} 条）:\n" +
                sessionEventsDelta.joinToString("\n")
        )
        // 客户端字段现状（认证结果是否把值写进了目标输入框——A 的功能面直接观测点）
        SystemClock.sleep(UI_SETTLE_MS)
        val clientStateAfterAuth = probe.awaitText(CLIENT_STATUS_TRACE, 6_000)?.let { textOf(it) }
        evidence.write("解锁链路结束后客户端状态文本=${clientStateAfterAuth ?: "未读取到"}")

        // ---------------------------------------------------------------- 阶段 6（B）：选择器路径真实填充
        evidence.section("阶段 6：端到端填充——选择器显式指认条目，框架写入目标输入框")
        // 重新拉起**全新客户端实例**：阶段 5 已把该实例的输入框填满，若不重开，
        // 本阶段的「新填充留痕」断言会被阶段 5 的旧值顶替（假绿）。
        probe.shell("input keyevent 4")
        SystemClock.sleep(UI_SETTLE_MS)
        targetContext.startActivity(clientIntent())
        val clientForPicker = probe.awaitText(AutofillClientActivity.TITLE, CLIENT_WAIT_MS) != null
        evidence.write("客户端重新拉起=$clientForPicker")
        assertTrue("客户端未能重新拉起，阶段 6 无法取得空表单实例", clientForPicker)
        SystemClock.sleep(UI_SETTLE_MS)

        var pickerItem = probe.awaitText(PICKER_ITEM_TEXT, REQUEST_FILL_WAIT_MS)
        if (pickerItem == null) {
            // 填充 UI 未自动弹出：再显式请求一次（客户端按钮）
            evidence.write("填充 UI 未出现，再显式触发一次客户端请求")
            probe.awaitText(AutofillClientActivity.REQUEST_BUTTON_TEXT, CLIENT_WAIT_MS)?.let { probe.click(it) }
            pickerItem = probe.awaitText(PICKER_ITEM_TEXT, FILL_UI_WAIT_MS)
        }
        assertNotNull("系统填充 UI 未出现选择器入口数据集「$PICKER_ITEM_TEXT」", pickerItem)
        screenshot("ac3-03-unlocked-dispatch")
        evidence.write("点选选择器入口=${probe.click(pickerItem!!)}")
        val pickerLaunched = awaitNewTrace("resumed:$PICKER_ACTIVITY", ACTIVITY_WAIT_MS)
        evidence.write("生命周期留痕: ${lifecycleTrace.toList()}")
        assertTrue("框架未拉起 $PICKER_ACTIVITY", pickerLaunched)
        SystemClock.sleep(UI_SETTLE_MS)
        evidence.write("选择器窗口快照:\n" + probe.snapshot())
        screenshot("ac3-04-picker-attribution")

        val entryItem = probe.awaitActiveNode(ENTRY_ITEM_LABEL, ACTIVITY_WAIT_MS) { node ->
            textOf(node).contains(AutofillSeedContract.ENTRY_TITLE)
        }
        assertNotNull("选择器中未找到播种条目「${AutofillSeedContract.ENTRY_TITLE}」", entryItem)
        evidence.write("点选播种条目=${probe.click(entryItem!!)}")

        val filled = awaitNewLogcatLine(CLIENT_FILLED_TRACE, FILL_RESULT_WAIT_MS)
        evidence.write("客户端自述（logcat）:\n" + filteredLogcat(AutofillClientActivity.TAG))
        assertTrue("客户端输入框未被真实凭据填充（未捕获到新的「$CLIENT_FILLED_TRACE」）", filled)
        SystemClock.sleep(UI_SETTLE_MS)
        screenshot("ac3-05-client-filled")

        // ---------------------------------------------------------------- 阶段 7：已解锁候选 + 二次确认
        evidence.section("阶段 7：已解锁候选数据集 → AutofillConfirmActivity 二次确认")
        // 阶段 6 已在受保护窗口内**显式指认**调用方，即写入「包名 + 签名摘要」首次绑定；
        // 旧填充会话已随阶段 6 填充结束，故重新拉起**全新客户端实例**取得干净会话，
        // 使「android:// 包名维度」在本会话内真正授权。
        probe.shell("input keyevent 4")
        SystemClock.sleep(UI_SETTLE_MS)
        targetContext.startActivity(clientIntent())
        val clientRestarted = probe.awaitText(AutofillClientActivity.TITLE, CLIENT_WAIT_MS) != null
        evidence.write("客户端重新拉起=$clientRestarted")
        assertTrue("客户端未能重新拉起，无法取得干净填充会话", clientRestarted)

        val candidateItem = probe.awaitText(AutofillSeedContract.ENTRY_TITLE, CANDIDATE_WAIT_MS)
        evidence.write(
            "服务侧候选数量留痕:\n" + filteredLogcat(SERVICE_TAG).lines()
                .filter { line -> line.contains("候选数据集数量") }
                .joinToString("\n")
        )
        assertNotNull(
            "系统填充 UI 未出现自动匹配候选「${AutofillSeedContract.ENTRY_TITLE}」——" +
                "即解锁后候选维度未放行（选择器已写入首次绑定，理论上本会话应命中）",
            candidateItem
        )
        screenshot("ac3-06-candidate-dataset")
        evidence.write("点选候选=${probe.click(candidateItem!!)}")
        val confirmLaunched = awaitNewTrace("resumed:$CONFIRM_ACTIVITY", ACTIVITY_WAIT_MS)
        evidence.write("生命周期留痕: ${lifecycleTrace.toList()}")
        assertTrue("框架未拉起 $CONFIRM_ACTIVITY", confirmLaunched)
        SystemClock.sleep(UI_SETTLE_MS)
        evidence.write("确认页窗口快照:\n" + probe.snapshot())
        screenshot("ac3-07-confirm-page")

        // 阶段 6 已写入「首次绑定」⇒ 本页应走「已授权」分支（无勾选框、按钮直接可用）；
        // 若仍出现勾选框，如实记录并显式勾选后继续（不掩盖现象）。
        probe.awaitActiveNode("记住此应用勾选框", ACTIVITY_WAIT_MS) { node -> node.isCheckable }?.let { box ->
            val checked = box.isChecked || probe.click(box)
            evidence.write("确认页出现「记住此应用」勾选框（本次仍按首次出现处理）勾选=$checked")
            SystemClock.sleep(UI_SETTLE_MS)
        }
        val confirmButton = probe.awaitActiveNode(CONFIRM_BUTTON_LABEL, ACTIVITY_WAIT_MS) { node ->
            normalizedText(node).contains(CONFIRM_BUTTON_KEY)
        }
        assertNotNull("确认页未找到「$CONFIRM_BUTTON_KEY」按钮", confirmButton)
        // ISSUE-P2-88 判据基线：**点击前**先记下「客户端自述已填充」的留痕条数，
        // 否则确认后产生的行会被当成基线吞掉（假红）
        val filledLinesBaseline = logcatMatchCount(CLIENT_FILLED_TRACE)
        val confirmRequestsBefore = clientRequestCount()
        evidence.write("点选确认按钮=${probe.click(confirmButton!!)}")

        // A 的契约面（第二个认证 Activity）：确认页以双参 `setResult` 结束，且框架接受该结果、全程无崩溃。
        val confirmSessionBefore = frameworkSessionEvents()
        val confirmFinished = awaitActivityFinished(CONFIRM_ACTIVITY, ACTIVITY_WAIT_MS)
        val crashedAfterConfirm = hasOwnProcessCrash()
        evidence.write("$CONFIRM_ACTIVITY 已结束=$confirmFinished；崩溃留痕=$crashedAfterConfirm")
        evidence.write(
            "确认后框架侧会话事件新增（${(frameworkSessionEvents() - confirmSessionBefore).size} 条）:\n" +
                (frameworkSessionEvents() - confirmSessionBefore).joinToString("\n")
        )
        evidence.write(
            "框架侧认证结果留痕:\n" + logcatDump().lines()
                .filter { line -> line.contains("onAuthenticationResult") }
                .takeLast(6)
                .joinToString("\n")
        )
        assertTrue("$CONFIRM_ACTIVITY 未结束（确认流程卡住）", confirmFinished)
        assertFalse("确认路径出现崩溃（FATAL EXCEPTION / ANR）", crashedAfterConfirm)

        // ISSUE-P2-88 **判据**：确认页回传的真实 Dataset 被框架写入客户端目标输入框。
        // 只认「点击确认之后新增的」客户端自述行——认证页进出会令客户端重新 resume，
        // 若按 contains 判定，阶段 5 / 阶段 6 的旧留痕会造成假绿。
        val confirmFilledLines = awaitNewLogcatLines(CLIENT_FILLED_TRACE, filledLinesBaseline, CONFIRM_FILL_WAIT_MS)
        evidence.write(
            "确认后新增的客户端自述留痕（logcat 原文，共 ${confirmFilledLines.size} 条）:\n" +
                confirmFilledLines.joinToString("\n")
        )
        evidence.write(
            "确认窗口内客户端主动请求次数 ${confirmRequestsBefore} → ${clientRequestCount()}" +
                "（确认路径的写入不得依赖客户端重请求）"
        )
        evidence.write("客户端自述（logcat）:\n" + filteredLogcat(AutofillClientActivity.TAG))
        assertTrue(
            "确认页在真机上**未**把凭据写入客户端输入框（确认后未新增「$CLIENT_FILLED_TRACE」留痕）" +
                "——ISSUE-P2-88 修复未生效",
            confirmFilledLines.isNotEmpty()
        )
        assertTrue(
            "确认后客户端自述为「已填充」但账号不是播种账号——疑为把别处的旧值误判为本次填充",
            confirmFilledLines.any { it.contains("username=[${AutofillSeedContract.USERNAME}]") }
        )
        screenshot("ac3-08-client-filled-after-confirm")

        evidence.section("结论")
        evidence.write(
            "A：认证数据集下发并展示 → 框架拉起 $UNLOCK_ACTIVITY → 真机完成解锁 → 链入 " +
                "$PICKER_ACTIVITY 并原样转发其认证结果，解锁页正常结束=已实测（$unlockFinished）"
        )
        evidence.write(
            "B（ISSUE-P2-86 判据）：解锁后**未经客户端重请求**（请求次数 $clientRequestsBeforeUnlock → " +
                "$clientRequestsAfterUnlock）即由本次认证结果把真实凭据写入客户端输入框=" +
                "$filledAfterUnlock（客户端自述 $CLIENT_FILLED_TRACE）"
        )
        evidence.write(
            "B（已解锁分支）：真实客户端（$clientPackage，独立进程/uid）经选择器路径被真实凭据填充=" +
                "$filled"
        )
        evidence.write(
            "C（ISSUE-P2-88 判据）：框架拉起 $CONFIRM_ACTIVITY，其确认结果携带实时构造的真实 Dataset，" +
                "客户端两个输入框被真实凭据写入（客户端自述 usernameFilled=true passwordFilled=true）=" +
                confirmFilledLines.isNotEmpty()
        )
        evidence.write(
            "归因测量（与既有 KDoc 前提的差异）：框架自行重发 onFillRequest=$frameworkRedispatch" +
                "（客户端全程未再主动请求）；$UNLOCK_ACTIVITY 的原 KDoc 前提「解锁后框架会自动重发」" +
                "已在 ISSUE-P2-86 中纠正"
        )
        evidence.write(
            "未覆盖边界：webDomain 归属路径（本用例客户端为原生应用，不产生 webDomain）"
        )
    }

    // ------------------------------------------------------------------ 断言/观测工具

    /** 客户端启动意图（阶段 2 与阶段 7 复用） */
    private fun clientIntent(): Intent = Intent().apply {
        component = ComponentName(clientPackage, CLIENT_CLASS_NAME)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 设备侧截图（shell 身份直写共享存储，供宿主侧与记录文档取证） */
    private fun screenshot(name: String): String {
        val path = "$SHOT_DIR/$name.png"
        probe.shell("screencap -p $path")
        val listed = probe.shell("ls -l $path").trim()
        evidence.write("截图 $path → ${listed.lines().lastOrNull()?.trim().orEmpty()}")
        return path
    }

    private fun textOf(node: AccessibilityNodeInfo): String = probe.textOf(node)

    private fun normalizedText(node: AccessibilityNodeInfo): String = textOf(node).replace(" ", "")

    private fun awaitActivity(className: String, timeoutMs: Long): Boolean =
        awaitTrace("resumed:$className", timeoutMs)

    /** 认证 Activity 必须正常结束（finish → onDestroy） */
    private fun awaitActivityFinished(className: String, timeoutMs: Long): Boolean =
        awaitTrace("destroyed:$className", timeoutMs)

    private fun awaitTrace(needle: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (lifecycleTrace.contains(needle)) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /**
     * 等待 trace 中出现一条**新增**的指定留痕。
     *
     * 同一 Activity 会在多个阶段被拉起（选择器、确认页均如此），仅判 `contains` 会命中上一阶段的
     * 旧留痕而**假绿**，故按「出现次数较调用前增加」判定。
     */
    private fun awaitNewTrace(needle: String, timeoutMs: Long): Boolean {
        val before = traceCount(needle)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (traceCount(needle) > before) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /** 计数（对同步列表加锁遍历，避免与生命周期回调并发时迭代器失效） */
    private fun traceCount(needle: String): Int = synchronized(lifecycleTrace) {
        lifecycleTrace.count { it == needle }
    }

    /**
     * 轮询直至出现一条**新的**（内容不同于既有）指定留痕。
     *
     * 用于「框架是否在认证结果之后**再次**发起请求」这类计数型判定：仅判 contains 会把
     * 认证前的旧留痕也算命中，从而把「客户端自己又请求了一次」误判为「框架自动重发」。
     */
    private fun awaitNewLogcatLine(needle: String, timeoutMs: Long): Boolean {
        fun matches(): List<String> = logcatDump().lines().filter { it.contains(needle) }
        val before = matches().toSet()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (matches().any { it !in before }) return true
            SystemClock.sleep(POLL_MS * 2)
        }
        return false
    }

    /** 全量 logcat 中包含 [needle] 的行数（用于「点击某动作后是否新增留痕」的基线取值） */
    private fun logcatMatchCount(needle: String): Int =
        logcatDump().lines().count { it.contains(needle) }

    /**
     * 等待「包含 [needle] 的留痕条数比 [baseline] 多」，返回**新增的**那些行。
     *
     * 与 [awaitNewLogcatLine] 的区别：按**条数**判定，故不受「新增行与旧行内容逐字相同」
     * 影响（客户端每次填充的账号/口令值本来就一样，只有 logcat 前缀时间戳不同）。
     * 基线必须在触发动作**之前**取得——认证 Activity 进出会令客户端重新 resume，
     * 若在触发之后取基线，本次填充产生的行会被当成基线而漏判（假红）。
     */
    private fun awaitNewLogcatLines(needle: String, baseline: Int, timeoutMs: Long): List<String> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (logcatMatchCount(needle) > baseline) {
                // 再等一拍，把同一次填充的后续状态行（客户端 ticker 每秒一行）一并收进证据
                SystemClock.sleep(POLL_MS * 2)
                val matches = logcatDump().lines().filter { it.contains(needle) }
                return matches.takeLast(matches.size - baseline)
            }
            SystemClock.sleep(POLL_MS)
        }
        return emptyList()
    }

    /** 客户端主动请求填充的次数（归因用：区分「框架重发」与「客户端又请求」） */
    private fun clientRequestCount(): Int =
        logcatDump().lines().count { it.contains(CLIENT_REQUEST_TRACE) }

    /** 本应用或本测试客户端进程是否留下崩溃/ANR 留痕 */
    private fun hasOwnProcessCrash(): Boolean = logcatDump().lines().any { line ->
        line.contains("FATAL EXCEPTION") ||
            line.contains("Process: com.keepasskey") ||
            line.contains("ANR in com.keepasskey")
    }

    /**
     * 读取 logcat 全量文本（不分 tag 过滤）。
     *
     * **为何不用 `logcat -s TAG:V` / 管道**：`UiAutomation.executeShellCommand` 不保证经 `sh -c`
     * 执行，`&&` / `|` / 重定向可能整体被当作参数而静默失败（本用例 2026-09-16 实测：
     * `cp a b && echo OK` 返回空输出）。故一律「整份 dump + 在 Kotlin 侧过滤」。
     */
    private fun logcatDump(): String = probe.shell("logcat -d -v threadtime")

    /**
     * 完整性扫描留痕取值：**只认本进程 pid 的行**，从而排除 logcat 环形缓冲里
     * 上一次运行残留的同名留痕（陈旧值 = 假绿）。
     */
    private fun awaitIntegrityLevel(timeoutMs: Long): String? {
        val myPid = android.os.Process.myPid().toString()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val level = logcatDump().lineSequence()
                .filter { it.contains("运行完整性扫描完成") && linePid(it) == myPid }
                .mapNotNull { LEVEL_PATTERN.find(it)?.groupValues?.get(1) }
                .lastOrNull()
            if (level != null) return level
            SystemClock.sleep(POLL_MS)
        }
        return null
    }

    /**
     * threadtime 格式第 3 列是 pid；解析失败返回 null（该行即被丢弃）。
     *
     * §204 纠偏：threadtime 的 pid/tid **右对齐 5 位宽**——4 位 pid 前有 2 个空格，
     * `split(" ")` 会把连续空格解析成空元素（getOrNull(2) = ""），只有 5 位 pid 才凑巧
     * 拿到正确列（2026-09-19 实测：真机 pid 5 位时两轮绿、pid 回落 4 位后恒败，
     * 模拟器 pid 恒 4 位故从未绿过）。改为按连续空白切分，两种位宽均正确。
     */
    private fun linePid(line: String): String? =
        line.trim().split(Regex(" +")).getOrNull(2)

    /**
     * 轮询直至锁屏窗口消失。
     *
     * SystemUI 的锁屏窗口即使在屏幕熄灭（Doze）时也留在窗口树里，此时任何 Activity 都无法
     * 真正进入前台——真机实测（2026-09-16）即因设备处于锁屏态导致客户端窗口始终不出现。
     */
    private fun awaitKeyguardGone(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val keyguardPresent = probe.collect().any { node ->
                node.viewIdResourceName?.contains("keyguard_root_view") == true
            }
            if (!keyguardPresent) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    /** 指定 tag 的 logcat 留痕（Kotlin 侧过滤，避免管道/`-s` 过滤在本机不可靠） */
    private fun filteredLogcat(tag: String, limit: Int = 80): String = logcatDump().lineSequence()
        .filter { it.contains(" $tag:") || it.contains(" $tag :") }
        .toList()
        .takeLast(limit)
        .joinToString("\n")

    /**
     * 框架侧自动填充会话事件集合（独立于服务侧留痕的第二条证据通道）。
     *
     * `AutofillSession: createPendingIntent` / `onShown` 只在框架**收到带认证的 FillResponse**
     * 并展示填充 UI 时出现，故「解锁后是否新增会话事件」可交叉验证「框架是否真的重发过请求」——
     * 若服务侧留痕缺失而框架侧却有新增事件，说明是检测手段漏检，而非框架未重发。
     */
    private fun frameworkSessionEvents(): Set<String> = logcatDump().lines()
        .filter { line -> line.contains("AutofillSession") || line.contains("RemoteFillService") }
        .toSet()

    /** 框架侧自动填充留痕（失败诊断用；同样在 Kotlin 侧过滤，避免管道） */
    private fun frameworkLogcat(): String = logcatDump().lineSequence()
        .filter { it.contains("utofill") }
        .toList()
        .takeLast(60)
        .joinToString("\n")

    /** 证据记录：同时落 logcat 与文件（文件在收尾时另存到共享存储，避免随卸载丢失） */
    private inner class Evidence(private val context: Context) {
        private val file: File? = runCatching {
            File(context.getExternalFilesDir(null) ?: context.filesDir, EVIDENCE_FILE).also {
                it.writeText("")
            }
        }.getOrNull()

        fun section(title: String) = write("\n===== $title =====")

        fun write(line: String) {
            val stamped = "[${System.currentTimeMillis()}] $line"
            Log.i(EVIDENCE_TAG, stamped)
            runCatching { file?.appendText(stamped + "\n") }
        }

        fun publish(directory: String): String {
            val source = file?.absolutePath ?: return "另存失败: 本进程证据文件不可写"
            val target = "$directory/$EVIDENCE_FILE"
            // 逐条命令执行：executeShellCommand 不保证经 sh -c，`&&` 会被当作参数整体失败
            val copied = probe.shell("cp $source $target")
            probe.shell("chmod 644 $target")
            val listed = probe.shell("ls -l $target").trim()
            return if (listed.contains(EVIDENCE_FILE)) target else "另存失败: $copied${listed}"
        }

        fun flushToLog() {
            Log.i(EVIDENCE_TAG, "证据文件: ${file?.absolutePath ?: "不可写"}")
        }
    }

    private companion object {
        const val EVIDENCE_TAG = "AutofillAC3"
        const val EVIDENCE_FILE = "autofill-auth-chain-evidence.txt"
        const val SERVICE_TAG = "KeePasskeyAutofill"
        const val CLIENT_CLASS_NAME = "com.keepasskey.app.autofill.AutofillClientActivity"

        const val UNLOCK_ACTIVITY = "com.keepasskey.app.autofill.AutofillUnlockActivity"
        const val PICKER_ACTIVITY = "com.keepasskey.app.autofill.AutofillPickerActivity"
        const val CONFIRM_ACTIVITY = "com.keepasskey.app.autofill.AutofillConfirmActivity"

        /** 认证引导数据集文案（`R.string.cred_autofill_unlock_prompt`） */
        const val UNLOCK_ITEM_TEXT = "解锁 KeePasskey"

        /** 选择器入口文案（`R.string.autofill_picker_entry_title`） */
        const val PICKER_ITEM_TEXT = "搜索全部条目"

        /** 解锁按钮文案（`R.string.unlock_btn_unlock` 含空格，比较前先归一化） */
        const val UNLOCK_BUTTON_KEY = "解锁密码库"

        /** 二次确认按钮文案（`R.string.autofill_confirm_ok`） */
        const val CONFIRM_BUTTON_KEY = "确认填充"

        const val UNLOCK_BUTTON_LABEL = "解锁按钮"
        const val PASSWORD_FIELD_LABEL = "解锁页主密码框"
        const val CONFIRM_BUTTON_LABEL = "确认按钮"
        const val ENTRY_ITEM_LABEL = "选择器条目"

        /** 服务侧 debug 留痕：解锁后下发已解锁分支数据集（见 KeePasskeyAutofillService） */
        const val UNLOCKED_DISPATCH_TRACE = "onFillRequest 下发已解锁数据集"

        /** 客户端自述：目标输入框已被真实凭据填充 */
        const val CLIENT_FILLED_TRACE = "passwordFilled=true"

        /** 客户端自述：主动调用 requestAutofill（归因「框架重发 vs 客户端请求」的关键留痕） */
        const val CLIENT_REQUEST_TRACE = "requestAutofill("

        /** 客户端界面状态文本前缀（其内容含 username / passwordFilled 等字段现状） */
        const val CLIENT_STATUS_TRACE = AutofillClientActivity.STATUS_PREFIX

        val LEVEL_PATTERN = Regex("运行完整性扫描完成: level=(\\w+)")

        const val POLL_MS = 200L
        const val UI_SETTLE_MS = 1_200L
        const val CLIENT_WAIT_MS = 15_000L
        const val FILL_UI_WAIT_MS = 25_000L
        // §204 纠偏：原 15s 窗口 < 完整性周期重扫间隔（LIVE_RESCAN_INTERVAL_MS = 30s，安全参数不得
        // 放宽）——阶段 0 的 logcat -c 已把进程启动时那拍留痕清掉，等待只能依赖**下一拍周期重扫**；
        // 窗口 < 周期时相位错过即恒等不到（2026-09-19 真机/模拟器同时现形，此前真机两轮绿系相位命中）。
        // 改为 > 一个完整周期（35s），保证窗口内必有一拍；断言强度不变（仍要求 level 非空且非 COMPROMISED）。
        const val INTEGRITY_WAIT_MS = 35_000L
        const val KEYGUARD_WAIT_MS = 10_000L
        const val REQUEST_FILL_WAIT_MS = 12_000L
        const val ACTIVITY_WAIT_MS = 15_000L

        /** 解锁成功后链入选择器的等待上限（含解锁 KDF 耗时，真机为低端设备故放宽） */
        const val CHAIN_WAIT_MS = 30_000L
        const val FILL_RESULT_WAIT_MS = 20_000L
        const val CANDIDATE_WAIT_MS = 20_000L

        /** 归因测量窗口：客户端不请求的前提下，框架自行重发的等待上限 */
        const val REDISPATCH_WAIT_MS = 25_000L

        /** 确认路径的写入观测窗口（ISSUE-P2-88 判据；客户端 ticker 每秒一行，8 s 足够） */
        const val CONFIRM_FILL_WAIT_MS = 8_000L

        /** 设备侧截图输出目录（shell 身份可写） */
        const val SHOT_DIR = "/sdcard/Download"
    }
}
