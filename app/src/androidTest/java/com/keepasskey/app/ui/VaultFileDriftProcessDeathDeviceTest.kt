package com.keepasskey.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.repository.VaultDatabaseCatalog
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.di.AutofillDeviceGraph
import com.keepasskey.app.di.DriftDeviceGraph
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ISSUE-P1-631`（生命周期收口）：**进程死亡**验收（第二段）。
 *
 * 为什么单独成类：进程死亡需要**外部编排**（`adb shell am force-stop` 由宿主机在
 * 前置段卡门后执行），单次 instrumentation 无法自杀后继续断言；把它混进常规 UI 类会让
 * 常规套件依赖外部时序。因此拆成两段：
 * 1. 前置段 `VaultFileDriftUiPathDeviceTest#UI驱动_进程死亡前置_提交卡在临界区前`
 *    ——把提交卡在临界区前门，写提交前磁盘快照与到门标记，等待被杀；
 * 2. 本类 —— **新进程**中核对现场：文件逐字节未变、无「已处理」残留基线、重开成功。
 *
 * 本类自包含（不依赖主类私有装置），可用 `am instrument -e class` 单独驱动。
 */
@RunWith(AndroidJUnit4::class)
class VaultFileDriftProcessDeathDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "P1-631-UiPath-2026".toCharArray()

    private val repository: VaultRepository
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, AutofillDeviceGraph::class.java
        ).vaultRepository()
    private val session: DatabaseSession
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, AutofillDeviceGraph::class.java
        ).databaseSession()
    private val coordinator: VaultFileDriftCoordinator
        get() = EntryPointAccessors.fromApplication(
            context.applicationContext, DriftDeviceGraph::class.java
        ).vaultFileDriftCoordinator()

    @Test
    fun `进程死亡后重开_无已处理残留且文件未变`() {
        val dir = File(context.cacheDir, "p1631-ui-pd")
        val file = File(dir, "vault.kdbx")
        val snapshot = File(dir, "before.bin")
        try {
            assertTrue("进程死亡前置现场必须存在（先跑前置段）", file.exists() && snapshot.exists())
            // ① 正式文件必须与提交前逐字节一致（无半步 / 无已提交产物）
            assertTrue(
                "进程死亡后正式文件必须与提交前逐字节一致",
                snapshot.readBytes().contentEquals(file.readBytes())
            )
            // ② 文件必须仍是合法 KDBX，且内容为提交前的磁盘版本（正式重解析，不搜原始字节）
            val passwords = KdbxFile.load(ByteArrayInputStream(file.readBytes()), password.clone())
                .rootGroup.allEntries()
                .mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
                .toSet()
            assertTrue("重开前磁盘内容必须仍是提交前版本（实测=$passwords）", "Disk#1" in passwords)
            assertTrue("未提交的本地新增条目不得出现在磁盘（实测=$passwords）", "LocalOnly#1" !in passwords)

            // ③ 新进程在**打开前**不得有「已处理」残留：持久化基线是会话级材料，
            // 进程死亡即消失——此处断言的是「上一个进程没有把任何已处理状态落到进程外」
            assertNull(
                "新进程（打开前）不得有上一进程的持久化基线残留",
                runBlocking { session.persistedBaselineSnapshot() }
            )

            // ④ 重开同一库（不重写文件：直接登记 + 选中 + 解锁）
            val imported = runBlocking {
                repository.importExternalDatabase("P1631-PD", file.absolutePath)
            }
            assertTrue("登记必须成功: $imported", imported is KdbxResult.Success)
            runBlocking { repository.selectDatabase(file.absolutePath) }
            val unlocked = runBlocking {
                repository.unlockActiveDatabase(password.clone(), null, readOnly = false)
            }
            assertTrue("重开必须成功: $unlocked", unlocked is KdbxResult.Success)

            // ⑤ 打开即登记基线（六类确认写盘点之一）——基线必须就是**当前磁盘版本**，
            // 不得声称合并产物已落盘：漂移判定必须为「无漂移」，且磁盘仍无本地新增条目
            assertTrue(
                "打开后不得声称存在漂移（基线必须等于当前磁盘版本）",
                !coordinator.checkDrift(session.currentPathIdentifier, file)
            )
            val passwordsAfterOpen = KdbxFile.load(
                ByteArrayInputStream(file.readBytes()), password.clone()
            ).rootGroup.allEntries()
                .mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
                .toSet()
            assertTrue(
                "重开后磁盘仍不得含未提交的本地新增（实测=$passwordsAfterOpen）",
                "LocalOnly#1" !in passwordsAfterOpen
            )
            assertNull("重开后不得有残留待决提示", coordinator.mergeConflict.value)
            assertNull("重开后不得有残留三选提示", coordinator.pending.value)
        } finally {
            runCatching { runBlocking { repository.lockDatabase() } }
            runCatching {
                runBlocking {
                    val catalog = VaultDatabaseCatalog(context, { _, _ -> "" }, session)
                    catalog.saveKnownDatabases(
                        catalog.loadKnownDatabases().filterNot { it.path == file.absolutePath }
                    )
                }
            }
        }
    }
}
