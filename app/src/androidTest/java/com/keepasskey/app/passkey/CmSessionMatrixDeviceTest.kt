package com.keepasskey.app.passkey

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-641`：真实 framework Credential Manager 上的**库代次上下文**矩阵。
 *
 * ## 这项在证明什么
 *
 * 四类 CM 入口（断言 / 创建 / 密码保存 / 草稿）在构造时登记了 `databaseId` + 会话票据。
 * 缺陷形态是：登记的是 A 库，用户却在系统选择器停住时切到了 B 库——旧入口若照旧执行，
 * 就会拿 B 库里**同 UUID** 的条目去签名 / 计数 / 落盘，造成账户与库上下文混淆。
 *
 * ## 装置与判据
 *
 * 复用 [CmSessionMatrixFixture]：A / B 两个隔离 KDBX 使用**同一个条目 UUID**、**不同密钥**、
 * **不同起始计数**，因此「签的是哪一本库」可由三件事同时判别：
 * 1. 断言里的 `credentialId`（A/B 各一，互不相同）；
 * 2. 独立验签（`tools/passkey-interop/verify_assertion.py`）用**A 的公钥**必须验签通过、
 *    用 B 的公钥必须失败——这是真正的外部实现对拍，不是本仓自证；
 * 3. 两本库各自的 `signCount` 落盘读数。
 *
 * ## 边界
 *
 * 本用例驱动真实系统选择器与真实生产 Activity，但**不**模拟「用户在验证窗口内切换库」这一
 * 人为时序（该分支由 `CredentialSessionGateDeviceTest` 的门控用例覆盖）；
 * 也不覆盖系统进程重建后旧 token 重放（注册表缺失即拒绝，同由门控用例覆盖）。
 */
@RunWith(AndroidJUnit4::class)
class CmSessionMatrixDeviceTest {

    private val fixture = CmSessionMatrixFixture()

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `A库断言经真实CM签发_A公钥验签通过且计数按A递增`() {
        fixture.prepare()
        val origin = fixture.nativeOrigin()
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        fixture.select("cm-matrix-A")
        fixture.awaitConfirmation(PasskeyAssertionActivity::class.java)
        fixture.appButton(com.keepasskey.app.R.string.passkey_confirm_ok)
        fixture.awaitResult(PasskeyAssertionActivity::class.java, android.app.Activity.RESULT_OK)
        fixture.exportAssertion("A1", origin, count = 18)
        // A 库计数必须已从 17 递增到 18；B 库必须完全没动
        assertEquals("A 库计数必须递增", 18, fixture.count(fixture.a))
        assertEquals("B 库计数必须保持不变（否则说明签到了另一本库）", 71, fixture.count(fixture.b))
    }

    @Test
    fun `切到B库后断言只能签B的密钥_两库计数互不影响`() {
        fixture.prepare()
        val origin = fixture.nativeOrigin()
        fixture.activate(fixture.b)
        fixture.client(CredentialSaveClientActivity.MODE_GET_PASSKEY, fixture.request())
        fixture.select("cm-matrix-B")
        fixture.awaitConfirmation(PasskeyAssertionActivity::class.java)
        fixture.appButton(com.keepasskey.app.R.string.passkey_confirm_ok)
        fixture.awaitResult(PasskeyAssertionActivity::class.java, android.app.Activity.RESULT_OK)
        fixture.exportAssertion("B1", origin, count = 72)
        assertEquals("B 库计数必须递增", 72, fixture.count(fixture.b))
        assertEquals("A 库计数必须保持不变（同 UUID 但不同库，不得串账）", 17, fixture.count(fixture.a))
    }

    @Test
    fun `A与B同UUID但凭据id不同_候选互不串库`() {
        fixture.prepare()
        assertNotEquals(
            "A/B 必须使用不同的 credentialId，否则本矩阵无法区分签的是哪一本库",
            fixture.credentialIds.getValue("A"),
            fixture.credentialIds.getValue("B")
        )
        assertNotEquals(
            "A/B 必须使用不同的公钥",
            fixture.publicKeys.getValue("A"),
            fixture.publicKeys.getValue("B")
        )
    }

    @Test
    fun `A库活动时B的凭据不出候选`() {
        fixture.prepare()
        // 活动库是 A；请求 RP 相同，但 allowCredentials 只给了 B 的 id
        fixture.client(
            CredentialSaveClientActivity.MODE_GET_PASSKEY_PREPARE,
            fixture.request(allowed = fixture.credentialIds.getValue("B"))
        )
        val status = fixture.awaitStatus("prepared:passkey=")
        assertEquals(
            "活动库是 A 时，B 的凭据不得出现在候选里；status=$status",
            "prepared:passkey=false",
            status
        )
        assertTrue("A 库文件必须仍然存在", File(fixture.a.absolutePath).exists())
    }
}
