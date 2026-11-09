package com.keepasskey.app.passkey

import android.app.Activity
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.app.R
import com.keepasskey.app.autofill.AutofillNodeIdentity
import com.keepasskey.core.model.PasskeyData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ENG-20261028-05`：通道开关在**真实系统流程**里的时序矩阵（设备侧）。
 *
 * ## 这项在证明什么
 *
 * 缺陷形态不是「判定函数算错」，而是「判定只在请求进入时求值，挂起中的流程继续交付」。
 * 因此每个用例都必须让**系统真的把请求推进到挂起点**，再改写生产开关的持久化值
 * （等价于用户到设置页关闭通道），然后观察生产落地页是否仍交付：
 *
 * | 时序 | 密码（填充） | 公钥（断言） | 保存（口令新建） | 公钥（注册） |
 * |---|---|---|---|---|
 * | 初始关闭 | `初始关闭_…` | `初始关闭_…` | `初始关闭_…` | `初始关闭_…` |
 * | 候选已生成后关闭 | `候选已生成后关闭_密码填充…` | `候选已生成后关闭_公钥断言…` | `候选已生成后关闭_密码保存…` | （注册见「用户验证挂起」一例） |
 * | 链式解锁挂起后关闭 | 复用公钥链（同一 `CredentialUnlockActivity`） | `链式解锁挂起后关闭_公钥请求…` | `链式解锁挂起后关闭_密码保存…` | — |
 * | 用户验证挂起后关闭 | `用户验证挂起后关闭_密码填充…` | `用户验证挂起后关闭_公钥断言…` | — | `用户验证挂起后关闭_通行密钥注册…` |
 *
 * 另有两类**作用域对照**（关闭条件明写「不得用公钥开关屏蔽密码请求」）：
 * - `单关通行密钥开关_公钥候选消失但密码填充照旧交付`：同一轮里公钥 prepare=false、
 *   密码 prepare=true 且真实交付 `result:a:A`；
 * - `正对照_锁库保存解锁后真实落库`：证明「保存链路本身能成」，否则负向用例的「没有落库」
 *   可能只是链路压根没跑通。
 *
 * ## 装置
 *
 * 复用 [CmSessionMatrixFixture]（A/B 两库、同一 UUID、不同密钥与计数）与两个既有 Java 客户端：
 * - `CredentialSaveClientActivity`：公钥断言（`MODE_GET_PASSKEY` / `…_PREPARE`）与创建
 *   （`MODE_PASSKEY` / 缺省口令保存）；
 * - `CredentialBrowserGetClientActivity`：**浏览器委派**的真口令 get（prepare + get），
 *   其回执 `result:a:A` / `result:a:cancelled:<type>` 是「是否真的把本库口令交出去」的独立读数。
 *
 * ## 前提（不满足即应视为环境问题，而非应用缺陷）
 *
 * 1. 设备无已录入的强认证器：生产用户验证走受保护窗口内的**手动确认**（与本目录既有
 *    `CmSessionMatrixDeviceTest` 同一前提）；若设备有生物识别，确认页会被系统弹窗替代，
 *    本类对 `passkey_confirm_ok` / `autofill_confirm_ok` 的定位会超时。
 * 2. `settings secure credential_service` 由 fixture 自行登记并在收尾还原。
 * 3. 浏览器用例由 fixture 把测试 APK 加入特权浏览器白名单（收尾还原）。
 *
 * ## 不做的事
 *
 * 不修改测试客户端（Java）、不直接调用生产私有方法、不以纯判定函数替代系统流程；
 * Autofill 通道的同类时序不在本类（见交付说明的未覆盖清单）。
 */
@RunWith(AndroidJUnit4::class)
class CredentialChannelSwitchDeviceTest {

    private val fixture = CmSessionMatrixFixture()

    @After
    fun tearDown() {
        fixture.close()
    }

    // ── 初始关闭 ────────────────────────────────────────────────────────────────

    @Test
    fun `初始关闭_总开关关闭时公钥与密码与保存都不产出候选或入口`() {
        fixture.prepare()

        // 正对照：开关开启时同一 prepare 读数必须为 true，否则下面的 false 不具判别力
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(allowed = fixture.credentialIds.getValue("A"))
        )
        assertEquals(
            "开关开启时系统必须发布公钥候选（正对照，证明 prepare 读数可分辨）",
            "prepared:passkey=true",
            fixture.awaitStatus("prepared:passkey=")
        )

        // 关闭 CM 通道总开关 ⇒ 公钥候选不再发布
        fixture.setChannelSwitches(credentialProviderEnabled = false)
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(allowed = fixture.credentialIds.getValue("A"))
        )
        assertEquals(
            "CM 总开关关闭后系统不得再发布公钥候选",
            "prepared:passkey=false",
            fixture.awaitStatus("prepared:passkey=")
        )

        // 口令保存入口：provider 返回空创建响应 ⇒ 系统直接以「无可创建选项」收尾
        val entriesBefore = fixture.entryCount()
        fixture.client("password")
        assertEquals(
            "总开关关闭后口令创建必须以错误收尾（不得回传 onResult）",
            "onError",
            fixture.awaitStatus("onError:").substringBefore(':')
        )
        fixture.assertNoArrival(PasswordSaveActivity::class.java)
        assertEquals("总开关关闭后不得写入任何条目", entriesBefore, fixture.entryCount())

        // 公钥注册入口：provider 不产出条目时，CM 只展示它自己的「在另一台设备上创建」选项
        // （实测文案 `Create passkey on another device?`，其中账号来自请求 JSON，非本 provider），
        // 且**不回调客户端**。判据：面板里不得出现本 provider 的创建条目文案，也不得拉起注册页。
        fixture.client(CredentialSaveClientActivity.MODE_PASSKEY)
        assertNoProviderCreateEntry()
        fixture.assertNoArrival(PasskeyCreateActivity::class.java)
        // 显式取消系统面板（不把等待超时当通过）
        fixture.dismissSystemSheetAndAwaitGone()
        // 设备实测：无创建条目时 CM 只展示它自己的「在另一台设备上创建」选项，不回调客户端；
        // 用户显式取消后才以 `onError:TYPE_USER_CANCELED` 收尾。
        val registrationStatus = fixture.awaitStatus("onError:")
        println("ENG05_INITIAL_CLOSED_REGISTRATION status=$registrationStatus")
        assertTrue(
            "通道关闭后注册请求必须以错误收尾；status=$registrationStatus",
            registrationStatus.startsWith("onError:")
        )

        // 口令填充（浏览器委派）：prepare 读数必须为 false
        assertTrue("测试 APK 必须能被加入特权浏览器白名单", fixture.enableTestBrowser())
        fixture.browserClient(
            CredentialBrowserGetClientActivity.ACTION_PREPARE_A,
            CmSessionMatrixFixture.BROWSER_ORIGIN_A
        )
        assertEquals(
            "总开关关闭后系统不得发布口令候选",
            "${fixture.browserPreparePrefix()}password=false",
            fixture.awaitBrowserStatus(fixture.browserPreparePrefix())
        )
    }

    @Test
    fun `单关通行密钥开关_公钥候选消失但密码填充照旧交付`() {
        fixture.prepare()
        assertTrue("测试 APK 必须能被加入特权浏览器白名单", fixture.enableTestBrowser())

        // 作用域对照①：只关「通行密钥支持」⇒ 公钥候选消失
        fixture.setChannelSwitches(passkeySupportEnabled = false)
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(allowed = fixture.credentialIds.getValue("A"))
        )
        assertEquals(
            "关闭通行密钥后公钥候选必须消失",
            "prepared:passkey=false",
            fixture.awaitStatus("prepared:passkey=")
        )

        // 作用域对照②：同一轮里口令通道必须照旧可用——候选发布 + 真实交付
        fixture.browserClient(
            CredentialBrowserGetClientActivity.ACTION_PREPARE_A,
            CmSessionMatrixFixture.BROWSER_ORIGIN_A
        )
        assertEquals(
            "关闭通行密钥不得屏蔽口令候选（公钥开关只收敛公钥通道）",
            "${fixture.browserPreparePrefix()}password=true",
            fixture.awaitBrowserStatus(fixture.browserPreparePrefix())
        )
        fixture.browserClient(CredentialBrowserGetClientActivity.ACTION_GET_A)
        fixture.tapCandidate(
            CmSessionMatrixFixture.BROWSER_USER_A,
            fixture.awaitCandidate(CmSessionMatrixFixture.BROWSER_USER_A)
        )
        fixture.confirmSystemIfPresent()
        confirmCredentialFill()
        assertEquals(
            "关闭通行密钥后口令仍必须真实交付；status=${fixture.browserStatus()}",
            "${fixture.browserResultPrefix()}A",
            fixture.awaitBrowserStatus(fixture.browserResultPrefix())
        )
    }

    // ── 候选已生成后关闭 ─────────────────────────────────────────────────────────

    @Test
    fun `候选已生成后关闭_公钥断言必须拒签且计数不变`() {
        fixture.prepare()
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        val row = fixture.awaitCandidate("cm-matrix-A")

        // 系统已把候选摆到用户面前之后才关闭通道
        fixture.setChannelSwitches(passkeySupportEnabled = false)
        fixture.tapCandidate("cm-matrix-A", row)

        fixture.awaitResultPumpingSystemConfirm(
            PasskeyAssertionActivity::class.java,
            Activity.RESULT_CANCELED
        )
        // 生产页取消后 CM 会重新展示候选面板并等用户操作；显式关闭后请求才终止。
        fixture.dismissSystemSheetAndAwaitGone()
        assertEquals("拒签不得推进 A 库签名计数器", 17, fixture.count(fixture.a))
        val assertionStatus = fixture.awaitStatus("get:onError")
        assertTrue(
            "客户端必须以用户取消收尾而非收到断言；status=$assertionStatus",
            assertionStatus.startsWith("get:onError")
        )
    }

    @Test
    fun `候选已生成后关闭_密码填充必须取消且不回传`() {
        fixture.prepare()
        assertTrue("测试 APK 必须能被加入特权浏览器白名单", fixture.enableTestBrowser())
        fixture.browserClient(
            CredentialBrowserGetClientActivity.ACTION_PREPARE_A,
            CmSessionMatrixFixture.BROWSER_ORIGIN_A
        )
        assertEquals(
            "前置：口令候选必须已发布",
            "${fixture.browserPreparePrefix()}password=true",
            fixture.awaitBrowserStatus(fixture.browserPreparePrefix())
        )
        fixture.browserClient(CredentialBrowserGetClientActivity.ACTION_GET_A)
        val row = fixture.awaitCandidate(CmSessionMatrixFixture.BROWSER_USER_A)

        fixture.setChannelSwitches(credentialProviderEnabled = false)
        fixture.tapCandidate(CmSessionMatrixFixture.BROWSER_USER_A, row)
        fixture.confirmSystemIfPresent()

        fixture.awaitResultPumpingSystemConfirm(PasswordFillActivity::class.java, Activity.RESULT_CANCELED)
        assertTrue(
            "系统必须真实拉起生产填充页（否则本条只是「没进入流程」）",
            fixture.arrivals.any { it is PasswordFillActivity }
        )
        assertTrue(
            "通道关闭后不得进入确认页（确认页即「准备回传明文」）",
            passwordConfirmButton(NegativeConfirmProbeWindowMs) == null
        )
        // 设备实测（AVD 5554）：生产页取消后 CM 会**重新展示候选面板**且不回调客户端
        // （客户端停在 `selecting:get-a`）。故必须由用户显式取消，再核对客户端从未收到口令。
        fixture.dismissSystemSheetAndAwaitGone()
        val result = fixture.awaitBrowserStatus(fixture.browserResultPrefix())
        println("ENG05_FILL_CLOSED_AFTER_CANCEL status=$result")
        assertTrue(
            "显式取消后客户端必须以用户取消收尾（绝不含口令）；status=$result",
            result.startsWith("${fixture.browserResultPrefix()}cancelled:")
        )
    }

    @Test
    fun `候选已生成后关闭_密码保存必须取消且不落库`() {
        fixture.prepare()
        val entriesBefore = fixture.entryCount()
        fixture.lock()
        fixture.client("password")
        val createRow = awaitCreateEntryRowOrNull()

        fixture.setChannelSwitches(credentialProviderEnabled = false)
        if (createRow != null) {
            assertTrue("系统保存面板的创建条目必须可点选", fixture.probe.click(createRow))
        } else if (unlockPageVisible(UnlockPageProbeWindowMs)) {
            // 回退时序：系统对单一 provider 直接拉起落地页（无面板），锁库下停在解锁页。
            // 此时关闭通道后再解锁，仍在**落库前**的开关判定处被拒——判据与上面一致。
            fixture.unlockThroughUi()
        }

        fixture.awaitResultPumpingSystemConfirm(PasswordSaveActivity::class.java, Activity.RESULT_CANCELED)
        assertTrue(
            "系统必须真实拉起生产保存页（否则本条只是「没进入流程」）",
            fixture.arrivals.any { it is PasswordSaveActivity }
        )
        fixture.unlockActiveIfLocked()
        assertFalse(
            "通道关闭后调用方口令不得落库",
            fixture.hasUserName(ClientFixedUserName)
        )
        assertEquals("通道关闭后条目数不得变化", entriesBefore, fixture.entryCount())
    }

    // ── 链式解锁挂起后关闭 ───────────────────────────────────────────────────────

    @Test
    fun `链式解锁挂起后关闭_公钥请求必须回空响应且不签发`() {
        fixture.prepare()
        fixture.lock()
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        openUnlockActionIfPresent()
        fixture.awaitArrival(CredentialUnlockActivity::class.java)
        assertUnlockPageVisible()

        // 解锁页已挂起（用户还没输入主密码）——此时关闭通道
        fixture.setChannelSwitches(credentialProviderEnabled = false)
        fixture.unlockThroughUi()

        // 设备实测（2026-11-08，AVD 5554）：空 `BeginGetCredentialResponse` 不会让系统回调客户端——
        // CM 把 `Unlock KeePasskey to fill` + `No sign-in info` 留在面板上等用户操作，客户端保持
        // `requesting:get-passkey`。故本条不得以「等 onError」冒充通过，判据分两步：
        // ① 面板内不得出现任何凭据候选，也不得拉起断言页（关通道即不交付）；
        // ② 由用户显式取消面板，取消后客户端仍不得收到任何断言结果。
        assertNoCandidateInSheet("cm-matrix-A")
        println("ENG05_CHAIN_UNLOCK_CLOSED sheetVisible=${fixture.systemSheetVisible()} status=${fixture.status()}")
        fixture.dismissSystemSheetAndAwaitGone()
        // 设备实测（AVD 5554，2026-11-08）：空响应不会让系统**直接**回调，面板停在
        // `Unlock KeePasskey to fill / No sign-in info`；由用户显式取消后客户端才以
        // `get:onError:TYPE_USER_CANCELED` 收尾——绝无凭据交付。
        val chainStatus = fixture.awaitStatus("get:onError")
        assertTrue("链式响应不得交付任何凭据；status=$chainStatus", chainStatus.startsWith("get:onError"))
        fixture.assertNoArrival(PasskeyAssertionActivity::class.java, windowMs = 1_000L)
        assertEquals("链式响应不得签发任何断言", 17, fixture.count(fixture.a))
    }

    @Test
    fun `链式解锁挂起后关闭_密码保存必须取消且不落库`() {
        fixture.prepare()
        val entriesBefore = fixture.entryCount()
        fixture.lock()
        fixture.client("password")
        awaitCreateEntryRowOrNull()?.let { assertTrue("系统保存面板的创建条目必须可点选", fixture.probe.click(it)) }
        fixture.awaitArrival(PasswordSaveActivity::class.java)
        assertUnlockPageVisible()

        fixture.setChannelSwitches(credentialProviderEnabled = false)
        fixture.unlockThroughUi()

        fixture.awaitResult(PasswordSaveActivity::class.java, Activity.RESULT_CANCELED)
        fixture.unlockActiveIfLocked()
        assertFalse(
            "解锁挂起期间关闭通道后调用方口令不得落库",
            fixture.hasUserName(ClientFixedUserName)
        )
        assertEquals("解锁挂起期间关闭通道后条目数不得变化", entriesBefore, fixture.entryCount())
    }

    // ── 用户验证挂起后关闭 ───────────────────────────────────────────────────────

    @Test
    fun `用户验证挂起后关闭_公钥断言必须拒签且计数不变`() {
        fixture.prepare()
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        fixture.select("cm-matrix-A")
        // 生产验证确认页已出现 = 用户验证挂起
        fixture.awaitConfirmation(PasskeyAssertionActivity::class.java)

        fixture.setChannelSwitches(passkeySupportEnabled = false)
        fixture.appButton(R.string.passkey_confirm_ok)

        fixture.awaitResult(PasskeyAssertionActivity::class.java, Activity.RESULT_CANCELED)
        fixture.dismissSystemSheetAndAwaitGone()
        assertEquals("验证后关闭通道不得推进签名计数器", 17, fixture.count(fixture.a))
        val verifyStatus = fixture.awaitStatus("get:onError")
        assertTrue(
            "验证期间关闭通道后客户端必须以用户取消收尾；status=$verifyStatus",
            verifyStatus.startsWith("get:onError")
        )
    }

    @Test
    fun `用户验证挂起后关闭_密码填充必须取消且不回传`() {
        fixture.prepare()
        assertTrue("测试 APK 必须能被加入特权浏览器白名单", fixture.enableTestBrowser())
        fixture.browserClient(
            CredentialBrowserGetClientActivity.ACTION_PREPARE_A,
            CmSessionMatrixFixture.BROWSER_ORIGIN_A
        )
        assertEquals(
            "前置：口令候选必须已发布",
            "${fixture.browserPreparePrefix()}password=true",
            fixture.awaitBrowserStatus(fixture.browserPreparePrefix())
        )
        fixture.browserClient(CredentialBrowserGetClientActivity.ACTION_GET_A)
        fixture.tapCandidate(
            CmSessionMatrixFixture.BROWSER_USER_A,
            fixture.awaitCandidate(CmSessionMatrixFixture.BROWSER_USER_A)
        )
        fixture.confirmSystemIfPresent()
        // 生产确认页已出现 = 用户验证挂起
        val confirm = passwordConfirmButton()
        assertNotNull("生产填充页必须显示确认按钮（用户验证挂起）；窗口=${fixture.probe.snapshot()}", confirm)

        fixture.setChannelSwitches(credentialProviderEnabled = false)
        assertTrue("确认按钮必须可点", fixture.probe.click(requireNotNull(confirm)))

        fixture.awaitResultPumpingSystemConfirm(PasswordFillActivity::class.java, Activity.RESULT_CANCELED)
        // 生产页取消后 CM 重新展示候选面板且不回调客户端（同「候选已生成后关闭」一例）；
        // 由用户显式取消后才以 TYPE_USER_CANCELED 收尾——绝不交付口令。
        fixture.dismissSystemSheetAndAwaitGone()
        val result = fixture.awaitBrowserStatus(fixture.browserResultPrefix())
        println("ENG05_FILL_VERIFY_CLOSED status=$result")
        assertTrue(
            "验证挂起期间关闭通道后客户端必须以用户取消收尾（绝不含口令）；status=$result",
            result.startsWith("${fixture.browserResultPrefix()}cancelled:")
        )
    }

    @Test
    fun `用户验证挂起后关闭_通行密钥注册必须拒绝且不落库`() {
        fixture.prepare()
        fixture.client(CredentialSaveClientActivity.MODE_PASSKEY)
        awaitCreateEntryRowOrNull()?.let {
            assertTrue("系统创建面板的通行密钥条目必须可点选", fixture.probe.click(it))
        }
        fixture.awaitConfirmation(PasskeyCreateActivity::class.java)

        fixture.setChannelSwitches(passkeySupportEnabled = false)
        fixture.appButton(R.string.passkey_confirm_ok)

        // 注册链的通道拒绝走**可见原因页**（CredentialRejectionReason.CHANNEL_DISABLED）：
        // 必须由用户确认后才收尾，不点确认就等 resultCode 会整轮超时。
        val reason = fixture.context.getString(R.string.passkey_reject_channel_disabled)
        val rejection = fixture.probe.awaitActiveNode("通道关闭拒绝页", CmSessionMatrixFixture.TIMEOUT) { node ->
            AutofillNodeIdentity.appNode(node, fixture.context.packageName) &&
                AutofillNodeIdentity.exactText(node, reason)
        }
        assertNotNull("注册链必须如实呈现「通道已关闭」原因；窗口=${fixture.probe.snapshot()}", rejection)
        val acknowledge = fixture.probe.awaitActiveNode("拒绝页确认按钮", CmSessionMatrixFixture.TIMEOUT) { node ->
            AutofillNodeIdentity.appButton(
                node,
                fixture.context.packageName,
                fixture.context.getString(R.string.cred_reject_confirm)
            )
        }
        assertNotNull("拒绝页必须显示确认按钮；窗口=${fixture.probe.snapshot()}", acknowledge)
        assertTrue("拒绝页确认必须可点", fixture.probe.click(requireNotNull(acknowledge)))

        fixture.awaitResult(PasskeyCreateActivity::class.java, Activity.RESULT_CANCELED)
        assertFalse(
            "验证后关闭通行密钥不得登记新凭据",
            livePasskeyUserNames().contains(ClientFixedUserName)
        )
        assertFalse(
            "客户端不得收到注册材料；status=${fixture.status()}",
            fixture.status().startsWith("onResult")
        )
    }

    // ── 正对照 ─────────────────────────────────────────────────────────────────

    @Test
    fun `正对照_锁库保存解锁后真实落库`() {
        fixture.prepare()
        assertFalse(
            "前置：库内不得已有该调用方的口令条目",
            fixture.hasUserName(ClientFixedUserName)
        )
        fixture.lock()
        fixture.client("password")
        awaitCreateEntryRowOrNull()?.let { assertTrue("系统保存面板的创建条目必须可点选", fixture.probe.click(it)) }
        fixture.awaitArrival(PasswordSaveActivity::class.java)
        assertUnlockPageVisible()

        fixture.unlockThroughUi()

        fixture.awaitResult(PasswordSaveActivity::class.java, Activity.RESULT_OK)
        // 判据取「按调用方账号可读回」而不是条目数：`saveAutofillCredential` 对
        // 「包名绑定且 user 为空的既有条目」走**更新**分支（本库的公钥条目就是这种形态），
        // 条目数可能不变。账号可读回才是「口令真的落库」的判据。
        println("ENG05_SAVE_POSITIVE count=${fixture.entryCount()} user=${ClientFixedUserName}")
        assertTrue(
            "开关开启时保存链路必须真实落库（按调用方账号可读回）",
            fixture.hasUserName(ClientFixedUserName)
        )
    }

    // ── 驱动辅助 ────────────────────────────────────────────────────────────────

    /** 生产密码填充页的确认按钮（用户验证挂起的锚点）；不在活动窗口内则返回 null。 */
    private fun passwordConfirmButton(windowMs: Long = ConfirmProbeWindowMs): AccessibilityNodeInfo? =
        fixture.probe.awaitActiveNode("密码填充确认页", windowMs) { node ->
            AutofillNodeIdentity.appButton(
                node,
                fixture.context.packageName,
                fixture.context.getString(R.string.autofill_confirm_ok)
            )
        }

    private fun confirmCredentialFill() {
        val button = passwordConfirmButton()
        assertNotNull("生产密码填充页必须显示确认按钮；窗口=${fixture.probe.snapshot()}", button)
        assertTrue("确认按钮必须可点", fixture.probe.click(requireNotNull(button)))
    }

    /** 解锁页（`UnlockScreen`）主密码框是否已出现；[windowMs] 为观察窗口。 */
    private fun unlockPageVisible(windowMs: Long): Boolean =
        fixture.probe.awaitActiveNode("解锁页主密码框", windowMs) { node ->
            AutofillNodeIdentity.appNode(node, fixture.context.packageName) &&
                node.isEditable && node.isPassword
        } != null

    /** 解锁页已挂起的锚点（失败即该条时序不成立）。 */
    private fun assertUnlockPageVisible() {
        assertTrue(
            "解锁页必须显示主密码输入框；窗口=${fixture.probe.snapshot()}",
            unlockPageVisible(CmSessionMatrixFixture.TIMEOUT)
        )
    }

    /**
     * 系统创建面板里本 provider 的创建条目行；**没有面板时返回 null**。
     *
     * 系统对「单一 provider」可能直接拉起落地页而不呈现面板，两种时序对交付判据等价：
     * 调用方把 null 当作「已经/即将直接拉起」，后续仍以生产 Activity 的到达与 resultCode 断言。
     */
    private fun awaitCreateEntryRowOrNull(windowMs: Long = CreateEntryWindowMs): AccessibilityNodeInfo? {
        val passwordTitle = fixture.context.getString(R.string.cred_create_entry_title)
        return fixture.probe.awaitNode("系统创建面板条目", windowMs) { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@awaitNode false
            val text = fixture.probe.textOf(node)
            text.contains(passwordTitle) || text.contains(ClientFixedUserName)
        }
    }

    /**
     * 锁库后 CM 只回「解锁 Action」：系统面板里点它进入链式解锁页。
     *
     * 部分系统版本会直接拉起落地页（无需点选），故点不到时交给紧随其后的
     * [CmSessionMatrixFixture.awaitArrival] 判定——那里的超时快照才是失败证据。
     */
    private fun openUnlockActionIfPresent() {
        val action = fixture.probe.awaitActiveNode("CM 解锁 Action", UnlockActionWindowMs) { node ->
            node.packageName?.toString() == CmSessionMatrixFixture.CM_PACKAGE &&
                fixture.probe.textOf(node).contains(fixture.context.getString(R.string.cred_unlock_action_title))
        }
        action?.let { fixture.probe.click(it) }
    }

    /**
     * 系统 CM 面板内**不得**出现该文本（凭据候选 / 本 provider 创建条目）。
     *
     * 通道关闭后系统可能把「无凭据」状态留在面板上而不回调客户端；本判据直接读**面板内容**，
     * 是「没有凭据被摆到用户面前」的独立证据，不依赖客户端回调。
     */
    private fun assertNoSheetText(label: String, description: String) {
        val hit = fixture.probe.awaitNode(description, SheetProbeWindowMs) { node ->
            node.packageName?.toString() == CmSessionMatrixFixture.CM_PACKAGE &&
                fixture.probe.textOf(node).contains(label)
        }
        assertNull(
            "$description：面板不得出现「$label」；窗口=${fixture.probe.snapshot()}",
            hit
        )
    }

    /** 断言面板内没有该条目的候选行（用户名即候选行标题）。 */
    private fun assertNoCandidateInSheet(userName: String) =
        assertNoSheetText(userName, "通道关闭后不得出现凭据候选")

    /** 断言面板内没有本 provider 的创建条目（`cred_create_entry_title` 为创建条目标题）。 */
    private fun assertNoProviderCreateEntry() =
        assertNoSheetText(fixture.context.getString(R.string.cred_create_entry_title), "通道关闭时不得提供创建入口")

    /** 活动库内现存通行密钥条目的 userName 集合（只读非敏感字段用于「是否落库」判据）。 */
    private fun livePasskeyUserNames(): Set<String> =
        fixture.session.databaseFlow.value!!.rootGroup.allEntries()
            .mapNotNull { PasskeyData.fromCustomFields(it.customFields)?.userName }
            .toSet()

    private companion object {
        const val ConfirmProbeWindowMs = 12_000L
        const val UnlockActionWindowMs = 5_000L

        /** 负向判据（「不得出现确认页」）的观察窗口：足够让被拉起的生产页完成拒绝收尾。 */
        const val NegativeConfirmProbeWindowMs = 1_500L

        /** 等待系统创建面板（或直接拉起落地页）的观察窗口。 */
        const val CreateEntryWindowMs = 6_000L

        /** 判断「系统是否已直接拉起解锁页」的观察窗口。 */
        const val UnlockPageProbeWindowMs = 2_000L

        /** 读系统 CM 面板内容（负向判据）的观察窗口。 */
        const val SheetProbeWindowMs = 3_000L

        /**
         * `CredentialSaveClientActivity` 的虚构固定账号：口令保存的 `TEST_USERNAME` 与公钥创建
         * 请求 JSON 的 `user.name` 同为该值（该 Java 客户端的两处常量不可引用）。
         */
        const val ClientFixedUserName = "cm-probe-user"
    }
}
