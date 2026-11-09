package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.sync.SyncOutcome
import com.keepasskey.app.sync.userText
import com.keepasskey.app.ui.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 同步结果 → 界面反馈的**展示时长口径**（§373；ENG-20261028-12 由源码守卫改为行为用例）。
 *
 * 原 `SyncSnackbarDurationWiringTest` 五条用例全部读源码文本：统计短档挂载处数（恰为 3）、
 * 匹配 `host.contains("if (undoLabel == null) message.durationMillis else null")` 之类的实现串。
 * 这类断言只锁写法（改用 when 分支、把映射下沉一处即整批误红），也不证明「状态类消息挂短档、
 * 需阅读的消息不缩短」这一口径。现按可观测行为验证 [syncOutcomeFeedback]（唯一结果→文案映射）：
 *
 * - 成功三态（两端一致 / 已上传 / 合并上传）挂 1s 短档，非错误样式、无撤销；
 * - 需阅读的结果（待同步 / 冲突 / 绑定不符 / 离线 / 错误）一律 `null`（走宿主默认档）；
 * - 各结果映射到各自文案资源，错误文案只经命名出口 [SyncOutcome.Error.userText]。
 *
 * **未覆盖（不得由本件绿推定）**：列表页 `VaultListSyncController` 的短档挂载与
 * `AppGlobalSnackbarHost` 的「无撤销时才按自定义时长、有撤销恒走 Material Long」消费语义——
 * 前者尚无纯映射接缝，后者是 Compose 宿主行为；两者须经界面 / 设备验收，验收点见
 * ENG-20261028-12 关闭条件。
 */
class SyncSnackbarDurationBehaviorTest {

    @Test
    fun `成功三态挂同步状态短档且非错误样式`() {
        listOf(
            SyncOutcome.UpToDate,
            SyncOutcome.UploadedLocal,
            SyncOutcome.MergedAndUploaded
        ).forEach { outcome ->
            val message = syncOutcomeFeedback(outcome, PROTOCOL)
            assertEquals(
                "$outcome 应挂同步状态短档（减少打断感）",
                UiMessage.SYNC_STATUS_DURATION_MS,
                message.durationMillis
            )
            assertFalse("$outcome 不得标记为错误样式", message.isError)
            assertFalse("$outcome 不得附带撤销动作", message.undoable)
        }
    }

    @Test
    fun `需阅读的结果不缩短时长`() {
        val readable: List<SyncOutcome> = listOf(
            SyncOutcome.LocalPendingChanges,
            SyncOutcome.ConflictNeedsUser(conflicts = emptyList()),
            SyncOutcome.VaultBindingMismatch(remotePath = "synthetic-remote.kdbx"),
            SyncOutcome.Offline,
            SyncOutcome.Error("本地化失败文案")
        )

        readable.forEach { outcome ->
            assertNull(
                "$outcome 需要阅读，不得挂同步状态短档（null = 宿主默认档）",
                syncOutcomeFeedback(outcome, PROTOCOL).durationMillis
            )
        }
    }

    @Test
    fun `各结果映射到各自文案资源且错误只走命名出口`() {
        assertEquals(R.string.sync_feedback_done, syncOutcomeFeedback(SyncOutcome.UpToDate, PROTOCOL).resId)
        assertEquals(R.string.sync_feedback_uploaded, syncOutcomeFeedback(SyncOutcome.UploadedLocal, PROTOCOL).resId)
        assertEquals(R.string.sync_feedback_merged, syncOutcomeFeedback(SyncOutcome.MergedAndUploaded, PROTOCOL).resId)
        assertEquals(
            R.string.sync_feedback_local_pending,
            syncOutcomeFeedback(SyncOutcome.LocalPendingChanges, PROTOCOL).resId
        )
        assertEquals(
            R.string.sync_feedback_conflict,
            syncOutcomeFeedback(SyncOutcome.ConflictNeedsUser(conflicts = emptyList()), PROTOCOL).resId
        )
        assertEquals(
            R.string.sync_vault_binding_mismatch,
            syncOutcomeFeedback(SyncOutcome.VaultBindingMismatch("synthetic-remote.kdbx"), PROTOCOL).resId
        )
        assertEquals(R.string.sync_feedback_offline, syncOutcomeFeedback(SyncOutcome.Offline, PROTOCOL).resId)

        val error = SyncOutcome.Error("本地化失败文案")
        val errorMessage = syncOutcomeFeedback(error, PROTOCOL)
        assertEquals(R.string.sync_feedback_error, errorMessage.resId)
        assertEquals(
            "错误文案必须取命名出口 userText 的本地化结果，不得拼接异常细节",
            listOf(error.userText),
            errorMessage.args
        )
    }

    @Test
    fun `短档常量为 1 秒且默认消息不缩短`() {
        assertEquals("用户反馈后收短至 1s 的口径", 1_000L, UiMessage.SYNC_STATUS_DURATION_MS)
        assertNull(
            "未指定时长的消息应走宿主默认档",
            UiMessage(R.string.sync_feedback_done).durationMillis
        )
    }

    private companion object {
        const val PROTOCOL = "WebDAV"
    }
}