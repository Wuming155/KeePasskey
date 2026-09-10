package com.keepasskey.app.sync

import com.keepasskey.app.ui.screens.settings.ConflictResolution
import com.keepasskey.app.ui.screens.settings.ExtendedSettings
import com.keepasskey.sync.merge.ConflictDisposition
import com.keepasskey.sync.merge.ConflictStrategyPolicy
import com.keepasskey.sync.merge.SyncConflictStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-03 (43a)：冲突解决策略的层间映射与处置分支单元测试。
 *
 * 覆盖两段决策链：
 * 1. 设置页偏好枚举 → sync 领域枚举（本模块映射，依赖倒置边界）；
 * 2. sync 领域枚举 → 编排器处置分支（纯决策函数，含是否跳过三方合并）。
 */
class ConflictStrategyMappingTest {

    @Test
    fun `设置页四种策略逐项映射到 sync 领域枚举`() {
        assertEquals(
            SyncConflictStrategy.AUTO_MERGE,
            ConflictResolution.AUTO_MERGE.toSyncStrategy()
        )
        assertEquals(
            SyncConflictStrategy.PROMPT_USER,
            ConflictResolution.PROMPT_USER.toSyncStrategy()
        )
        assertEquals(
            SyncConflictStrategy.KEEP_REMOTE,
            ConflictResolution.KEEP_REMOTE.toSyncStrategy()
        )
        assertEquals(
            SyncConflictStrategy.KEEP_LOCAL,
            ConflictResolution.KEEP_LOCAL.toSyncStrategy()
        )
    }

    @Test
    fun `进阶偏好默认策略为自动合并`() {
        assertEquals(SyncConflictStrategy.AUTO_MERGE, ExtendedSettings().conflictResolution.toSyncStrategy())
    }

    @Test
    fun `处置分支与是否跳过合并的判定`() {
        assertEquals(
            ConflictDisposition.AutoMerge,
            ConflictStrategyPolicy.dispositionOf(SyncConflictStrategy.AUTO_MERGE)
        )
        assertEquals(
            ConflictDisposition.PromptUser,
            ConflictStrategyPolicy.dispositionOf(SyncConflictStrategy.PROMPT_USER)
        )
        assertEquals(
            ConflictDisposition.TakeRemote,
            ConflictStrategyPolicy.dispositionOf(SyncConflictStrategy.KEEP_REMOTE)
        )
        assertEquals(
            ConflictDisposition.TakeLocal,
            ConflictStrategyPolicy.dispositionOf(SyncConflictStrategy.KEEP_LOCAL)
        )

        // 单方强制策略不做三方合并；自动合并 / 每次询问必须继续走合并流程
        assertTrue(ConflictStrategyPolicy.skipsMerge(SyncConflictStrategy.KEEP_REMOTE))
        assertTrue(ConflictStrategyPolicy.skipsMerge(SyncConflictStrategy.KEEP_LOCAL))
        assertFalse(ConflictStrategyPolicy.skipsMerge(SyncConflictStrategy.AUTO_MERGE))
        assertFalse(ConflictStrategyPolicy.skipsMerge(SyncConflictStrategy.PROMPT_USER))
    }
}
