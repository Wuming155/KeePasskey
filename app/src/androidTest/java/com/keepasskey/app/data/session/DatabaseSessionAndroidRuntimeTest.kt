package com.keepasskey.app.data.session

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.app.data.repository.VaultEntryMapper
import com.keepasskey.app.data.repository.VaultEntryQueryCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * 解锁端到端 + 大附件落盘的**设备侧（instrumented）**回归（ISSUE-P2-27 解锁链路 / ISSUE-P2-24 AC②③）。
 *
 * ## 为什么必须有这一层
 *
 * 解锁链路（复合密钥派生 → 外层 Header 校验 → HMAC 块流 → 解密 → 解压 → 内层 Header → XML）
 * 在设备侧依赖 Rust 原生内核、`MessageDigest`/`Cipher` 的平台实现与真实文件系统语义；
 * 而 ISSUE-P2-24 的「附件落盘 + 权限 0600/0700 + 锁定即清理」只能由真实 POSIX 文件系统验证
 * （宿主 JVM 走的是降级分支，验不到真实权限位）。
 *
 * 本用例用**生产写入管线**现场产出 `.kdbx`，再经生产 [DatabaseSession] 解锁，
 * 断言：大附件落盘且权限收敛、字节往返等价、锁定后缓存目录被清空。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSessionAndroidRuntimeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheDir = File(context.cacheDir, "attachments")

    private fun cacheFiles(): List<File> =
        cacheDir.listFiles { file -> file.isFile && file.name.endsWith(".cache") }?.toList() ?: emptyList()

    private fun kdbxFileWith(attachment: ByteArray): File {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("设备侧", isProtected = false)),
                        attachments = listOf(KdbxAttachment("blob.bin", data = attachment))
                    )
                )
            )
        )
        val file = File(context.cacheDir, "device-session-test.kdbx")
        file.delete()
        FileOutputStream(file).use { KdbxFile.save(it, db, PASSWORD) }
        return file
    }

    @Test
    fun `带大附件密码库在设备侧解锁后附件落盘且锁定即清理`() = runBlocking<Unit> {
        cacheDir.deleteRecursively()
        val big = ByteArray(1_200_000) { (it % 251).toByte() }
        val file = kdbxFileWith(big)

        val store = FileBinaryStore(context)
        val session = DatabaseSession(store).apply { addLockObserver(store) }

        val openResult = session.open(file, PASSWORD)
        assertTrue(
            "解锁失败: ${(openResult as? KdbxResult.Failure)?.error?.javaClass?.name}",
            openResult is KdbxResult.Success
        )
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)

        val cached = cacheFiles()
        assertEquals("超过 1 MiB 的附件必须落盘", 1, cached.size)
        assertEquals(
            "落盘文件必须收敛为仅属主可读写",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(cached.single().toPath())
        )
        assertEquals(
            "缓存目录必须收敛为 0700",
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ),
            Files.getPosixFilePermissions(cacheDir.toPath())
        )

        val attachment = session.databaseFlow.value!!.rootGroup.entries.single().attachments.single()
        assertArrayEquals("设备侧落盘往返必须逐字节等价", big, attachment.data)

        session.lock()
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertTrue("锁定后附件缓存必须清空", cacheFiles().isEmpty())

        file.delete()
    }

    @Test
    fun `阈值以下附件不落盘`() = runBlocking<Unit> {
        cacheDir.deleteRecursively()
        val file = kdbxFileWith(ByteArray(4096) { it.toByte() })

        val store = FileBinaryStore(context)
        val session = DatabaseSession(store).apply { addLockObserver(store) }

        assertTrue(session.open(file, PASSWORD) is KdbxResult.Success)
        assertTrue("阈值以内的附件不得落盘", cacheFiles().isEmpty())

        session.close()
        assertTrue(cacheFiles().isEmpty())

        file.delete()
    }

    /**
     * `ENG-20261028-11` 设备侧性能探针与并发一致性回归：
     * 3000 合成条目（15,000 字段、6,000 受保护实例）真实 [VaultEntryQueryCoordinator.entriesFlow]
     * 投影与并发改树落盘（真实更改 title/userName/password）。
     *
     * 验证与测量：
     * 1. 真实旧实例从 open 后的 session.databaseFlow 捕获，并发改树后断言旧实例在投影读者结束后确定性清零；
     * 2. 并发改树实际修改投影读取的 title 与 userName，断言投影结果为一代完整值而非 clear 降级空串，
     *    按条目 ID 精确等值匹配（旧值或新值），杜绝 startswith 掩盖受损；
     * 3. 统计多次样本输出 min/median/max 读/投影/改树保存/回收的可观察耗时，正确性断言不设人工硬 SLA 阈值；
     * 4. finally 严格保证 session.close()、临时库清理与独占缓冲擦除，失败不留残余。
     */
    @Test
    fun `entriesFlow投影与并发改树保存3000条目成本与回收验证`() = runBlocking<Unit> {
        val entryCount = 3000
        val testFile = File(context.cacheDir, "scale-3000-probe.kdbx")
        val session = DatabaseSession()
        val seedEntries = ArrayList<KdbxEntry>(entryCount)

        try {
            for (i in 0 until entryCount) {
                seedEntries.add(
                    KdbxEntry(
                        id = KdbxUuid.random(),
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("Scale-Title-$i", isProtected = false),
                            KdbxConstants.Fields.USER_NAME to ProtectedString("user$i@scale.test", isProtected = false),
                            KdbxConstants.Fields.PASSWORD to ProtectedString("Sec#Pass-$i", isProtected = true),
                            KdbxConstants.Fields.URL to ProtectedString("https://app-$i.scale.test", isProtected = false),
                            KdbxConstants.Fields.NOTES to ProtectedString("Notes-$i", isProtected = true)
                        )
                    )
                )
            }
            val db = KdbxDatabase(
                header = KdbxHeader.createDefault(useArgon2 = false),
                rootGroup = KdbxGroup(name = "Root", entries = seedEntries)
            )
            testFile.delete()
            FileOutputStream(testFile).use { KdbxFile.save(it, db, PASSWORD) }

            // 必须从 session.open 打开后的树中抓取生产真实实例
            val openResult = session.open(testFile, PASSWORD)
            assertTrue("3000 条目数据库打开失败: ${(openResult as? KdbxResult.Failure)?.error}", openResult is KdbxResult.Success)

            val openedDb = session.databaseFlow.value ?: error("打开后内存数据库为空")
            val liveEntries = openedDb.rootGroup.allEntries()
            assertEquals("打开后的条目数须为 3000", entryCount, liveEntries.size)

            // 建立 UUID -> 序号映射以便 O(1) 精确对齐
            val idToIndex = HashMap<KdbxUuid, Int>(entryCount)
            liveEntries.forEachIndexed { idx, e -> idToIndex[e.id] = idx }

            val mapper = VaultEntryMapper(StringsProvider { _, _ -> "" })
            val coordinator = VaultEntryQueryCoordinator(session, mapper, Dispatchers.Default)

            // 多轮采样测量真实成本
            val rounds = 3
            val projectionTimes = LongArray(rounds)
            val mutationTimes = LongArray(rounds)

            for (round in 0 until rounds) {
                // 每轮选取不同区间的 20 条条目进行改树（修改投影实际读取的 TITLE 与 USER_NAME，以及 PASSWORD）
                val mutateOffset = round * 20
                val targetOldEntries = (mutateOffset until mutateOffset + 20).map { liveEntries[it] }
                val targetOldTitles = targetOldEntries.map { it.fields[KdbxConstants.Fields.TITLE]!! }
                val targetOldUserNames = targetOldEntries.map { it.fields[KdbxConstants.Fields.USER_NAME]!! }
                val targetOldPasswords = targetOldEntries.map { it.fields[KdbxConstants.Fields.PASSWORD]!! }

                val mutateLatch = java.util.concurrent.CountDownLatch(1)

                // 并发执行：Default 调度器收集投影，IO 调度器并发改树并保存
                val projectionDeferred = async(Dispatchers.Default) {
                    val startNs = SystemClock.elapsedRealtime()
                    // 真实 entriesFlow 投影内部持有读租约，不增加外层额外租约掩盖
                    val snapshot = coordinator.entriesFlow().first()
                    projectionTimes[round] = SystemClock.elapsedRealtime() - startNs
                    snapshot
                }

                val mutationDeferred = async(Dispatchers.IO) {
                    mutateLatch.countDown()
                    val startNs = SystemClock.elapsedRealtime()
                    for (i in mutateOffset until mutateOffset + 20) {
                        val targetId = liveEntries[i].id
                        session.updateEntryById(targetId) { entry ->
                            entry.withField(KdbxConstants.Fields.TITLE, ProtectedString("Updated-Title-$i", false))
                                 .withField(KdbxConstants.Fields.USER_NAME, ProtectedString("updated-user$i@scale.test", false))
                                 .withField(KdbxConstants.Fields.PASSWORD, ProtectedString("NewSec#Pass-$i", true))
                        }
                    }
                    val saveResult = session.save()
                    mutationTimes[round] = SystemClock.elapsedRealtime() - startNs
                    assertTrue("第 $round 轮并发改树保存失败", saveResult is KdbxResult.Success)
                }

                val snapshot = projectionDeferred.await()
                mutationDeferred.await()

                // 正确性校验 1：投影快照规模严格完整
                assertEquals("第 $round 轮投影条目数必须为 3000", entryCount, snapshot.size)

                // 正确性校验 2：投影结果必须为一代完整值而非 clear 降级空串，精确等值匹配旧值或新值
                for (ui in snapshot) {
                    val uuid = KdbxUuid.fromHexString(ui.id)
                    val idx = idToIndex[uuid] ?: error("未知条目 ID: ${ui.id}")
                    val oldTitle = "Scale-Title-$idx"
                    val newTitle = "Updated-Title-$idx"
                    val oldUser = "user$idx@scale.test"
                    val newUser = "updated-user$idx@scale.test"

                    assertTrue(
                        "条目 $idx 标题既非旧值也非新值，可能已损坏或降级为空串: actual='${ui.title}'",
                        ui.title == oldTitle || ui.title == newTitle
                    )
                    assertTrue(
                        "条目 $idx 用户名既非旧值也非新值，可能已损坏或降级为空串: actual='${ui.username}'",
                        ui.username == oldUser || ui.username == newUser
                    )
                }

                // 正确性校验 3：旧树中被替换下线的真实实例在读租约结束后必须已确定性清零
                for (oldTitle in targetOldTitles) {
                    assertTrue("被替换下线的旧标题在读者退出后必须已清零", oldTitle.cleared)
                }
                for (oldUser in targetOldUserNames) {
                    assertTrue("被替换下线的旧用户名在读者退出后必须已清零", oldUser.cleared)
                }
                for (oldPass in targetOldPasswords) {
                    assertTrue("被替换下线的旧密码在读者退出后必须已清零", oldPass.cleared)
                }
            }

            // 正向对照：新上线的活跃字段与未修改的共享字段绝对不得被清零
            val currentDb = session.databaseFlow.value ?: error("终态数据库为空")
            for (i in 0 until 60) {
                val live = currentDb.rootGroup.findEntry(liveEntries[i].id) ?: error("找不到更新条目 $i")
                assertEquals("Updated-Title-$i", live.title)
                assertEquals("updated-user$i@scale.test", live.userName)
                assertFalse("新上线的活跃字段不得被清零", live.fields[KdbxConstants.Fields.TITLE]!!.cleared)
            }
            val untouched = currentDb.rootGroup.findEntry(liveEntries[2999].id) ?: error("找不到未修改条目 2999")
            assertEquals("Scale-Title-2999", untouched.title)
            assertFalse("未修改共享字段不得被清零", untouched.fields[KdbxConstants.Fields.TITLE]!!.cleared)

            // 计算 min / median / max 真实读数
            projectionTimes.sort()
            mutationTimes.sort()
            val projMin = projectionTimes.first()
            val projMed = projectionTimes[rounds / 2]
            val projMax = projectionTimes.last()
            val mutMin = mutationTimes.first()
            val mutMed = mutationTimes[rounds / 2]
            val mutMax = mutationTimes.last()

            val statsReport = "ENG11ScaleProbe 3000条目样本读数: " +
                "投影耗时 min=${projMin}ms, median=${projMed}ms, max=${projMax}ms; " +
                "改树保存耗时 min=${mutMin}ms, median=${mutMed}ms, max=${mutMax}ms"
            Log.i("ENG11ScaleProbe", statsReport)

            // 写入本地缓存文件，供提取到 build/eng11-lease-evidence
            File(context.cacheDir, "eng11_lease_probe.txt").writeText(statsReport)

        } finally {
            // 无论成功失败，严防任何测试残留
            runCatching { session.close() }
            testFile.delete()
            seedEntries.clear()
        }
    }

    private companion object {
        /** 伪造测试口令（敏感纪律：不写入断言消息）。 */
        val PASSWORD = "DeviceSession#2026".toCharArray()
    }
}
