package com.keepasskey.app.autofill

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.R
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ENG-20261028-05`：**自动填充通道**「数据集已呈现后关闭开关再确认」的设备用例。
 *
 * ## 这项在证明什么
 *
 * 服务侧在 `onFillRequest` 时求值一次 `isAutofillServiceEnabled`，但数据集一旦交给系统，
 * 用户仍可能在点击**之前**到设置页关掉通道。真正把凭据写进目标表单的是
 * [AutofillConfirmActivity] 的认证结果回传，故该页必须在交付边界按**本次**开关值拒绝。
 *
 * 时序（全部为真实系统链路，不调用生产私有方法）：
 * 1. 播种并解锁合成库 → 登记本应用为 `autofill_service`；
 * 2. 测试 APK 的独立客户端（另一个包 / uid）真实 `requestAutofill`，系统弹出候选数据集；
 * 3. **数据集已呈现**后，把生产开关 `autofillServiceEnabled` 置 false（等价于用户去设置页关闭）；
 * 4. 点选数据集 → 生产确认页 → 用户确认；
 * 5. 判据：`AutofillConfirmActivity` 以 `RESULT_CANCELED` 结束，客户端两个输入框始终未被填充。
 *
 * ## 前提与边界
 *
 * - 设备无已录入的强认证器：确认走受保护窗口内手动确认（与 `AutofillAuthChainDeviceTest`
 *   同一前提）；首次出现的调用方需勾选「记住此应用」后才可确认。
 * - 不修改测试客户端（Java），不改服务侧既有入口判定；本用例只覆盖**交付边界**。
 */
@RunWith(AndroidJUnit4::class)
class AutofillChannelSwitchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val clientPackage = instrumentation.context.packageName
    private val probe = DeviceAutofillProbe()
    private val application = targetContext.applicationContext as Application

    private lateinit var settings: ExtendedSettingsStore
    private lateinit var originalSettings: ExtendedSettings
    private var originalAutofillService: String? = null
    private lateinit var isolation: AutofillClientRunIsolation
    private lateinit var selection: AutofillVaultSelection
    private var selectionSnapshot: AutofillVaultSelection.Snapshot? = null
    private var seededVault: File? = null

    /** 生产确认页的 resultCode 留痕（由 ActivityLifecycleCallbacks 在销毁时读取）。 */
    private val confirmResults = Collections.synchronizedList(mutableListOf<Int>())

    /** 生产认证 Activity 的到达 / 结束留痕（按类名，用于「框架是否真的拉起 / 是否收尾」判据）。 */
    private val arrivals = Collections.synchronizedList(mutableListOf<String>())
    private val finished = Collections.synchronizedList(mutableListOf<String>())

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity.javaClass.simpleName in AuthActivities) arrivals += activity.javaClass.simpleName
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (activity.javaClass.simpleName in AuthActivities) finished += activity.javaClass.simpleName
            if (activity is AutofillConfirmActivity) {
                confirmResults += Activity::class.java.getDeclaredField("mResultCode")
                    .apply { isAccessible = true }.getInt(activity)
            }
        }
    }

    @Before
    fun setUp() {
        probe.requireAutomation()
        probe.prepare()
        settings = ExtendedSettingsStore(targetContext)
        originalSettings = settings.load()
        originalAutofillService = probe.shell("settings get secure autofill_service")
            .trim().takeUnless { it.isEmpty() || it == "null" }
        isolation = AutofillClientRunIsolation(probe) { println("ENG05_AUTOFILL $it") }
        isolation.prepare()
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    @After
    fun tearDown() {
        runCatching { isolation.cleanup() }
        application.unregisterActivityLifecycleCallbacks(callbacks)
        runCatching {
            val restore = originalAutofillService
            if (restore == null) probe.shell("settings delete secure autofill_service")
            else probe.shell("settings put secure autofill_service $restore")
        }
        runCatching { settings.save(originalSettings) }
        runCatching {
            selectionSnapshot?.let { if (::selection.isInitialized) selection.restore(it) }
        }
        runCatching {
            seededVault?.let { AutofillTestVaultSeeder.deleteOwned(it, AutofillSeedContract.MASTER_PASSWORD_TEXT) }
        }
    }

    @Test
    fun `已呈现数据集后关闭通道_确认后不得填充`() {
        // 1) 播种 + 选中 + 解锁：候选数据集的前提
        val vault = AutofillTestVaultSeeder.seed(targetContext, clientPackage)
        seededVault = vault
        selection = AutofillVaultSelection(targetContext)
        selectionSnapshot = selection.select(vault.name)
        assertEquals("播种库必须成为活动库", vault.name, selection.activeId())
        val master = AutofillSeedContract.MASTER_PASSWORD_TEXT.toCharArray()
        val unlocked = try {
            runBlocking {
                EntryPointAccessors.fromApplication(
                    targetContext.applicationContext,
                    AutofillDeviceGraph::class.java
                ).vaultRepository().unlockActiveDatabase(master)
            }
        } finally {
            master.fill('\u0000')
        }
        assertTrue("合成库必须能解锁：$unlocked", unlocked.isSuccess)

        authorizeCallerPackageDimension()

        // 2) 登记本应用为系统填充服务（收尾还原）
        val service = ComponentName(
            targetContext.packageName,
            KeePasskeyAutofillService::class.java.name
        ).flattenToShortString()
        probe.shell("settings put secure autofill_service $service")
        assertEquals("系统必须接受本次填充服务登记", service, probe.shell("settings get secure autofill_service").trim())

        // 3) 独立客户端发起真实填充请求，系统呈现候选数据集
        isolation.startFresh("已呈现数据集")
        var candidate = probe.awaitDatasetText(
            targetContext.packageName,
            AutofillSeedContract.USERNAME,
            CandidateWaitMs
        )
        if (candidate == null) {
            probe.awaitAppText(clientPackage, AutofillClientActivity.REQUEST_BUTTON_TEXT, ClientWaitMs)
                ?.let { probe.click(it) }
            candidate = probe.awaitDatasetText(
                targetContext.packageName,
                AutofillSeedContract.USERNAME,
                CandidateWaitMs
            )
        }
        assertNotNull("系统必须呈现候选数据集（数据集已呈现的锚点）；窗口=${probe.snapshot()}", candidate)

        // 4) 数据集已呈现后关闭通道，等价于用户此时去设置页关闭「系统自动填充服务」
        settings.save(settings.load().copy(autofillServiceEnabled = false))
        assertTrue("已呈现的数据集必须可点选", probe.click(requireNotNull(candidate)))

        // 5) 生产确认页 → 用户确认 → 交付边界必须拒绝
        acknowledgeConfirmPageAndConfirm()
        awaitConfirmResult(Activity.RESULT_CANCELED)
        assertClientNotFilled(FillWindowMs)
    }

    @Test
    fun `初始关闭_无数据集与入口_开启后才有候选`() {
        prepareVault(unlock = true)
        // 已授权包名维度 + 库已解锁 ⇒ 开启通道时应出现**凭据候选**数据集（正对照的判别面）
        authorizeCallerPackageDimension()
        registerService()
        settings.save(settings.load().copy(autofillServiceEnabled = false))

        // 关闭态：真实 requestAutofill 之后，系统填充 UI 里既无候选数据集，也无解锁引导数据集
        probe.shell("logcat -c")
        isolation.startFresh("初始关闭")
        assertNull(
            "通道关闭时不得出现候选数据集；窗口=${probe.snapshot()}",
            probe.awaitDatasetText(
                targetContext.packageName,
                AutofillSeedContract.USERNAME,
                NegativeFillWindowMs
            )
        )
        assertNull(
            "通道关闭时不得出现解锁引导数据集",
            probe.awaitDatasetText(
                targetContext.packageName,
                targetContext.getString(R.string.cred_autofill_unlock_prompt),
                NegativeFillWindowMs
            )
        )
        assertNull(
            "通道关闭时不得出现选择器入口数据集",
            probe.awaitDatasetText(
                targetContext.packageName,
                targetContext.getString(R.string.autofill_picker_entry_title),
                NegativeFillWindowMs
            )
        )
        assertTrue(
            "通道关闭时不得拉起任何生产认证 Activity；arrivals=$arrivals",
            arrivals.isEmpty()
        )
        // 服务侧短路留痕仅作**佐证**，数据集「不存在」的判据是上面两条面板读数
        val serviceLog = probe.shell("logcat -d -s ${KeePasskeyAutofillService.TAG}")
        println(
            "ENG05_AUTOFILL initialClosed arrivals=$arrivals " +
                "shortCircuit=${serviceLog.contains(ServiceClosedMarker)}"
        )
        assertTrue(
            "服务侧必须留下通道关闭的短路留痕；log=$serviceLog",
            serviceLog.contains(ServiceClosedMarker)
        )

        // 正对照：同一库、同一调用方（已授权、库已解锁），重新开启后必须出现凭据候选数据集
        // ——否则上面的 null 只是「本装置从来就出不来数据集」
        settings.save(settings.load().copy(autofillServiceEnabled = true))
        isolation.startFresh("正对照开启")
        val candidate = probe.awaitDatasetText(
            targetContext.packageName,
            AutofillSeedContract.USERNAME,
            CandidateWaitMs
        )
        assertNotNull("开启通道后必须出现凭据候选数据集；窗口=${probe.snapshot()}", candidate)
        // 收起下拉，不进行填充（本条只证明「有/无数据集」）
        probe.shell("input keyevent 4")
    }

    @Test
    fun `解锁页挂起后关闭_解锁并点选条目也不得填充`() {
        prepareVault(unlock = false)
        registerService()
        settings.save(settings.load().copy(autofillServiceEnabled = true))

        // 锁定态：系统必须下发认证引导数据集
        isolation.startFresh("锁定态")
        val unlockItem = probe.awaitDatasetText(
            targetContext.packageName,
            targetContext.getString(R.string.cred_autofill_unlock_prompt),
            CandidateWaitMs
        )
        assertNotNull("锁定态必须出现解锁引导数据集；窗口=${probe.snapshot()}", unlockItem)
        assertTrue("解锁引导数据集必须可点选", probe.click(requireNotNull(unlockItem)))
        awaitArrival(UnlockActivityName)
        assertUnlockPageVisible()

        // 解锁页已挂起 ⇒ 此时关闭通道
        settings.save(settings.load().copy(autofillServiceEnabled = false))
        unlockThroughUi()

        // 解锁成功 ⇒ 未绑定调用方无强匹配候选，路由到选择器；点选条目必须被交付闸门拒绝
        awaitArrival(PickerActivityName)
        tapSeededEntryInPicker()
        awaitFinished(PickerActivityName)
        assertClientNotFilled(FillWindowMs)
    }

    // ── 驱动辅助 ────────────────────────────────────────────────────────────────

    private fun graph(): AutofillDeviceGraph = EntryPointAccessors.fromApplication(
        targetContext.applicationContext,
        AutofillDeviceGraph::class.java
    )

    /** 播种合成库并选中为活动库；[unlock] = false 时保留锁定态（用于解锁引导数据集）。 */
    private fun prepareVault(unlock: Boolean) {
        val vault = AutofillTestVaultSeeder.seed(targetContext, clientPackage)
        seededVault = vault
        selection = AutofillVaultSelection(targetContext)
        selectionSnapshot = selection.select(vault.name)
        assertEquals("播种库必须成为活动库", vault.name, selection.activeId())
        if (!unlock) return
        val master = AutofillSeedContract.MASTER_PASSWORD_TEXT.toCharArray()
        val unlocked = try {
            runBlocking { graph().vaultRepository().unlockActiveDatabase(master) }
        } finally {
            master.fill('\u0000')
        }
        assertTrue("合成库必须能解锁：$unlocked", unlocked.isSuccess)
    }

    private fun registerService() {
        val service = ComponentName(
            targetContext.packageName,
            KeePasskeyAutofillService::class.java.name
        ).flattenToShortString()
        probe.shell("settings put secure autofill_service $service")
        assertEquals(
            "系统必须接受本次填充服务登记",
            service,
            probe.shell("settings get secure autofill_service").trim()
        )
    }

    private fun awaitArrival(simpleName: String) {
        val deadline = SystemClock.uptimeMillis() + ConfirmWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (arrivals.contains(simpleName)) return
            SystemClock.sleep(200L)
        }
        fail("$simpleName 必须被系统真实拉起；arrivals=$arrivals；窗口=${probe.snapshot()}")
    }

    private fun awaitFinished(simpleName: String) {
        val deadline = SystemClock.uptimeMillis() + ConfirmWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (finished.contains(simpleName)) return
            SystemClock.sleep(200L)
        }
        fail("$simpleName 必须收尾；finished=$finished；窗口=${probe.snapshot()}")
    }

    /** 解锁页已挂起的锚点：按包名 + 可编辑 + 密码语义定位主密码框。 */
    private fun assertUnlockPageVisible() {
        val field = probe.awaitActiveNode("自动填充解锁页主密码框", ConfirmWaitMs) { node ->
            AutofillNodeIdentity.appNode(node, targetContext.packageName) &&
                node.isEditable && node.isPassword
        }
        assertNotNull("解锁页必须显示主密码输入框；窗口=${probe.snapshot()}", field)
    }

    /** 在真实解锁页输入主密码并点「解锁」（与 `AutofillAuthChainDeviceTest` 同法）。 */
    private fun unlockThroughUi() {
        val field = probe.awaitActiveNode("自动填充解锁页主密码框", ConfirmWaitMs) { node ->
            AutofillNodeIdentity.appNode(node, targetContext.packageName) &&
                node.isEditable && node.isPassword
        }
        assertNotNull("解锁页必须显示主密码输入框；窗口=${probe.snapshot()}", field)
        val master = AutofillSeedContract.MASTER_PASSWORD_TEXT
        if (!probe.setText(requireNotNull(field), master)) {
            probe.tapNode(requireNotNull(field))
            SystemClock.sleep(300L)
            probe.shell("input text $master")
        }
        SystemClock.sleep(300L)
        val buttonLabel = targetContext.getString(R.string.unlock_btn_unlock)
        val button = probe.awaitActiveNode("解锁按钮", ConfirmWaitMs) { node ->
            AutofillNodeIdentity.appButton(node, targetContext.packageName, buttonLabel)
        }
        assertNotNull("解锁页必须显示解锁按钮；窗口=${probe.snapshot()}", button)
        assertTrue("解锁按钮必须可点", probe.click(requireNotNull(button)))
    }

    /** 在选择器里点选播种条目（用户显式指认），触发生产交付闸门。 */
    private fun tapSeededEntryInPicker() {
        val entry = probe.awaitActiveNode("选择器中的播种条目", ConfirmWaitMs) { node ->
            AutofillNodeIdentity.appNode(node, targetContext.packageName) &&
                probe.textOf(node).lines().any {
                    AutofillNodeIdentity.normalize(it) ==
                        AutofillNodeIdentity.normalize(AutofillSeedContract.ENTRY_TITLE)
                }
        }
        assertNotNull("选择器必须列出播种条目；窗口=${probe.snapshot()}", entry)
        assertTrue("播种条目必须可点选", probe.click(requireNotNull(entry)))
    }

    /**
     * 客户端输入框在观察窗口内**始终**未被写入。
     *
     * 必须在窗口内至少读到一次客户端字段状态（`AC3-STATUS ... usernameFilled=false`）——
     * 读不到即判失败，不以「没看到填充」冒充「未被填充」。
     */
    private fun assertClientNotFilled(windowMs: Long) {
        val deadline = SystemClock.uptimeMillis() + windowMs
        var observed: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val status = probe.awaitClientStatus(
                clientPackage,
                AutofillClientActivity.STATUS_PREFIX,
                500L
            )?.let { probe.textOf(it) }
            if (status != null) {
                observed = status
                if (status.contains("usernameFilled=true") || status.contains("passwordFilled=true")) {
                    fail("客户端输入框不得被填充；status=$status")
                }
            }
            SystemClock.sleep(200L)
        }
        assertNotNull("必须能读到客户端字段状态（否则本条无观测面）；窗口=${probe.snapshot()}", observed)
        println("ENG05_AUTOFILL clientNotFilled status=$observed")
    }

    /**
     * 前置：该调用方已在本应用获得**包名维度授权**（例如用户此前保存过凭据）。
     *
     * 授权本身不是各用例的观测对象，但它是「已呈现候选数据集」的必要前提——未授权时生产侧
     * 只给出选择器入口，不会摆出候选。授权记录由 [AutofillClientRunIsolation] 收尾还原。
     */
    private fun authorizeCallerPackageDimension() {
        val digests = clientCertDigests()
        assertTrue("必须能读取合成客户端的证书摘要", digests.values.isNotEmpty())
        val trustStore = AutofillCallerTrustStore(targetContext)
        assertTrue(
            "合成调用方的包名维度授权必须写入成功",
            trustStore.trust(clientPackage, digests.primary)
        )
        assertTrue(
            "信任记录必须立即可读回（否则说明写入了非生产信任面）；digestLen=${digests.primary?.length}",
            trustStore.isTrusted(clientPackage, digests)
        )
        println("ENG05_AUTOFILL trustWritten pkg=$clientPackage digests=${digests.values.size}")
    }

    /** 合成客户端自身的签名摘要（生产信任存储的键口径：大写十六进制、无冒号）。 */
    private fun clientCertDigests(): CallerCertDigests {
        val info = targetContext.packageManager.getPackageInfo(
            clientPackage,
            android.content.pm.PackageManager.PackageInfoFlags.of(
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            )
        )
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().toList()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return CallerCertDigests.of(
            signers.map { signer ->
                digest.digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
            }
        )
    }

    /** 真实确认页：首次调用方需先勾选「记住此应用」，再点确认。 */
    private fun acknowledgeConfirmPageAndConfirm() {
        probe.awaitActiveNode("记住此应用勾选框", ConfirmWaitMs) { node ->
            AutofillNodeIdentity.appNode(node, targetContext.packageName) && node.isCheckable
        }?.let { box ->
            if (!box.isChecked) assertTrue("「记住此应用」勾选框必须可勾选", probe.click(box))
            SystemClock.sleep(300L)
        }
        val label = targetContext.getString(R.string.autofill_confirm_ok)
        val button = probe.awaitActiveNode("自动填充确认按钮", ConfirmWaitMs) { node ->
            AutofillNodeIdentity.appButton(node, targetContext.packageName, label)
        }
        assertNotNull("生产确认页必须出现；窗口=${probe.snapshot()}", button)
        assertTrue("确认按钮必须可点", probe.click(requireNotNull(button)))
    }

    private fun awaitConfirmResult(code: Int) {
        val deadline = SystemClock.uptimeMillis() + ConfirmWaitMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (confirmResults.contains(code)) return
            SystemClock.sleep(200L)
        }
        fail(
            "AutofillConfirmActivity 必须以 resultCode=$code 结束；实际=$confirmResults；" +
                "窗口=${probe.snapshot()}"
        )
    }


    private companion object {
        const val CandidateWaitMs = 20_000L
        const val ClientWaitMs = 10_000L
        const val ConfirmWaitMs = 15_000L
        const val FillWindowMs = 3_000L

        /** 「不得出现数据集」的观察窗口：足够覆盖框架拉起填充 UI 的正常时延。 */
        const val NegativeFillWindowMs = 3_000L

        const val UnlockActivityName = "AutofillUnlockActivity"
        const val PickerActivityName = "AutofillPickerActivity"
        const val ConfirmActivityName = "AutofillConfirmActivity"

        /** 生产认证三页——到达/结束留痕只认这三个，不把客户端或系统 UI 计进来。 */
        val AuthActivities = setOf(UnlockActivityName, PickerActivityName, ConfirmActivityName)

        /** `KeePasskeyAutofillService` 在通道关闭时的短路留痕（仅作佐证）。 */
        const val ServiceClosedMarker = "自动填充通道已关闭"
    }
}
