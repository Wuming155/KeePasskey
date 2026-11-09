package com.keepasskey.app.data.repository

import com.keepasskey.app.passkey.KeePasskeyCredentialProviderService
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-341`：回收站子树内的凭据不得再被任何供给面捞出。
 *
 * ## 锁定的缺陷
 *
 * 本项目的"已删除"是**组归属**而非条目位（`KdbxEntry` 无 `isDeleted`），而整改前全仓只有列表页的
 * **搜索分支**做了过滤 ⇒ 验证器列表、CM 通行密钥候选、autofill 候选、断言与填充执行侧
 * 一律捞出整树 ⇒ **用户删掉的凭据仍然可被选中并签名 / 填出**（用户在真机上先看到的是
 * 「回收站里的 TOTP 在验证器里还全部能看到」）。
 *
 * ## 判据的鉴别力从哪来
 *
 * 每条"排除"断言都配一条**同源的正向对照**（整树读口 / 标记不过滤的 UI 投影仍须看得见它们）：
 * 否则实现退化成"什么都不返回"也能让全表通过——那正是本仓反复登记的假绿形态。
 */
class RecycleBinSubtreeExclusionTest {

    private val rootId = KdbxUuid.random()
    private val binId = KdbxUuid.random()
    private val nestedBinGroupId = KdbxUuid.random()
    private val liveGroupId = KdbxUuid.random()

    private fun passkeyEntry(id: KdbxUuid, parent: KdbxUuid, rpId: String) = KdbxEntry(
        id = id,
        parentGroupId = parent,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("站点 $rpId", false),
            KdbxConstants.Fields.URL to ProtectedString("https://$rpId", false),
            KdbxConstants.Fields.USER_NAME to ProtectedString("alice", false)
        ),
        customFields = listOf(
            KdbxCustomField(PasskeyData.FIELD_RP_ID, ProtectedString(rpId, isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_CREDENTIAL_ID, ProtectedString("cred_$rpId", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("fake_private_key", isProtected = true))
        )
    )

    private fun totpEntry(id: KdbxUuid, parent: KdbxUuid) = KdbxEntry(
        id = id,
        parentGroupId = parent,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("令牌", false),
            KdbxConstants.Fields.URL to ProtectedString("https://totp.example", false)
        ),
        customFields = listOf(
            KdbxCustomField(
                KdbxConstants.Fields.OTP,
                ProtectedString("otpauth://totp/Example:alice?secret=JBSWY3DPEHPK3PXP&issuer=Example", false)
            )
        )
    )

    /**
     * 树：Root → { 回收站(binId) → { 直接条目 ×2, 子组 → { 条目 ×1 } }, 活动组 → { 条目 ×2 } }
     *
     * 「子组里那条」是本条判据的关键形状：**只按 bin 自身 id 匹配的过滤会漏掉它**。
     */
    private fun databaseWithBin(binUuid: KdbxUuid?, enabled: Boolean): KdbxDatabase {
        val nested = KdbxGroup(
            id = nestedBinGroupId,
            parentGroupId = binId,
            name = "旧令牌",
            entries = listOf(totpEntry(KdbxUuid.random(), nestedBinGroupId))
        )
        val bin = KdbxGroup(
            id = binId,
            parentGroupId = rootId,
            name = "回收站",
            entries = listOf(
                passkeyEntry(KdbxUuid.random(), binId, "deleted.example"),
                totpEntry(KdbxUuid.random(), binId)
            ),
            subgroups = listOf(nested)
        )
        val live = KdbxGroup(
            id = liveGroupId,
            parentGroupId = rootId,
            name = "活动",
            entries = listOf(
                passkeyEntry(KdbxUuid.random(), liveGroupId, "deleted.example"),
                totpEntry(KdbxUuid.random(), liveGroupId)
            )
        )
        return KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(
                id = rootId,
                name = "Root",
                subgroups = listOf(bin, live)
            ),
            recycleBinUuid = binUuid,
            recycleBinEnabled = enabled
        )
    }

    private fun query(session: DatabaseSession) = VaultEntryQueryCoordinator(
        databaseSession = session,
        entryMapper = VaultEntryMapper(StringsProvider { _, _ -> "" }),
        projectionDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun `可用条目排除回收站子树的两个深度，整树读口仍含它们`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(databaseWithBin(binUuid = binId, enabled = true))
        val coordinator = query(session)

        val all = coordinator.allEntries()
        val usable = coordinator.usableEntries()

        assertEquals("整树应有 6 条（bin 直接 2 + bin 子组 1 + 活动 2 + 根 1?）", 5, all.size)
        assertEquals("可用只应剩活动组 2 条", 2, usable.size)
        val usableIds = usable.map { it.id }.toSet()
        val excluded = all.filterNot { it.id in usableIds }

        // 正向对照：被排除的确实只有回收站那三条，整树读口不受影响
        // （同步合并与 `{REF:}` 展开必须仍看得见已删条目，改坏它们这里就红）。
        assertEquals("回收站子树应排除 3 条（bin 直接 2 + bin 子组 1）", 3, excluded.size)
        assertTrue(
            "bin 子组内那条也必须被排除（只匹配 bin 自身 id 的实现会漏掉它）",
            excluded.any { it.parentGroupId == nestedBinGroupId }
        )
        assertTrue("可用侧必须全部来自活动组", usable.all { it.parentGroupId == liveGroupId })
    }

    @Test
    fun `Meta 未回填但组名叫回收站且启用回收站时同样排除（与列表页判定同源）`() = runBlocking {
        val session = DatabaseSession()
        // ISSUE-P3-470 AC①：recycleBinEnabled = true 且 recycleBinUuid = null（懒创建未写 UUID
        // 的第三方库），此时才允许按组名兜底 → 仍须排除。
        session.setDatabaseForTesting(databaseWithBin(binUuid = null, enabled = true))
        val coordinator = query(session)

        val usable = coordinator.usableEntries()
        assertEquals("按名命中的 bin 及其子组同样须被排除", 2, usable.size)
        assertTrue(usable.all { it.parentGroupId == liveGroupId })
    }

    @Test
    fun `Meta 关闭回收站时残留同名组不判已删（ISSUE-P3-470）`() = runBlocking {
        val session = DatabaseSession()
        // ISSUE-P3-470 AC①：recycleBinEnabled = false ⇒ 不凭任何依据判已删 ——
        // 官方语义下关闭开关即「删除＝永久删除」，残留同名组（此处 UUID 仍命中）里的条目是活条目。
        session.setDatabaseForTesting(databaseWithBin(binUuid = binId, enabled = false))
        val coordinator = query(session)

        val all = coordinator.allEntries()
        val usable = coordinator.usableEntries()
        assertEquals("关闭回收站时全部 5 条均为可用（无任何条目被判已删）", 5, usable.size)
        assertEquals("可用侧等于整树侧", all.map { it.id }.toSet(), usable.map { it.id }.toSet())

        // 正向对照：同口径的 UI 投影也不得标 isRecycled（否则列表页仍把它们呈现为已删）
        val projections = coordinator.entriesFlow().first()
        assertEquals("关闭回收站时投影不得标 isRecycled", 0, projections.count { it.isRecycled })
    }

    @Test
    fun `UI 投影只标记不过滤：回收站条目带 isRecycled 但仍下发`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(databaseWithBin(binUuid = binId, enabled = true))

        val projections = query(session).entriesFlow().first()
        assertEquals("投影不得过滤（列表页进回收站要看得见、能还原）", 5, projections.size)

        val recycled = projections.filter { it.isRecycled }
        val live = projections.filterNot { it.isRecycled }
        assertEquals("回收站子树（含子组）应标 3 条", 3, recycled.size)
        assertEquals("活动组 2 条不得被误标", 2, live.size)
    }

    @Test
    fun `CM 候选：同 rpId 的回收站孪生条目不出候选，活动条目出候选`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(databaseWithBin(binUuid = binId, enabled = true))
        val projections = query(session).entriesFlow().first()

        val matched = KeePasskeyCredentialProviderService().findMatchingEntries(
            entries = projections,
            origin = "https://deleted.example",
            packageName = "",
            packageDimensionAuthorized = false
        )

        val rpIds = matched.map { it.passkeyRpId }
        assertTrue("活动组那条同 rpId 凭据必须仍在候选里（正向对照）", rpIds.isNotEmpty())
        assertEquals("候选数须等于活动侧命中数", projections.count { !it.isRecycled && it.passkeyRpId == "deleted.example" }, matched.size)
        assertTrue("候选一律不得来自回收站", matched.none { it.isRecycled })
        assertNotNull("被排除的那条同 rpId 凭据确实存在于投影里", projections.firstOrNull { it.isRecycled && it.passkeyRpId == "deleted.example" })
    }

    @Test
    fun `Passkey 检索协调器同样只看可用条目：删掉的凭据不会被重新注册复活`() = runBlocking {
        val session = DatabaseSession()
        session.setDatabaseForTesting(databaseWithBin(binUuid = binId, enabled = true))
        val coordinator = PasskeyEntryCoordinator(
            databaseSession = session,
            debugLog = com.keepasskey.app.data.logger.DebugLogBuffer(),
            persistSession = { com.keepasskey.core.result.KdbxResult.Success(Unit) }
        )

        val byRp = coordinator.findEntriesForRpId("deleted.example")
        assertTrue("按 rpId 检索必须仍有命中（正向对照，防「返回空表也算过」）", byRp.isNotEmpty())
        assertTrue(
            "命中项一律不得来自回收站子树",
            byRp.none { it.parentGroupId == binId || it.parentGroupId == nestedBinGroupId }
        )
        val byCredId = coordinator.findPasskeyByCredentialId("cred_deleted.example")
        assertTrue(
            "credentialId 定位不得命中已删条目（否则删掉的凭据仍可被签名）",
            byCredId == null || byCredId.parentGroupId == liveGroupId
        )
    }

    /**
     * `ENG-20261028-11` 回归：[VaultEntryQueryCoordinator.entriesFlow] 投影与并发改树。
     *
     * 消除先取旧 db 再登记的交接窗口：投影在读租约保护下获取活动库引用，即使并发线程在短时间内
     * 频繁改树并下线擦除旧实例，活跃投影收集也绝不读到半路清零的垃圾字段，且全部快照消费完毕后
     * 租约正常归零、下线实例确定性回收排空。
     */
    @Test
    fun `entriesFlow投影在并发改树与保存期间完整可读且释放后回收`() = runBlocking {
        val session = DatabaseSession()
        try {
            withTimeout(10_000) {
                val entryCount = 50
                val initialEntries = List(entryCount) { index ->
                    KdbxEntry(
                        id = KdbxUuid.random(),
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("条目-$index", isProtected = false),
                            KdbxConstants.Fields.USER_NAME to ProtectedString("user-$index", isProtected = false),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("pass-$index", isProtected = true),
                            KdbxConstants.Fields.URL to ProtectedString("https://example.com/$index", isProtected = false)
                        )
                    )
                }
                val initialDb = KdbxDatabase(
                    header = KdbxHeader.createDefault(),
                    rootGroup = KdbxGroup(name = "Root", entries = initialEntries)
                )
                session.setDatabaseForTesting(initialDb)

                // 从活动会话树抓取生产真实实例，而不是离线构造的 seed 树
                val liveInitial = session.databaseFlow.value ?: error("数据库未初始化")
                val oldLiveEntries = liveInitial.rootGroup.allEntries()
                val idToIndex = HashMap<KdbxUuid, Int>(entryCount)
                oldLiveEntries.forEachIndexed { idx, e -> idToIndex[e.id] = idx }

                // 捕获前 10 个条目中即将被替换下线的真实 ProtectedString 实例
                val targetOldTitles = (0 until 10).map { oldLiveEntries[it].fields[KdbxConstants.Fields.TITLE]!! }
                val targetOldUserNames = (0 until 10).map { oldLiveEntries[it].fields[KdbxConstants.Fields.USER_NAME]!! }
                val targetOldPasswords = (0 until 10).map { oldLiveEntries[it].fields[KdbxConstants.Fields.PASSWORD]!! }
                val targetUntouchedTitle = oldLiveEntries[49].fields[KdbxConstants.Fields.TITLE]!!

                val coordinator = query(session)

                // 开始闸门：确保投影流已进入订阅并就绪，再触发并发改树
                val projectionStarted = CompletableDeferred<Unit>()
                val collectedSnapshots = mutableListOf<List<com.keepasskey.app.ui.model.UiVaultEntry>>()

                // 确定性完成语义：StateFlow 具有合并特性，不得靠 take(固定次数) 傻等；
                // 持续收集快照直到观测到包含最终更新标志（条目 9 变成 Updated-Title-9）的快照即刻完成
                val projectionDeferred = async(Dispatchers.Default) {
                    coordinator.entriesFlow()
                        .onStart { projectionStarted.complete(Unit) }
                        .takeWhile { snapshot ->
                            collectedSnapshots.add(snapshot)
                            val finalUpdated = snapshot.firstOrNull { it.id == oldLiveEntries[9].id.toHexString() }
                            // 只要条目 9 尚未是最终更新值，就继续收集；一旦等于最终更新值，收集立即结束
                            finalUpdated?.title != "Updated-Title-9"
                        }
                        .collect()
                    collectedSnapshots
                }

                // 等待投影流真正连通并开始
                projectionStarted.await()

                // 并发改树：修改投影实际读取的 TITLE 与 USER_NAME，以及 PASSWORD
                val mutationJob = launch(Dispatchers.Default) {
                    for (i in 0 until 10) {
                        val currentEntry = session.databaseFlow.value?.rootGroup?.findEntry(oldLiveEntries[i].id) ?: continue
                        val updated = currentEntry
                            .withField(KdbxConstants.Fields.TITLE, ProtectedString("Updated-Title-$i", isProtected = false))
                            .withField(KdbxConstants.Fields.USER_NAME, ProtectedString("updated-user-$i", isProtected = false))
                            .withField(KdbxConstants.Fields.PASSWORD, ProtectedString("updated-pass-$i", isProtected = true))
                        session.saveEntry(updated)
                    }
                }

                mutationJob.join()
                val snapshots = projectionDeferred.await()

                assertTrue("应至少采集到投影快照", snapshots.isNotEmpty())
                for (snapshot in snapshots) {
                    assertEquals("快照中条目数应完整", entryCount, snapshot.size)
                    for (ui in snapshot) {
                        val uuid = KdbxUuid.fromHexString(ui.id)
                        val idx = idToIndex[uuid] ?: error("未知条目: ${ui.id}")
                        val oldTitle = "条目-$idx"
                        val newTitle = "Updated-Title-$idx"
                        val oldUser = "user-$idx"
                        val newUser = "updated-user-$idx"

                        // 精确等值匹配一代完整值，杜绝 clear 降级空串或 startswith 掩盖受损
                        assertTrue("标题必须是一代完整值而非降级空串: actual='${ui.title}'", ui.title == oldTitle || ui.title == newTitle)
                        assertTrue("用户名必须是一代完整值而非降级空串: actual='${ui.username}'", ui.username == oldUser || ui.username == newUser)
                    }
                }

                // 验证可观察行为：旧树中被并发替换下线的真实实例在读租约全部结束后已被确定性清零
                for (oldTitle in targetOldTitles) {
                    assertTrue("被替换下线的旧标题在读者结束后必须已清零", oldTitle.cleared)
                }
                for (oldUser in targetOldUserNames) {
                    assertTrue("被替换下线的旧用户名在读者结束后必须已清零", oldUser.cleared)
                }
                for (oldPassword in targetOldPasswords) {
                    assertTrue("被替换下线的旧密码在读者结束后必须已清零", oldPassword.cleared)
                }

                // 正向对照：新树上线的活跃字段与未修改的共享字段绝对不得被清零
                val currentDb = session.databaseFlow.value ?: error("终态数据库为空")
                for (i in 0 until 10) {
                    val activeEntry = currentDb.rootGroup.findEntry(oldLiveEntries[i].id) ?: error("找不到活动条目 $i")
                    assertEquals("Updated-Title-$i", activeEntry.title)
                    assertEquals("updated-user-$i", activeEntry.userName)
                    assertFalse("新上线的活跃标题不得被误清零", activeEntry.fields[KdbxConstants.Fields.TITLE]!!.cleared)
                    assertFalse("新上线的活跃用户名不得被误清零", activeEntry.fields[KdbxConstants.Fields.USER_NAME]!!.cleared)
                    assertFalse("新上线的活跃密码不得被误清零", activeEntry.fields[KdbxConstants.Fields.PASSWORD]!!.cleared)
                }
                val untouchedEntry = currentDb.rootGroup.findEntry(oldLiveEntries[49].id) ?: error("找不到未修改条目 49")
                assertEquals("条目-49", untouchedEntry.title)
                assertFalse("未修改的共享字段不得被误清零", untouchedEntry.fields[KdbxConstants.Fields.TITLE]!!.cleared)
                assertEquals("未修改的共享字段实例必须完全同一", targetUntouchedTitle, untouchedEntry.fields[KdbxConstants.Fields.TITLE])
            }
        } finally {
            session.close()
        }
    }
}
