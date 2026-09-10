package com.keepasskey.app.ui.screens.settings

import com.keepasskey.app.R
import com.keepasskey.app.data.childdb.ChildDatabaseEntryProjection
import com.keepasskey.app.data.childdb.ChildDatabaseException
import com.keepasskey.app.data.childdb.ChildDatabaseFailureReason
import com.keepasskey.app.data.childdb.ChildDatabaseMountState
import com.keepasskey.app.data.childdb.ChildDatabaseSnapshot
import com.keepasskey.app.ui.model.UiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-20：子库挂载「状态 → 文案」映射的纯函数单测（JVM 直测，无 Android 依赖）。
 *
 * 覆盖验收要点：
 * 1. **穷尽**：6 种运行时状态与 12 种失败分型全部有专用呈现，无静默回落；
 * 2. **不谎报**：`Opening` 不映射为「未解锁」或「已解锁」任何文案（无文案即事实）；
 * 3. **不混同**：「已挂载」与「已解锁」在展示态上是两件事——
 *    `Closed`（锁库/重启后仍是已挂载）如实呈现「未解锁」并提供真实的重试入口；
 * 4. **条数真实**：`Opened` 的条目数直接取快照（不估算、不占位）。
 */
class ChildDatabaseStatusTextTest {

    // ===================== 运行时状态 → 呈现 =====================

    @Test
    fun `未解锁状态映射为「未解锁」文案`() {
        assertEquals(
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_locked)),
            ChildDatabaseStatusText.of(ChildDatabaseMountState.Closed)
        )
    }

    @Test
    fun `解锁中状态不映射任何文案`() {
        val status = ChildDatabaseStatusText.of(ChildDatabaseMountState.Opening)

        assertSame(
            "运行中态必须以不确定进度表达，不得借用「未解锁」或「已解锁」文案",
            ChildDatabaseStatus.Opening,
            status
        )
        assertFalse("Opening 不得产生文案", status is ChildDatabaseStatus.Text)
    }

    @Test
    fun `已解锁状态的条目数来自真实快照`() {
        val status = ChildDatabaseStatusText.of(ChildDatabaseMountState.Opened(snapshot(ENTRY_COUNT)))

        val text = status as ChildDatabaseStatus.Text
        assertEquals(R.string.dbset_child_db_opened_summary, text.message.resId)
        assertEquals(listOf<Any>(ENTRY_COUNT), text.message.args)
    }

    @Test
    fun `条目数为零的已解锁子库同样如实呈现零`() {
        val status = ChildDatabaseStatusText.of(ChildDatabaseMountState.Opened(snapshot(0)))

        val text = status as ChildDatabaseStatus.Text
        assertEquals(listOf<Any>(0), text.message.args)
    }

    @Test
    fun `凭据被拒与来源不可读各有专用文案`() {
        assertEquals(
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_rejected)),
            ChildDatabaseStatusText.of(ChildDatabaseMountState.CredentialRejected)
        )
        assertEquals(
            ChildDatabaseStatus.Text(UiMessage(R.string.dbset_child_db_state_unavailable)),
            ChildDatabaseStatusText.of(ChildDatabaseMountState.SourceUnavailable)
        )
    }

    @Test
    fun `失败状态的文案取自失败分型而非统一兜底`() {
        val status = ChildDatabaseStatusText.of(
            ChildDatabaseMountState.Failed(ChildDatabaseFailureReason.TERMINATED)
        )

        val text = status as ChildDatabaseStatus.Text
        assertEquals(R.string.dbset_child_db_err_terminated, text.message.resId)
        assertNotEquals(
            "具体分型不得被兜底文案吞掉",
            R.string.dbset_child_db_err_unknown,
            text.message.resId
        )
    }

    @Test
    fun `六种运行时状态全部有穷尽呈现且仅 Opening 无文案`() {
        val opened = ChildDatabaseMountState.Opened(snapshot(ENTRY_COUNT))
        val states = listOf(
            ChildDatabaseMountState.Closed,
            ChildDatabaseMountState.Opening,
            opened,
            ChildDatabaseMountState.CredentialRejected,
            ChildDatabaseMountState.SourceUnavailable,
            ChildDatabaseMountState.Failed(ChildDatabaseFailureReason.CORRUPT_FILE)
        )

        val openingCount = states.count { ChildDatabaseStatusText.of(it) === ChildDatabaseStatus.Opening }

        assertEquals("6 种状态逐一映射，无遗漏", states.size, 6)
        assertEquals("仅 Opening 以进度表达", 1, openingCount)
        states.filter { it !== ChildDatabaseMountState.Opening }.forEach { state ->
            assertTrue(
                "状态 $state 必须有专用文案",
                ChildDatabaseStatusText.of(state) is ChildDatabaseStatus.Text
            )
        }
    }

    // ===================== 失败分型 → 文案 =====================

    @Test
    fun `十二种失败分型全部有专用文案且互不重复`() {
        val reasons = ChildDatabaseFailureReason.entries
        val mapped = reasons.associateWith { ChildDatabaseStatusText.of(it) }

        assertEquals("分型数量基线（新增分型须同步补文案）", 12, reasons.size)
        mapped.forEach { (reason, resId) ->
            assertNotEquals("分型 $reason 缺少文案", 0, resId)
        }
        assertEquals(
            "12 种分型不得共用同一文案",
            reasons.size,
            mapped.values.toSet().size
        )
    }

    @Test
    fun `关键分型映射到语义相符的文案`() {
        assertEquals(
            R.string.dbset_child_db_err_credential_missing,
            ChildDatabaseStatusText.of(ChildDatabaseFailureReason.CREDENTIAL_MISSING)
        )
        assertEquals(
            R.string.dbset_child_db_err_credential_rejected,
            ChildDatabaseStatusText.of(ChildDatabaseFailureReason.CREDENTIAL_REJECTED)
        )
        assertEquals(
            R.string.dbset_child_db_err_duplicate,
            ChildDatabaseStatusText.of(ChildDatabaseFailureReason.DUPLICATE_MOUNT)
        )
        assertEquals(
            R.string.dbset_child_db_err_unsupported_version,
            ChildDatabaseStatusText.of(ChildDatabaseFailureReason.UNSUPPORTED_VERSION)
        )
    }

    // ===================== 凭据重试入口 =====================

    @Test
    fun `已打开与进行中状态不提供凭据重试入口`() {
        assertFalse(
            ChildDatabaseStatusText.allowsCredentialRetry(
                ChildDatabaseMountState.Opened(snapshot(ENTRY_COUNT))
            )
        )
        assertFalse(
            ChildDatabaseStatusText.allowsCredentialRetry(ChildDatabaseMountState.Opening)
        )
    }

    @Test
    fun `未解锁与其他失败状态均提供凭据重试入口`() {
        listOf(
            ChildDatabaseMountState.Closed,
            ChildDatabaseMountState.CredentialRejected,
            ChildDatabaseMountState.SourceUnavailable,
            ChildDatabaseMountState.Failed(ChildDatabaseFailureReason.IO_ERROR)
        ).forEach { state ->
            assertTrue(
                "状态 $state 必须给出真实的重试入口（不得只显示「已挂载」而无解锁手段）",
                ChildDatabaseStatusText.allowsCredentialRetry(state)
            )
        }
    }

    // ===================== 反馈与来源展示名 =====================

    @Test
    fun `挂载成功反馈不标红而失败反馈标红`() {
        val success = childDatabaseMountedFeedback()
        assertEquals(R.string.dbset_child_db_mounted, success.message.resId)
        assertFalse("成功反馈不得按错误配色呈现", success.isError)

        val failure = childDatabaseFailureFeedback(
            ChildDatabaseException(ChildDatabaseFailureReason.CORRUPT_FILE)
        )
        assertEquals(R.string.dbset_child_db_err_corrupt, failure.message.resId)
        assertTrue("失败反馈应按错误配色呈现", failure.isError)
    }

    @Test
    fun `失败反馈穿透 cause 链取回真实分型`() {
        val wrapped = IllegalStateException(
            "outer",
            ChildDatabaseException(
                ChildDatabaseFailureReason.SOURCE_UNAVAILABLE,
                java.io.FileNotFoundException("inner")
            )
        )

        assertEquals(
            R.string.dbset_child_db_err_source_unavailable,
            childDatabaseFailureFeedback(wrapped).message.resId
        )
    }

    @Test
    fun `非领域异常归一为未知失败文案`() {
        assertEquals(
            R.string.dbset_child_db_err_unknown,
            childDatabaseFailureFeedback(IllegalArgumentException("boom")).message.resId
        )
    }

    @Test
    fun `来源展示名取末段且空值回落原串`() {
        assertEquals(
            "child-db.kdbx",
            childDatabaseSourceDisplayName("/storage/emulated/0/Documents/child-db.kdbx")
        )
        assertEquals(
            "child-db",
            childDatabaseSourceDisplayName("content://com.example.documents/child-db")
        )
        // 全空白与空串一律回落为原串（不产出空标签之外的内容，也不抛异常）
        assertEquals("", childDatabaseSourceDisplayName("   "))
        assertEquals("", childDatabaseSourceDisplayName(""))
    }

    private fun snapshot(entryCount: Int): ChildDatabaseSnapshot = ChildDatabaseSnapshot(
        mountId = MOUNT_ID,
        mountAlias = MOUNT_ALIAS,
        databaseName = "子库测试库",
        groupCount = 1,
        entries = List(entryCount) { index -> projection(index) },
        openedAtEpochMillis = 1L
    )

    private fun projection(index: Int): ChildDatabaseEntryProjection = ChildDatabaseEntryProjection(
        mountId = MOUNT_ID,
        mountAlias = MOUNT_ALIAS,
        entryUuid = "uuid-$index",
        title = "条目$index",
        username = "user-$index",
        url = "https://example.com/$index",
        notes = "",
        groupPath = "",
        tags = emptyList(),
        iconId = 0,
        hasPassword = true
    )

    private companion object {
        const val MOUNT_ID = "mount-1"
        const val MOUNT_ALIAS = "子库一"
        const val ENTRY_COUNT = 3
    }
}
