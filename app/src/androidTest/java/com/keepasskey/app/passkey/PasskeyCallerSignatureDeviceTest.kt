package com.keepasskey.app.passkey

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ENG-20261028-06`：**真实 Credential Manager 候选 → 签发**的调用方签名授权矩阵。
 *
 * ## 这项在证明什么
 *
 * 缺陷形态：原生调用（`android:apk-key-hash:` origin）没有可推导的 RP，签发侧只比「条目
 * `android://<包名>` == 调用包名」就放行，于是「包名相同」被当成了授权——显式 RP 与缺失 RP
 * 都是如此。本矩阵要证明的是**系统真实路由**下的裁决结果，而不是纯函数：
 *
 * | # | 调用方签名登记 | 请求 RP | 期望签发 | 期望呈现 |
 * |---|---|---|---|---|
 * | 1 | 已登记**本次真实证书** | 显式且相等 | `RESULT_OK` + 计数递增 | — |
 * | 2 | 已登记本次真实证书 | 缺失 | `RESULT_OK` + 计数递增 | — |
 * | 3 | 未登记 | 显式且相等 | 首次 `RESULT_CANCELED` + 计数不变；**授权后重发成功签发并递增计数** | 原因 + **登记**动作，登记后真实落盘并重发签发 |
 * | 4 | 未登记 | 缺失 | 首次 `RESULT_CANCELED` + 计数不变；**授权后重发成功签发并递增计数** | 同上 |
 * | 5 | 已登记**另一份真实证书**（同包名异签名） | 显式且相等 | 候选发布，签发侧拒签（`RESULT_CANCELED` + 计数不变） | 原因 + **无**登记动作，且不写本次签名 |
 * | 6 | 已登记另一份真实证书 | 缺失 | 候选层直接拦截（零候选，系统返回 `TYPE_NO_CREDENTIAL`），计数不变 | 无候选发布，不拉起任何生产页面 |
 * | 7 | 任意 | 显式且**不相等** | 候选不发布（签发侧另有同一判据） | — |
 *
 * ## 装置
 *
 * 复用 [CmSessionMatrixFixture]：真实客户端 `CredentialSaveClientActivity` 向系统发起
 * `getCredential`，系统把请求路由到**生产** `KeePasskeyCredentialProviderService`；
 * 候选由用户（用例）在系统面板点选，随后进入生产 `PasskeyAssertionActivity`。
 * 签名登记写入**真实** `CredentialManagerCallerTrustStore`（真实 `SharedPreferences`），
 * 摘要一律取自 `PackageManager` 的**真实证书**（本次调用方证书，或设备上另一个真实应用的证书）。
 *
 * 拒绝发生在**用户验证之前**（授权裁决在复制私钥的同一读租约内），因此拒绝路径不会出现
 * 生产确认页——用例据 `RESULT_CANCELED` + 拒绝页 + 计数不变三项读数判定。
 *
 * ## 边界（如实声明）
 *
 * - 「同包名异签名」在设备上以**存储里登记另一份真实证书**构造，而不是真的安装一个与测试
 *   APK 同包名、不同签名的 APK：后者要求卸载测试包本身（instrumentation 会随之退出），
 *   无法在同一单次 instrumentation 进程内完成。故本矩阵证明「签发侧对已登记签名的比对与拒绝」，
 *   不证明「系统如何把另一个已安装应用的请求送进来」——后者由 CM 自身的包名/签名背书承担。
 * - 真实侧载两阶段合成客户端复用方案（不冒充已执行该真侧载验证）：
 *   阶段一：独立合成客户端 APK-Key1 安装并由用户完成首次签名授权绑定；
 *   阶段二：卸载 APK-Key1（保留 KeePasskey 库与信任存储数据），侧载安装同包名但异签名
 *   APK-Key2 并发起请求，验证系统背书的异签名被签发侧判定为 MISMATCH 并拒签。
 * - 缺 RP 请求由客户端 `request(rp = null)` 构造：系统不改写 `requestJson`，故这确实是
 *   「原生调用没有 rpId」的形态。
 * - 只断言「是否签发 / 是否呈现 / 计数是否变化」，不断言系统面板的视觉细节。
 * - 浏览器委派路径（web origin）不在本矩阵内：它不参评签名绑定，由 `PasskeyRpBindingDeviceTest`
 *   与 `CredentialChannelSwitchDeviceTest` 的浏览器用例覆盖。
 */
@RunWith(AndroidJUnit4::class)
class PasskeyCallerSignatureDeviceTest {

    private val fixture = CmSessionMatrixFixture()

    @After
    fun tearDown() {
        fixture.close()
    }

    /** 点选候选后走完生产确认页与用户确认，断言以 `RESULT_OK` 结束且计数递增到 18。 */
    private fun assertSigned() {
        fixture.select("cm-matrix-A")
        fixture.awaitConfirmation(PasskeyAssertionActivity::class.java)
        fixture.appButton(R.string.passkey_confirm_ok)
        fixture.awaitResult(PasskeyAssertionActivity::class.java, Activity.RESULT_OK)
        assertEquals("授权成立时必须真实签发并推进计数", 18, fixture.count(fixture.a))
    }

    /**
     * 点选候选后断言「拒签」：出现拒绝页、计数不变。
     *
     * 拒绝在用户验证之前发生，故此处等的是拒绝页而不是生产确认页。
     */
    private fun assertRejected() {
        fixture.select("cm-matrix-A")
        fixture.awaitRejectionPagePumpingConfirm()
        assertEquals("拒签不得推进签名计数器", 17, fixture.count(fixture.a))
    }

    /** 点拒绝页上的按钮并按 `RESULT_CANCELED` 收尾（对系统契约不变）。 */
    private fun dismissRejection(buttonRes: Int) {
        fixture.appButton(buttonRes)
        fixture.awaitResult(PasskeyAssertionActivity::class.java, Activity.RESULT_CANCELED)
        fixture.dismissSystemSheetAndAwaitGone()
    }

    // ── 1/2：已登记本次真实证书 ⇒ 显式 RP 与缺失 RP 都应签发 ──────────────

    @Test
    fun `已登记真实签名_显式RP匹配时经真实CM签发并递增计数`() {
        fixture.prepare()
        assertTrue("前置：夹具必须已登记调用方真实签名", fixture.callerBoundToRealSignature())

        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        assertSigned()
    }

    @Test
    fun `已登记真实签名_缺失RP时同样签发`() {
        fixture.prepare()
        assertTrue("前置：夹具必须已登记调用方真实签名", fixture.callerBoundToRealSignature())

        // 缺 RP 的原生请求：包名维度回退仍需签名参照（本行是「有参照」的正对照）
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request(rp = null))
        assertSigned()
    }

    // ── 3/4：未登记 ⇒ 候选仍列出，签发拒绝并给出登记入口 ──────────────────

    @Test
    fun `未登记签名_显式RP匹配时候选可见但拒签且提供登记入口`() {
        fixture.prepare()
        fixture.unbindClientCaller()
        assertFalse("前置：本用例必须处于未登记状态", fixture.callerBound())

        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        // 候选仍必须发布：收紧即零候选，用户就失去授权入口（见 assertRejected 内的点选）
        assertRejected()
        // 拒绝页必须给出「登记此应用签名」，否则存量条目在 CM 通道无路可走
        fixture.appButton(R.string.cred_reject_action_authorize_caller)
        // 登记写入真实存储后，拒绝页转为「已登记」并只剩退出
        dismissRejection(R.string.cred_reject_exit)
        assertTrue("就地登记必须真实落盘（写入的是本次系统背书的真实签名）", fixture.callerBoundToRealSignature())

        // 授权后重新发起请求：已登记签名，应能成功签发并推进计数
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        assertSigned()
    }

    @Test
    fun `未登记签名_缺失RP时同样拒签且提供登记入口`() {
        fixture.prepare()
        fixture.unbindClientCaller()

        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request(rp = null))
        assertRejected()
        fixture.appButton(R.string.cred_reject_action_authorize_caller)
        dismissRejection(R.string.cred_reject_exit)
        assertTrue(fixture.callerBoundToRealSignature())

        // 授权后重新发起请求：已登记签名，缺失 RP 同样应能成功签发并推进计数
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request(rp = null))
        assertSigned()
    }

    // ── 5/6：已登记另一份真实证书（同包名异签名）⇒ 拒签且**无**登记入口 ──

    @Test
    fun `已登记异签名_显式RP匹配时拒签且不提供重绑入口`() {
        fixture.prepare()
        assertTrue(
            "前置：必须能把调用方登记到另一份真实证书上",
            fixture.bindClientCallerToOtherRealCertificate()
        )
        assertFalse("前置：本次真实签名必须不在登记集合内", fixture.callerBoundToRealSignature())

        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        assertRejected()
        fixture.assertNoAppText(R.string.cred_reject_action_authorize_caller)
        dismissRejection(R.string.cred_reject_confirm)
        assertFalse(
            "拒绝页不得把本次签名写入存储（否则顶替者自证身份）",
            fixture.callerBoundToRealSignature()
        )
    }

    @Test
    fun `已登记异签名_缺失RP时候选层直接拦截且系统返回无凭据`() {
        fixture.prepare()
        assertTrue(
            "前置：必须能把调用方登记到另一份真实证书上",
            fixture.bindClientCallerToOtherRealCertificate()
        )
        assertFalse("前置：本次真实签名必须不在登记集合内", fixture.callerBoundToRealSignature())

        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request(rp = null))
        val status = fixture.awaitStatus("get:onError:")
        assertTrue(
            "缺RP且异签名时候选层直接拦截（零候选），系统返回无可用凭据；status=$status",
            status.contains("TYPE_NO_CREDENTIAL")
        )
        fixture.assertNoArrival(PasskeyAssertionActivity::class.java)
        assertEquals("零候选不得推进签名计数器", 17, fixture.count(fixture.a))
        assertFalse(
            "存储中不得登记本次真实签名",
            fixture.callerBoundToRealSignature()
        )
    }

    // ── 7：显式 RP 不相等 ⇒ 候选层就不发布（签发侧另有拒绝） ─────────────

    @Test
    fun `请求RP与凭据RP不等时不发布候选_与签名登记状态无关`() {
        fixture.prepare()

        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(rp = "other.example")
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "显式 RP 不匹配时不得发布候选（签发侧另有同一判据）；status=$status",
            "prepared:passkey=false",
            status
        )
    }
}
