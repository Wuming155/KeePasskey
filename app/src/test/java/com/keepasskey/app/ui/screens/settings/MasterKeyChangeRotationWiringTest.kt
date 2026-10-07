package com.keepasskey.app.ui.screens.settings

import androidx.fragment.app.FragmentActivity
import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-528` 接线守卫：主凭据变更**成功后**必须登记「云端副本待替换」，
 * 且登记发生在重封印**之前**、失败路径**不得**登记。
 *
 * 立规缘由（本仓既有教训同型）：机制实现正确、接线断掉时功能静默失效，
 * 「闸门存在 ≠ 闸门被执行」——故接线本身须有用例钉住。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MasterKeyChangeRotationWiringTest {

    private class RecordingRepository(
        private val result: KdbxResult<Unit> = KdbxResult.Success(Unit)
    ) : VaultRepository by FakeVaultRepository() {
        override suspend fun changeMasterPassword(
            newPassword: CharArray,
            keyFileIntent: ChangeKeyFileIntent
        ): KdbxResult<Unit> = result
    }

    @Test
    fun `换密成功后先登记云端副本待替换再重封印`() = runTest {
        val order = mutableListOf<String>()
        val controller = SettingsMasterKeyChangeController(
            RecordingRepository(), this,
            resealAfterChange = { _: FragmentActivity?, _: CharArray? -> order.add(ORDER_RESEAL) },
            onCredentialsRotated = { order.add(ORDER_ROTATION) }
        )

        val password = "Wiring#New#1".toCharArray()
        controller.submit(password, ChangeKeyFileIntent.Keep, null)
        advanceUntilIdle()

        assertEquals(
            "登记必须发生且早于重封印（重封印可被用户取消，登记不得随之丢失）",
            listOf(ORDER_ROTATION, ORDER_RESEAL),
            order
        )
        assertTrue("任务收尾仍必须清零入参", password.all { it == '0' })
    }

    @Test
    fun `换密失败不得登记`() = runTest {
        var rotationCalls = 0
        var resealCalls = 0
        val controller = SettingsMasterKeyChangeController(
            RecordingRepository(KdbxResult.Failure(IllegalStateException("boom"), "改密失败")), this,
            resealAfterChange = { _: FragmentActivity?, _: CharArray? -> resealCalls++ },
            onCredentialsRotated = { rotationCalls++ }
        )

        val password = "Wiring#Should#Fail".toCharArray()
        controller.submit(password, ChangeKeyFileIntent.Keep, null)
        advanceUntilIdle()

        assertEquals("失败路径不得登记（本地文件未轮换，云端无需替换）", 0, rotationCalls)
        assertEquals("失败路径不得重封印", 0, resealCalls)
        assertFalse("入参仍必须被清零", password.any { it != '0' })
    }

    private companion object {
        const val ORDER_ROTATION = "rotation"
        const val ORDER_RESEAL = "reseal"
    }
}
