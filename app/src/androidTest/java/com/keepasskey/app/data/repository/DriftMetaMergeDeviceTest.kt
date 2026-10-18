package com.keepasskey.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.security.ExternalModificationChoice
import com.keepasskey.app.security.VaultFileBaselineHolder
import com.keepasskey.app.security.VaultFileDriftCoordinator
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `ISSUE-P2-659` 的**设备侧漂移合并验收**：外部工具改动的库级 Meta（库名 / 描述 /
 * 默认用户名 / 颜色 / 库级 customData）必须被「合并后保存」合入，本地未改字段保持不变。
 *
 * 走**生产漂移处置链**（[VaultFileDriftCoordinator] + `VaultFileDriftResolve`，与
 * `VaultFileDriftBackupDeviceTest` 同一装置口径）：真实 `File` 通道、真实三方合并、
 * 真实落盘与重解析。每步都从正式 `.kdbx` 文件独立重读，不以会话内存树为唯一证据。
 *
 * 未覆盖（不得据本件外推）：
 * - 真机 Compose 三选 / 裁决按钮点击（本件经生产仓库的处置入口驱动，与 UI 同一实现）；
 * - 同步（云端）面的 Meta 合并与「库设置」卡片逐字段裁决（`ISSUE-P2-659` 的另一路径）；
 * - 独立 KeePass / KeePassXC 读取同一导出库。
 */
@RunWith(AndroidJUnit4::class)
class DriftMetaMergeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val password = "P2-659-Meta-Device-2026".toCharArray()
    private val strings = StringsProvider { id, args -> context.getString(id, *args) }

    private fun uuidOf(hex: String): KdbxUuid =
        KdbxUuid(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private val commonId = uuidOf("00000000000000000000000000000061")
    private val diskOnlyId = uuidOf("00000000000000000000000000000062")

    private fun entry(id: KdbxUuid, title: String, value: String): KdbxEntry = KdbxEntry(
        id = id,
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, false),
            KdbxConstants.Fields.PASSWORD to ProtectedString(value, true)
        )
    )

    private fun rootOf(vararg entries: KdbxEntry) =
        KdbxGroup(id = uuidOf("00000000000000000000000000000060"), name = "Root", entries = entries.toList())

    /** 以当前会话凭据解析正式文件（每步独立重读，不信任内存树）。 */
    private fun parse(file: File): KdbxDatabase =
        KdbxFile.load(file.inputStream(), password.clone())

    /** 外部工具改写：读回正式库、改 Meta 与条目后原位写回（真实字节级外部修改）。 */
    private fun rewriteExternally(file: File, transform: (KdbxDatabase) -> KdbxDatabase) {
        val current = parse(file)
        val rewritten = try {
            transform(current)
        } finally {
            // 变换产物与 current 共享受保护实例（copy 语义），此处只清「未被产物引用」的池
            current.clearSensitiveData()
        }
        try {
            file.outputStream().use { KdbxFile.save(it, rewritten, password.clone()) }
        } finally {
            rewritten.clearSensitiveData()
        }
    }

    @Test
    fun `外部工具单侧改库级Meta必须合入且本地未改字段不变`() = runBlocking {
        val dir = File(context.filesDir, "p2-659-meta-${System.nanoTime()}").apply { mkdirs() }
        val vaultFile = File(dir, "vault.kdbx")
        val session = DatabaseSession()
        try {
            assertTrue(
                "建库必须成功",
                session.create(vaultFile, "P2-659 Meta", password.clone(), useArgon2 = false) is KdbxResult.Success
            )
            // 本地 Meta 基线：库名 / 描述 / 默认用户名 / 颜色 / 库级 customData + 一个「本地未改」字段
            session.updateDatabaseMeta {
                it.copy(
                    rootGroup = rootOf(entry(commonId, "Common", "Base#1")),
                    databaseName = "Local Name",
                    databaseDescription = "Local description",
                    defaultUserName = "local-user",
                    color = "#112233",
                    customData = mapOf("local-key" to "local-value"),
                    historyMaxItems = 42
                )
            }
            assertTrue("本地 Meta 落盘必须成功", session.save() is KdbxResult.Success)
            assertNotNull("落盘后必须登记持久化基线", session.persistedBaselineSnapshot()?.also { it.wipe() })

            // 外部工具（KeePassXC / 官方桌面端）改写 Meta：五个字段全部改，并追加磁盘独有条目改变文件尺寸
            rewriteExternally(vaultFile) { db ->
                db.copy(
                    rootGroup = rootOf(entry(commonId, "Common", "Base#1"), entry(diskOnlyId, "Disk", "Disk#1")),
                    databaseName = "Disk Name",
                    databaseDescription = "Disk description",
                    defaultUserName = "disk-user",
                    color = "#445566",
                    customData = mapOf("local-key" to "local-value", "disk-key" to "disk-value")
                )
            }
            val diskBytes = vaultFile.readBytes()

            // 走生产漂移处置：合并后保存
            val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
            val resolver = VaultFileDriftResolve(session, strings, coordinator)
            val merged = resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("合并后保存必须成功: $merged", merged is KdbxResult.Success)

            val reopened = parse(vaultFile)
            try {
                assertEquals("外部改动的库名必须合入", "Disk Name", reopened.databaseName)
                assertEquals("外部改动的描述必须合入", "Disk description", reopened.databaseDescription)
                assertEquals("外部改动的默认用户名必须合入", "disk-user", reopened.defaultUserName)
                assertEquals("外部改动的颜色必须合入", "#445566", reopened.color)
                assertEquals(
                    "库级 customData 必须按键并集合并（外部新增键保留）",
                    mapOf("local-key" to "local-value", "disk-key" to "disk-value"),
                    reopened.customData
                )
                assertEquals("本地未改字段必须保持不变", 42, reopened.historyMaxItems)
                val passwords = reopened.rootGroup.allEntries()
                    .mapNotNull { it.fields[KdbxConstants.Fields.PASSWORD]?.readString() }
                assertTrue("磁盘独有条目必须并入（实测=$passwords）", "Disk#1" in passwords)
                assertTrue("共有条目必须保留", "Base#1" in passwords)
            } finally {
                reopened.clearSensitiveData()
            }
            // 正式文件确实被改写（合并产物已落盘，而非只看内存）
            assertTrue(
                "合并产物必须已落盘（正式文件字节已变化）",
                !vaultFile.readBytes().contentEquals(diskBytes)
            )
        } finally {
            runCatching { session.lock() }
            runCatching { session.close() }
            runCatching { dir.deleteRecursively() }
        }
    }

    @Test
    fun `库级Meta双侧异值先待裁决再按磁盘侧落定`() = runBlocking {
        val dir = File(context.filesDir, "p2-659-meta-conflict-${System.nanoTime()}").apply { mkdirs() }
        val vaultFile = File(dir, "vault.kdbx")
        val session = DatabaseSession()
        try {
            assertTrue(
                "建库必须成功",
                session.create(vaultFile, "P2-659 Meta Conflict", password.clone(), useArgon2 = false) is KdbxResult.Success
            )
            session.updateDatabaseMeta {
                it.copy(rootGroup = rootOf(entry(commonId, "Common", "Base#1")), databaseName = "Local Name")
            }
            assertTrue("本地 Meta 落盘必须成功", session.save() is KdbxResult.Success)

            // 本地侧（未落盘）：直接改内存树，基线仍停在落盘版本 ⇒ 构成「双侧异值」
            session.updateDatabaseMeta { it.copy(databaseName = "Local Changed") }

            // 外部工具把同一字段改成另一个值
            rewriteExternally(vaultFile) { db ->
                db.copy(
                    rootGroup = rootOf(entry(commonId, "Common", "Base#1"), entry(diskOnlyId, "Disk", "Disk#1")),
                    databaseName = "Disk Changed"
                )
            }
            val beforeDecision = vaultFile.readBytes()

            val coordinator = VaultFileDriftCoordinator(VaultFileBaselineHolder(session), session)
            val resolver = VaultFileDriftResolve(session, strings, coordinator)

            // 无策略：同字段双侧异值不得静默取一侧——如实失败并置起待裁决提示
            val pending = resolver.resolve(ExternalModificationChoice.MERGE_AND_SAVE)
            assertTrue("双侧异值不得静默提交: $pending", pending is KdbxResult.Failure)
            assertNotNull("必须置起待裁决提示", coordinator.mergeConflict.value)
            assertTrue(
                "裁决前不得写盘",
                vaultFile.readBytes().contentEquals(beforeDecision)
            )

            // 用户裁决「以磁盘为准」：该字段取磁盘值并落盘
            val decided = resolver.resolve(ExternalModificationChoice.MERGE_KEEP_DISK)
            assertTrue("以磁盘为准的裁决必须落盘成功: $decided", decided is KdbxResult.Success)
            val reopened = parse(vaultFile)
            try {
                assertEquals("裁决后库名必须取磁盘值", "Disk Changed", reopened.databaseName)
            } finally {
                reopened.clearSensitiveData()
            }
        } finally {
            runCatching { session.lock() }
            runCatching { session.close() }
            runCatching { dir.deleteRecursively() }
        }
    }
}
