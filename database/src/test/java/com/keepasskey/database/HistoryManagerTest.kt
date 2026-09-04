package com.keepasskey.database.history

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HistoryManager 版本历史归档与回滚单元测试
 */
class HistoryManagerTest {

    @Test
    fun `测试历史快照记录与一键回滚`() {
        val entryId = KdbxUuid(ByteArray(16) { 5 })
        val v1 = KdbxEntry(
            id = entryId,
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Title V1", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("PassV1", isProtected = true)
            )
        )

        val v2Draft = v1.withField(KdbxConstants.Fields.TITLE, ProtectedString("Title V2", isProtected = false))
        val v2 = HistoryManager.recordHistorySnapshot(v1, v2Draft)

        assertEquals("Title V2", v2.title)
        assertEquals(1, v2.history.size)
        assertEquals("Title V1", v2.history.first().title)

        // 执行回滚至 V1
        val rolledBack = HistoryManager.rollbackToSnapshot(v2, 0)
        assertEquals("Title V1", rolledBack.title)
        assertEquals("PassV1", rolledBack.password?.readString())
        assertEquals(entryId, rolledBack.id)
    }
}
