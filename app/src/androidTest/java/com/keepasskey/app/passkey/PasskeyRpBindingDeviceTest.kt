package com.keepasskey.app.passkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P3-642`：**真实 framework Credential Manager** 上的 RP 绑定正反对照。
 *
 * ## 为什么必须设备侧
 *
 * 该问题的判据不是「匹配函数写了什么」，而是**系统真的会不会把别域的通行密钥摆进候选**：
 * 候选由 `CredentialResponseAssembler` 依据请求 JSON 里的 `rpId` / `allowCredentials` 收敛，
 * 请求来自系统（`BeginGetPublicKeyCredentialOption`），中间隔着 framework 的 Bundle 编解码。
 * 只有让系统真实下发请求、由**生产** `KeePasskeyCredentialProviderService` 应答，
 * 才能证明「RP-B 的请求拿不到 RP-A 的凭据」这条结论。
 *
 * ## 装置
 *
 * 复用 [CmSessionMatrixFixture]：真实 HTTPS origin 的客户端 Activity
 * （`CredentialSaveClientActivity`）向系统 `CredentialManager.prepareGetCredential` 发起
 * **public-key** 请求；`prepare` 的 `hasCredentialResults(TYPE_PUBLIC_KEY_CREDENTIAL)`
 * 直接回答「系统这一侧有没有可呈现的公钥候选」，无需驱动选择器 UI。
 *
 * ## 边界
 *
 * - 只证明「候选是否发布」这一层，不驱动选择器点选与签名（签名前的 RP 复校另由
 *   `PasskeyAssertionActivity` 承担，`PasskeyRequestRpBindingTest` 覆盖其判据）。
 * - 本机（API 36 隔离 AVD）`cmd credential` 无实现，故不经 ADB 构造请求；
 *   请求一律由真实客户端 Activity 发起。
 */
@RunWith(AndroidJUnit4::class)
class PasskeyRpBindingDeviceTest {

    private val fixture = CmSessionMatrixFixture()

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `请求RP与凭据RP精确匹配时发布公钥候选`() {
        fixture.prepare()
        // 正对照：请求 example.com，凭据也绑定 example.com
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE, fixture.request(rp = CmSessionMatrixFixture.RP))
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "RP 精确匹配时必须发布公钥候选（系统侧读数）；status=$status",
            "prepared:passkey=true",
            status
        )
    }

    @Test
    fun `请求RP不匹配时不得发布公钥候选`() {
        fixture.prepare()
        // 反对照：请求 other.example（凭据绑定 example.com），空 allowCredentials
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(rp = "other.example")
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "RP 不匹配时不得发布公钥候选——否则用户会选中一个必然被 RP 拒绝的凭据；status=$status",
            "prepared:passkey=false",
            status
        )
    }

    @Test
    fun `allowCredentials不含该凭据时不得发布公钥候选`() {
        fixture.prepare()
        // RP 匹配，但 allowCredentials 指向另一条（不存在的）凭据 id
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(rp = CmSessionMatrixFixture.RP, allowed = "bm90LWEtcmVhbC1jcmVkZW50aWFs")
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "allowCredentials 未包含该凭据时不得发布候选（用户选中后 RP 必然拒绝）；status=$status",
            "prepared:passkey=false",
            status
        )
    }

    @Test
    fun `allowCredentials命中该凭据时发布公钥候选`() {
        fixture.prepare()
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(rp = CmSessionMatrixFixture.RP, allowed = fixture.credentialIds.getValue("A"))
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "allowCredentials 命中该凭据时必须发布候选；status=$status",
            "prepared:passkey=true",
            status
        )
    }

    @Test
    fun `请求缺RP时按已绑定包名回退并发布候选`() {
        fixture.prepare()
        // PD-77：仅 RP 缺失才允许包名维度回退；凭据 URL 为 android://<测试客户端包名>
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(rp = null)
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "缺 RP 时应按已绑定包名回退（PD-77），候选必须发布；status=$status",
            "prepared:passkey=true",
            status
        )
    }

    @Test
    fun `父域请求不得命中子域凭据`() {
        fixture.prepare()
        // 反对照：请求父域 example.com 的子域形态不成立时（凭据绑定 example.com，
        // 请求 sub.example.com）必须不发布——精确相等口径，不做子域放宽。
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(rp = "sub.example.com")
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "请求 RP 与凭据 RP 必须归一化后精确相等，子域不得放宽；status=$status",
            "prepared:passkey=false",
            status
        )
        assertTrue("夹具必须已登记候选凭据 id", fixture.credentialIds.containsKey("A"))
    }
}
