package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-156 回归：增量定点擦除（[KdbxGroup.eraseSupersededSensitiveData]）的语义与候选收敛。
 *
 * 判据三条：
 * 1. **不得削弱**——被替换节点中各类敏感实例（标准字段 / 自定义字段 / 附件 / 历史快照）
 *    凡在新树不可达者必须清零；
 * 2. **不得误擦**——新树其它位置（克隆体 / 历史快照 / 被保留的旧子项）按**同一引用**共享的
 *    实例必须保留（这正是通用实现里「整棵新树身份集合」存在的理由）；
 * 3. **与通用实现结论一致**——同型场景下逐实例对拍 [KdbxGroup.clearSupersededSensitiveData]。
 *
 * 候选收敛（AC 第二条「集合规模不随全库规模增长」）由**构造**保证：候选集合只由被替换节点
 * 收集（`collectSensitiveIdentities` 仅对该节点调用一次），此处以「未命中分支按引用复用」的
 * 路径复制用例（`SessionTreeEditorPathCopyTest`）作为其正确性前提的锁定。
 *
 * **调用前提**：调用方必须列全本次全部被替换节点。本入口**不负责**发现未声明的下线实例——
 * 路径复制契约下「替换关系之外不存在下线实例」，该前提由树变换的引用复用逐处锁定。
 */
class KdbxIncrementalSensitiveErasureTest {

    private fun uuid(seed: Byte) = KdbxUuid(ByteArray(16) { seed })

    private val rootId = uuid(9)

    private fun entry(
        idSeed: Byte,
        parentGroupId: KdbxUuid? = rootId,
        fields: Map<String, ProtectedString> = emptyMap(),
        customFields: List<KdbxCustomField> = emptyList(),
        attachments: List<KdbxAttachment> = emptyList(),
        history: List<KdbxEntry> = emptyList()
    ) = KdbxEntry(
        id = uuid(idSeed),
        parentGroupId = parentGroupId,
        fields = fields,
        customFields = customFields,
        attachments = attachments,
        history = history
    )

    private fun password(value: String) = ProtectedString(value, isProtected = true)

    private fun attachment(name: String, byte: Byte) =
        KdbxAttachment(name = name, data = ByteArray(4) { byte })

    /** 已清零的 ProtectedString 读取会抛 IllegalStateException，此处归一为 null 便于断言 */
    private fun readOrNull(value: ProtectedString): String? =
        try {
            value.readString()
        } catch (_: IllegalStateException) {
            null
        }

    private fun isZeroed(value: KdbxAttachment): Boolean = value.data.all { it == 0.toByte() }

    @Test
    fun `被替换旧条目各类敏感实例在新树不可达时均被清零`() {
        val fieldSecret = password("old-field")
        val customSecret = password("old-custom")
        val attachmentBytes = attachment("old.bin", 0x5A)
        val historySecret = password("old-history")
        val oldEntry = entry(
            idSeed = 1,
            fields = mapOf(KdbxConstants.Fields.PASSWORD to fieldSecret),
            customFields = listOf(KdbxCustomField("k", customSecret)),
            attachments = listOf(attachmentBytes),
            history = listOf(entry(idSeed = 2, fields = mapOf(KdbxConstants.Fields.PASSWORD to historySecret)))
        )
        val oldRoot = KdbxGroup(id = rootId, name = "root", entries = listOf(oldEntry))

        // saveEntry 语义：同 id 全新实例替换（不共享任何承载容器、不带 history）
        val replacement = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new-pass")))
        val newRoot = oldRoot.copy(entries = listOf(replacement))

        newRoot.eraseSupersededSensitiveData(oldEntry, replacement)

        assertNull("标准字段必须清零", readOrNull(fieldSecret))
        assertNull("自定义字段必须清零", readOrNull(customSecret))
        assertTrue("附件字节必须清零", isZeroed(attachmentBytes))
        assertNull("历史快照必须清零", readOrNull(historySecret))
        assertEquals("上线新条目的密文不得受影响", "new-pass", replacement.password!!.readString())
    }

    @Test
    fun `克隆体按引用共享的字段实例不得因源条目被整体替换而误擦`() {
        val shared = password("shared-secret")
        val source = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to shared))
        // EntryDuplicateCoordinator.toFreshClone 语义：copy 换 id，字段按引用共享
        val clone = source.copy(id = uuid(2))
        val oldRoot = KdbxGroup(id = rootId, name = "root", entries = listOf(source, clone))

        val replacement = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new-pass")))
        val newRoot = oldRoot.copy(entries = listOf(replacement, clone))

        newRoot.eraseSupersededSensitiveData(source, replacement)

        assertEquals("克隆体仍持有同一实例 ⇒ 必须保留（存活判定须覆盖全树）", "shared-secret", shared.readString())
    }

    @Test
    fun `被替换旧条目仍作为历史快照存活时其字段实例不得被误擦`() {
        val kept = password("kept-by-history")
        val oldEntry = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to kept))
        val oldRoot = KdbxGroup(id = rootId, name = "root", entries = listOf(oldEntry))

        // HistoryManager.recordHistorySnapshot 语义：快照 = currentEntry.copy(history = emptyList())
        val snapshot = oldEntry.copy(history = emptyList())
        val replacement = oldEntry.copy(
            fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new-pass")),
            history = listOf(snapshot)
        )
        val newRoot = oldRoot.copy(entries = listOf(replacement))

        newRoot.eraseSupersededSensitiveData(oldEntry, replacement)

        assertEquals("历史快照仍引用同一实例 ⇒ 必须保留", "kept-by-history", kept.readString())
    }

    @Test
    fun `被替换分组子树整体下线时其条目密文被清零`() {
        val childSecret = password("child-secret")
        val child = entry(idSeed = 3, parentGroupId = uuid(4), fields = mapOf(KdbxConstants.Fields.PASSWORD to childSecret))
        val oldGroup = KdbxGroup(id = uuid(4), parentGroupId = rootId, name = "sub", entries = listOf(child))
        val oldRoot = KdbxGroup(id = rootId, name = "root", subgroups = listOf(oldGroup))

        val replacement = KdbxGroup(id = uuid(4), parentGroupId = rootId, name = "sub")
        val newRoot = oldRoot.copy(subgroups = listOf(replacement))

        newRoot.eraseSupersededSensitiveData(oldGroup, replacement)

        assertNull("整体下线的子树条目密文必须清零", readOrNull(childSecret))
    }

    @Test
    fun `替换分组与被替换分组共享子项时子项密文不得被误擦`() {
        val childSecret = password("child-secret")
        val child = entry(idSeed = 3, parentGroupId = uuid(4), fields = mapOf(KdbxConstants.Fields.PASSWORD to childSecret))
        val existing = KdbxGroup(id = uuid(4), parentGroupId = rootId, name = "sub", entries = listOf(child))
        val oldRoot = KdbxGroup(id = rootId, name = "root", subgroups = listOf(existing))

        // SessionTreeEditor.preserveChildrenIfMissing 语义：传入分组无子项 ⇒ 并入既有子项列表
        val placed = existing.copy(name = "renamed", entries = emptyList()).copy(entries = existing.entries)
        val newRoot = oldRoot.copy(subgroups = listOf(placed))

        newRoot.eraseSupersededSensitiveData(existing, placed)

        assertEquals("共享子项仍存活 ⇒ 必须保留", "child-secret", childSecret.readString())
    }

    @Test
    fun `替换节点与旧节点共享全部承载容器时任何实例都不得被清零`() {
        val shared = password("shared-secret")
        val oldEntry = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to shared))
        val oldRoot = KdbxGroup(id = rootId, name = "root", entries = listOf(oldEntry))
        // setEntryFavorite 语义：仅改元数据，copy 保留全部承载容器
        val updated = oldEntry.copy(times = oldEntry.times.withModified())
        val newRoot = oldRoot.copy(entries = listOf(updated))

        newRoot.eraseSupersededSensitiveData(oldEntry, updated)

        assertEquals("承载容器同一引用 ⇒ 零候选，不得清零", "shared-secret", shared.readString())
    }

    @Test
    fun `增量擦除与通用擦除在同型场景下逐实例结论一致`() {
        fixtures().forEach { (name, build) ->
            val general = build()
            general.oldRoot.clearSupersededSensitiveData(general.newRoot)

            val incremental = build()
            incremental.eraseIncremental()

            assertEquals(
                "场景「$name」：增量擦除的逐实例结论必须与通用实现一致",
                general.clearedFlags(),
                incremental.clearedFlags()
            )
        }
    }

    private class Fixture(
        val oldRoot: KdbxGroup,
        val newRoot: KdbxGroup,
        private val probes: List<ProtectedString>,
        val eraseIncremental: () -> Unit
    ) {
        /** 探针按固定顺序给出「是否已清零」，供两套实现逐实例对拍 */
        fun clearedFlags(): List<Boolean> = probes.map { probe ->
            try {
                probe.readString()
                false
            } catch (_: IllegalStateException) {
                true
            }
        }
    }

    /** 同型场景工厂：每次调用产出结构相同、实例独立的旧树 / 新树与探针 */
    private fun fixtures(): Map<String, () -> Fixture> {
        fun root(entries: List<KdbxEntry> = emptyList(), subgroups: List<KdbxGroup> = emptyList()) =
            KdbxGroup(id = rootId, name = "root", entries = entries, subgroups = subgroups)

        return mapOf(
            "同 id 全新实例替换（下线字段）" to {
                val dropped = password("dropped")
                val old = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to dropped))
                val replacement = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new")))
                val oldRoot = root(entries = listOf(old))
                Fixture(oldRoot, oldRoot.copy(entries = listOf(replacement)), listOf(dropped)) {
                    oldRoot.copy(entries = listOf(replacement)).eraseSupersededSensitiveData(old, replacement)
                }
            },
            "克隆体共享字段（存活实例）" to {
                val shared = password("shared")
                val source = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to shared))
                val clone = source.copy(id = uuid(2))
                val replacement = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new")))
                val oldRoot = root(entries = listOf(source, clone))
                Fixture(oldRoot, oldRoot.copy(entries = listOf(replacement, clone)), listOf(shared)) {
                    oldRoot.copy(entries = listOf(replacement, clone))
                        .eraseSupersededSensitiveData(source, replacement)
                }
            },
            "历史快照共享字段（存活实例）" to {
                val kept = password("kept")
                val old = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to kept))
                val replacement = old.copy(
                    fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new")),
                    history = listOf(old.copy(history = emptyList()))
                )
                val oldRoot = root(entries = listOf(old))
                Fixture(oldRoot, oldRoot.copy(entries = listOf(replacement)), listOf(kept)) {
                    oldRoot.copy(entries = listOf(replacement)).eraseSupersededSensitiveData(old, replacement)
                }
            },
            "分组子树整体替换（下线字段）" to {
                val dropped = password("dropped-child")
                val child = entry(idSeed = 3, parentGroupId = uuid(4), fields = mapOf(KdbxConstants.Fields.PASSWORD to dropped))
                val oldGroup = KdbxGroup(id = uuid(4), parentGroupId = rootId, name = "sub", entries = listOf(child))
                val replacement = KdbxGroup(id = uuid(4), parentGroupId = rootId, name = "sub")
                val oldRoot = root(subgroups = listOf(oldGroup))
                Fixture(oldRoot, oldRoot.copy(subgroups = listOf(replacement)), listOf(dropped)) {
                    oldRoot.copy(subgroups = listOf(replacement)).eraseSupersededSensitiveData(oldGroup, replacement)
                }
            },
            "分组替换但共享子项（存活实例）" to {
                val kept = password("kept-child")
                val child = entry(idSeed = 3, parentGroupId = uuid(4), fields = mapOf(KdbxConstants.Fields.PASSWORD to kept))
                val existing = KdbxGroup(id = uuid(4), parentGroupId = rootId, name = "sub", entries = listOf(child))
                val placed = existing.copy(name = "renamed")
                val oldRoot = root(subgroups = listOf(existing))
                Fixture(oldRoot, oldRoot.copy(subgroups = listOf(placed)), listOf(kept)) {
                    oldRoot.copy(subgroups = listOf(placed)).eraseSupersededSensitiveData(existing, placed)
                }
            }
        )
    }

    @Test
    fun `替换关系之外的下线实例不由本入口处置（调用方须列全被替换节点）`() {
        val stale = password("stale-outside-replaced-node")
        // 旧树中存在一条**不在替换位置**的条目（例如调用方另行持有的旧快照）
        val staleEntry = entry(idSeed = 7, fields = mapOf(KdbxConstants.Fields.PASSWORD to stale))
        val replaced = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to password("old")))
        val oldRoot = KdbxGroup(id = rootId, name = "root", entries = listOf(replaced, staleEntry))

        val replacement = entry(idSeed = 1, fields = mapOf(KdbxConstants.Fields.PASSWORD to password("new")))
        // 新树不再包含 staleEntry，但增量擦除只认「被替换节点」⇒ 候选不含它的实例
        val newRoot = oldRoot.copy(entries = listOf(replacement))

        newRoot.eraseSupersededSensitiveData(replaced, replacement)

        assertEquals(
            "候选集合规模 = 被替换节点规模：本入口**不负责**发现调用方未声明的下线实例",
            "stale-outside-replaced-node",
            stale.readString()
        )
        assertSame(oldRoot.entries[1], staleEntry)
    }
}