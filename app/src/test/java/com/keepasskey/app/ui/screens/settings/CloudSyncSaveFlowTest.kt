package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.ui.screens.settings.subscreens.persistThenContinue
import com.keepasskey.app.ui.screens.settings.subscreens.withSyncCredentialSubmissionCopy
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ISSUE-P2-651/652: 表单提交副本所有权与保存后的联网守卫。 */
class CloudSyncSaveFlowTest {

    @Test
    fun `保存拒绝时保留可提交草稿并清除WebDAV工作副本`() {
        val draft = "fake-webdav-password".toCharArray()
        var submitted: CharArray? = null

        val saved = withSyncCredentialSubmissionCopy(draft) { copy ->
            submitted = copy
            assertNotSame("持久化只能拿到独立工作副本", draft, copy)
            assertArrayEquals("工作副本保存前包含输入值", draft, copy)
            false
        }

        assertFalse(saved)
        assertArrayEquals("保存失败后表单草稿仍可重试", "fake-webdav-password".toCharArray(), draft)
        assertTrue("提交方工作副本无论保存结果都应清零", submitted!!.all { it == '0' })
    }

    @Test
    fun `S3的AccessKey和SecretKey工作副本分别清除且空输入保持空`() {
        val accessDraft = "fake-access-key".toCharArray()
        val secretDraft = CharArray(0)
        var submittedAccess: CharArray? = null
        var submittedSecret: CharArray? = null

        withSyncCredentialSubmissionCopy(accessDraft) { accessCopy ->
            submittedAccess = accessCopy
            withSyncCredentialSubmissionCopy(secretDraft) { secretCopy ->
                submittedSecret = secretCopy
                assertEquals("空输入仍按空数组传递，以保留存储层既有凭据语义", 0, secretCopy.size)
                true
            }
        }

        assertArrayEquals("AccessKey 草稿由表单继续持有", "fake-access-key".toCharArray(), accessDraft)
        assertTrue("AccessKey 工作副本清零", submittedAccess!!.all { it == '0' })
        assertEquals("SecretKey 工作副本仍为空", 0, submittedSecret!!.size)
        assertArrayEquals("空草稿保持为空", CharArray(0), secretDraft)
    }

    @Test
    fun `提交取消时清零工作副本且保留原草稿`() {
        val draft = "fake-secret-key".toCharArray()
        var submitted: CharArray? = null

        try {
            withSyncCredentialSubmissionCopy(draft) { copy ->
                submitted = copy
                throw CancellationException("simulated cancellation")
            }
        } catch (_: CancellationException) {
            // 断言取消路径 finally 擦除，且借用者草稿仍可重试。
        }

        assertArrayEquals("异常时保留表单草稿", "fake-secret-key".toCharArray(), draft)
        assertTrue("异常时仍清除工作副本", submitted!!.all { it == '0' })
    }

    @Test
    fun `保存失败不调用测试或同步后续动作`() {
        var persistCalls = 0
        var networkActions = 0

        val continued = persistThenContinue(
            persist = { persistCalls++; false },
            continueAfterPersist = { networkActions++ }
        )

        assertFalse(continued)
        assertEquals("保存仅尝试一次", 1, persistCalls)
        assertEquals("失败保存不触发任何后续联网动作", 0, networkActions)
    }

    @Test
    fun `测试编排回调只在保存成功后调用并观察到已写入状态`() {
        var persistedTarget = "old-target"
        var callbackObservedTarget: String? = null
        var networkActions = 0

        val continued = persistThenContinue(
            persist = {
                persistedTarget = "new-target"
                true
            },
            continueAfterPersist = {
                networkActions++
                callbackObservedTarget = persistedTarget
            }
        )

        assertTrue(continued)
        assertEquals(1, networkActions)
        assertEquals("回调只能在本次保存返回成功、状态更新后观察到新目标", "new-target", callbackObservedTarget)
    }
}
