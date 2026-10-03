package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-469` 读侧的**接线锁**：Meta `EntryTemplatesGroup` → [VaultGroup.isTemplate]。
 *
 * 判据：只有 Meta 命中该 UUID 的分组标 `isTemplate`（第三方库的官方模板组据此入选
 * 「从模板新建」供给）；Meta 未登记时**不**在此层按名兜底（按名回落由列表投影层承担，
 * 见 `VaultListProjectionFiltersTest`）——两层分工须各自锁住，否则改名 / 改 Meta 会使
 * 供给面静默失效。
 */
class VaultGroupTemplateFlagTest {

    private val rootId = KdbxUuid.random()
    private val templateId = KdbxUuid.random()
    private val otherId = KdbxUuid.random()

    private fun database(templateUuid: KdbxUuid?) = KdbxDatabase(
        header = KdbxHeader.createDefault(),
        rootGroup = KdbxGroup(
            id = rootId,
            name = "Root",
            subgroups = listOf(
                KdbxGroup(id = templateId, parentGroupId = rootId, name = "Templates"),
                KdbxGroup(id = otherId, parentGroupId = rootId, name = "普通")
            )
        ),
        entryTemplatesGroup = templateUuid
    )

    private fun coordinator(session: DatabaseSession) = VaultGroupCoordinator(
        databaseSession = session,
        entryMapper = VaultEntryMapper(StringsProvider { _, _ -> "" }),
        persistSession = { KdbxResult.Success(Unit) }
    )

    @Test
    fun `Meta 命中的分组标 isTemplate，其余分组不标`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(database(templateUuid = templateId))

        val groups = coordinator(session).groupsFlow().first()

        assertTrue(
            "Meta EntryTemplatesGroup 命中的分组必须标 isTemplate",
            groups.first { it.id == templateId.toHexString() }.isTemplate
        )
        assertFalse(
            "未命中的分组不得标 isTemplate",
            groups.first { it.id == otherId.toHexString() }.isTemplate
        )
    }

    @Test
    fun `Meta 未登记时本层不按名兜底（回落归列表投影层）`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(
            database(templateUuid = null).copy(
                rootGroup = KdbxGroup(
                    id = rootId,
                    name = "Root",
                    subgroups = listOf(KdbxGroup(id = templateId, parentGroupId = rootId, name = "模板"))
                )
            )
        )

        val groups = coordinator(session).groupsFlow().first()

        assertFalse(
            "Meta 未登记时投影层的 isTemplate 必须为 false——按名回落是列表投影层的职责，不在此层",
            groups.first { it.id == templateId.toHexString() }.isTemplate
        )
    }
}
