package com.keepasskey.app.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.ui.screens.conflict.GroupResolutionMode
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P2-660`：云端同步路径的**分组**同字段冲突必须进待决面，并由真实冲突页逐组裁决。
 *
 * 验收口径（与 `ACTIVE_ISSUES` 的关闭条件对齐）：
 * 1. 两端各自改**同一分组**的同名字段 ⇒ 同步产物必须带 `groupConflicts`，且**零上传**（不进静默 LWW）；
 * 2. 生产冲突页逐组选择「取本地 / 取云端 / 双方保留」并确认推送 ⇒ 落库与上传完成、冲突流清空；
 * 3. 另一端重新同步后回读到裁决结果（含「双方保留」生成的云端冲突副本）；
 * 4. 「双方保留」在多轮（A 上传 → B 同步 → B 再上传 → A 同步）后仍稳定，不重复增殖副本。
 *
 * 前置：真实 HTTPS S3 接收方（`tools/local-sync` 实验室服务）经 runner 参数传入 endpoint / bucket /
 * 凭据；缺参即 fail-closed，不允许在 mock 上冒充通过。
 */
@RunWith(AndroidJUnit4::class)
class SyncGroupConflictDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 冲突页滚动 / 点击依赖无障碍树：先打开可检索窗口标志（否则拿不到活动窗口）。 */
    private fun enableAccessibilityRetrieval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val info = instrumentation.uiAutomation.serviceInfo
            ?: android.accessibilityservice.AccessibilityServiceInfo()
        info.flags = info.flags or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        instrumentation.uiAutomation.serviceInfo = info
    }

    @Test
    fun 两端改同一分组_逐组裁决后对端回读且双方保留多轮稳定() = runBlocking {
        enableAccessibilityRetrieval()
        val fixture = SyncGroupConflictDeviceFixture(context)
        try {
            val peerA = fixture.peer("A")
            val peerB = fixture.peer("B")
            fixture.create(peerA)
            fixture.create(peerB)

            // 初始基线：两端各建同名分组并同步出共同版本。
            seedGroup(peerA.session, GROUP_NAME, LOCAL_EDITED_NAME)
            // 首次同步是「建立远端基线」：成功形态可能是 UploadedLocal / MergedAndUploaded / UpToDate
            // （远端尚无对象时上传即基线），三者都表示已与远端收敛。
            // 诊断：先用观察者 provider 直接探测一次，确认接收方与证书链路可用。
            val probe = fixture.observer.getMetadata(fixture.remotePath)
            assertTrue(
                "实验室 S3 接收方必须可达：${probe.exceptionOrNull()?.javaClass?.name} " +
                    "${probe.exceptionOrNull()?.message}",
                probe.isSuccess || probe.exceptionOrNull() is com.keepasskey.sync.model.SyncException.FileNotFound
            )
            val aFirst = fixture.sync(peerA)
            assertTrue("A 首轮同步必须收敛：$aFirst", aFirst.converged())
            val bFirst = fixture.sync(peerB)
            assertTrue("B 首轮同步必须收敛：$bFirst", bFirst.converged())

            // 两端改同一分组的组名 ⇒ 同字段双侧异值。
            renameGroup(peerA.session, GROUP_NAME, LOCAL_EDITED_NAME)
            renameGroup(peerB.session, GROUP_NAME, REMOTE_EDITED_NAME)
            val firstUpload = fixture.sync(peerA)
            assertTrue("A 的改名必须真实上传：$firstUpload", firstUpload.uploadedOrMerged())

            // B 同步：必须进入待决面的**分组**冲突，且本轮不上传、不静默定案。
            val conflicted = fixture.sync(peerB)
            assertTrue("B 同步必须停在待决冲突：$conflicted", conflicted is SyncOutcome.ConflictNeedsUser)
            val conflicts = (conflicted as SyncOutcome.ConflictNeedsUser)
            assertTrue("冲突必须包含分组项", conflicts.groupConflicts.isNotEmpty())
            assertEquals("分组冲突必须指向同一分组", 1, conflicts.groupConflicts.size)

            val pair = conflicts.groupConflicts.single()
            val groupId = pair.groupId
            val localName = pair.localGroup.name
            val remoteName = pair.remoteGroup.name
            assertEquals("本地侧应是 B 的改名", REMOTE_EDITED_NAME, localName)
            assertEquals("远端侧应是 A 的改名", LOCAL_EDITED_NAME, remoteName)

            // 真实冲突页：逐组选择「取本地」并确认推送。
            SyncGroupConflictDeviceUi().resolve(
                peerB.coordinator,
                mapOf(groupId to (pair.groupName to GroupResolutionMode.KEEP_LOCAL))
            )
            assertTrue("裁决后分组冲突流必须清空", peerB.coordinator.groupConflictFlow.value.isEmpty())

            // A 重新同步回读：必须看到 B 的取名（KEEP_LOCAL 胜出）。
            val afterResolve = fixture.sync(peerA)
            assertTrue("A 回读同步必须成功：$afterResolve", afterResolve.uploadedOrMerged() || afterResolve is SyncOutcome.UpToDate)
            assertEquals(
                "对端必须回读到裁决结果",
                setOf(REMOTE_EDITED_NAME),
                conflictScopeNames(fixture.loadFile(peerA))
            )

            // 多轮「双方保留」稳定性：再制造一次同字段分叉，选双方保留后两端来回同步。
            renameGroup(peerA.session, CASE_FIRST_NAME, "Round2-A")
            assertTrue("第二轮 A 上传必须成功", fixture.sync(peerA).uploadedOrMerged())
            renameGroup(peerB.session, CASE_FIRST_NAME, "Round2-B")
            val round2 = fixture.sync(peerB)
            assertTrue("第二轮必须再次进入待决：$round2", round2 is SyncOutcome.ConflictNeedsUser)
            val round2Conflict = (round2 as SyncOutcome.ConflictNeedsUser).groupConflicts.single()
            SyncGroupConflictDeviceUi().resolve(
                peerB.coordinator,
                mapOf(round2Conflict.groupId to (round2Conflict.groupName to GroupResolutionMode.DUPLICATE_BOTH))
            )

            val namesAfterDuplicate = conflictScopeNames(fixture.loadFile(peerB))
            // 「双方保留」的落地形态：本地侧保留原名，远端侧内容复制为**带冲突副本后缀**的分组
            // （与漂移面「磁盘冲突副本」同一原语，后缀为「云端冲突副本」）。
            assertTrue(
                "双方保留必须同时留下本地侧与云端副本：$namesAfterDuplicate",
                namesAfterDuplicate.contains("Round2-B") &&
                    namesAfterDuplicate.any { it.startsWith("Round2-A") && it != "Round2-A" }
            )

            // 再往返一轮：副本不得重复增殖。
            fixture.sync(peerA)
            fixture.sync(peerB)
            fixture.sync(peerA)
            val stable = conflictScopeNames(fixture.loadFile(peerA))
            assertEquals(
                "多轮同步后双方保留的分组名集合必须稳定（本地侧 + 恰好一个云端副本，不重复增殖）：$stable",
                2,
                stable.size
            )
            assertTrue("稳定集合必须含本地侧", stable.contains("Round2-B"))
            assertTrue("稳定集合必须含云端副本", stable.any { it.startsWith("Round2-A") && it != "Round2-A" })
        } finally {
            fixture.close()
        }
    }

    private suspend fun seedGroup(session: DatabaseSession, name: String, firstRename: String) {
        assertTrue(
            "种子分组创建失败",
            session.saveGroup(
                com.keepasskey.core.model.KdbxGroup(
                    id = com.keepasskey.core.model.KdbxUuid.random(),
                    name = name
                )
            ) == Unit
        )
        assertTrue("种子分组落盘失败", session.save() is KdbxResult.Success)
        assertTrue("种子分组必须落盘", groupNames(session).contains(name))
        // `firstRename` 仅用于让首轮基线内容与后续改名不同（避免首轮被判定为无变化）
        assertNotEquals(name, firstRename)
    }

    private suspend fun renameGroup(session: DatabaseSession, from: String, to: String) {
        val group = session.databaseFlow.value!!.rootGroup.allGroups().single { it.name == from }
        session.saveGroup(group.copy(name = to))
        assertTrue("改名落盘失败", session.save() is KdbxResult.Success)
    }

    private fun groupNames(session: DatabaseSession): Set<String> =
        session.databaseFlow.value!!.rootGroup.allGroups()
            .filterNot { it.name == ROOT_NAME }
            .map { it.name }
            .toSet()

    /** 只取本用例参与冲突的分组名（排除库自带根分组与其默认子组），避免把种子分组算进裁决结果。 */
    private fun conflictScopeNames(db: com.keepasskey.database.file.KdbxDatabase): Set<String> =
        db.rootGroup.allGroups().map { it.name }
            .filter { it.startsWith(CASE_PREFIX) || it.startsWith(ROUND2_PREFIX) }
            .toSet()

    private fun SyncOutcome.uploadedOrMerged(): Boolean =
        this is SyncOutcome.UploadedLocal || this is SyncOutcome.MergedAndUploaded

    private fun SyncOutcome.converged(): Boolean =
        uploadedOrMerged() || this is SyncOutcome.UpToDate

    private companion object {
        const val GROUP_NAME = "P2-660-Synthetic"
        const val LOCAL_EDITED_NAME = "P2-660-From-A"
        const val REMOTE_EDITED_NAME = "P2-660-From-B"
        const val ROOT_NAME = "Root"
        const val CASE_PREFIX = "P2-660-"
        /** 第二轮（「双方保留」）使用的分组名前缀。 */
        const val ROUND2_PREFIX = "Round2-"

        /** 第一轮裁决后两端共同的分组名（第二轮改名的起点）。 */
        const val CASE_FIRST_NAME = "P2-660-From-B"
    }
}
