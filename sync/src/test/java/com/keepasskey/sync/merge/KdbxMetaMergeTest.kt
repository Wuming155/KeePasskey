package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `ISSUE-P2-659`：库级 Meta 三方合并。
 *
 * 核心回归：**外部工具单侧修改的 Meta 必须被合入**（整改前合并产物恒取本地，外部改动在
 * 「合并后保存」时被永久覆盖且无提示）。其次是双侧异值的裁决语义：有元数据时间戳按较新者，
 * 时间戳无法区分先后时登记冲突而不静默定案。
 */
class KdbxMetaMergeTest {

    private val t0 = Instant.parse("2026-10-01T00:00:00Z")
    private val t1 = Instant.parse("2026-10-02T00:00:00Z")
    private val t2 = Instant.parse("2026-10-03T00:00:00Z")

    @Test
    fun `远端单侧修改的库名被合入`() {
        val base = meta(name = "旧库名", nameChanged = t0)
        val local = base
        val remote = meta(name = "外部新库名", nameChanged = t1)

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals("外部工具改的库名必须进入合并产物", "外部新库名", result.meta.databaseName)
        assertEquals("时间戳随取值侧对齐", t1, result.meta.databaseNameChanged)
        assertTrue("单侧修改不产生冲突", result.conflicts.isEmpty())
    }

    @Test
    fun `本地单侧修改被保留且不被远端旧值覆盖`() {
        val base = meta(description = "旧描述", descriptionChanged = t0)
        val local = base.copy(databaseDescription = "本地新描述", databaseDescriptionChanged = t1)
        val remote = base

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals("本地新描述", result.meta.databaseDescription)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `双侧同值不产生冲突`() {
        val base = meta(name = "旧库名")
        val local = meta(name = "同名", nameChanged = t1)
        val remote = meta(name = "同名", nameChanged = t2)

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals("同名", result.meta.databaseName)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `双侧异值按元数据时间戳取较新者`() {
        val base = meta(name = "旧库名", nameChanged = t0)
        val local = meta(name = "本地库名", nameChanged = t1)
        val remote = meta(name = "远端库名", nameChanged = t2)

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals("时间戳较新的一侧胜出（协议自身语义，不是无据 LWW）", "远端库名", result.meta.databaseName)
        assertTrue("有时间戳可裁时不登记冲突", result.conflicts.isEmpty())
    }

    @Test
    fun `双侧异值且时间戳相同登记冲突并临时保留本地`() {
        val base = meta(name = "旧库名", nameChanged = t0)
        val local = meta(name = "本地库名", nameChanged = t1)
        val remote = meta(name = "远端库名", nameChanged = t1)

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals("无法区分先后时临时取本地，不得静默取远端", "本地库名", result.meta.databaseName)
        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.single()
        assertEquals(KdbxMetaConflictKeys.NAME, conflict.fieldKey)
        assertEquals("本地库名", conflict.localValue)
        assertEquals("远端库名", conflict.remoteValue)
    }

    @Test
    fun `双侧异值且均无时间戳登记冲突`() {
        val result = KdbxMetaMerge.merge(
            base = null,
            local = meta(defaultUserName = "alpha"),
            remote = meta(defaultUserName = "beta")
        )

        assertEquals(1, result.conflicts.size)
        assertEquals(KdbxMetaConflictKeys.DEFAULT_USER_NAME, result.conflicts.single().fieldKey)
    }

    @Test
    fun `无可信基线时按时间戳裁决`() {
        val result = KdbxMetaMerge.merge(
            base = null,
            local = meta(name = "本地", nameChanged = t1),
            remote = meta(name = "远端", nameChanged = t2)
        )

        assertEquals("远端", result.meta.databaseName)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `customData 键并集与双侧异值冲突`() {
        val base = meta(customData = mapOf("a" to "1", "b" to "2"))
        val local = meta(customData = mapOf("a" to "1", "b" to "本地值", "localOnly" to "x"))
        val remote = meta(customData = mapOf("a" to "翻新", "b" to "远端值", "remoteOnly" to "y"))

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals("单侧修改取该侧", "翻新", result.meta.customData["a"])
        assertEquals("双侧新增的键都保留", "x", result.meta.customData["localOnly"])
        assertEquals("y", result.meta.customData["remoteOnly"])
        assertEquals("双侧异值取本地并登记", "本地值", result.meta.customData["b"])
        val conflict = result.conflicts.single()
        assertEquals(KdbxMetaConflictKeys.customDataKey("b"), conflict.fieldKey)
    }

    @Test
    fun `无底版时单侧独有的 customData 键按并集保留`() {
        val result = KdbxMetaMerge.merge(
            base = null,
            local = meta(customData = mapOf("localKey" to "L")),
            remote = meta(customData = mapOf("remoteKey" to "R"))
        )

        assertEquals("本地独有键不得丢失", "L", result.meta.customData["localKey"])
        assertEquals("远端独有键不得丢失", "R", result.meta.customData["remoteKey"])
        assertTrue("单侧存在不是冲突", result.conflicts.isEmpty())
    }

    @Test
    fun `历史维护参数按 settingsChanged 裁决`() {
        val base = meta(historyMaxItems = 10, settingsChanged = t0)
        val local = meta(historyMaxItems = 20, settingsChanged = t1)
        val remote = meta(historyMaxItems = 30, settingsChanged = t2)

        val result = KdbxMetaMerge.merge(base, local, remote)

        assertEquals(30, result.meta.historyMaxItems)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `字段级裁决可把冲突字段改为云端值`() {
        val base = meta(name = "旧库名", nameChanged = t0)
        val local = meta(name = "本地库名", nameChanged = t1, color = "#111111")
        val remote = meta(name = "远端库名", nameChanged = t1, color = "#222222")
        val merged = KdbxMetaMerge.merge(base, local, remote)
        val nameKey = KdbxMetaConflictKeys.NAME

        val resolved = KdbxMetaMerge.resolveFieldsOnly(
            merged = merged.meta,
            conflicts = merged.conflicts,
            choices = mapOf(nameKey to FieldResolutionChoice.KEEP_REMOTE)
        )

        assertEquals("远端库名", resolved.databaseName)
        assertEquals("未钦点的字段保持合并产物（本地）", "#111111", resolved.color)
    }

    @Test
    fun `未知差异键的安全 no-op`() {
        val merged = meta(name = "本地")
        val resolved = KdbxMetaMerge.resolveFieldsOnly(
            merged = merged,
            conflicts = listOf(MetaFieldConflict("meta.unknown", "a", "b")),
            choices = mapOf("meta.unknown" to FieldResolutionChoice.KEEP_REMOTE)
        )
        assertEquals("本地", resolved.databaseName)
    }

    @Test
    fun `未提供 Meta 镜像时合并器不产出 Meta`() {
        val root = KdbxGroup(id = KdbxUuid(ByteArray(16) { 1 }), name = "根")
        val result = KdbxMerger.mergeDatabases(
            base = KdbxDatabaseLite(rootGroup = root),
            local = KdbxDatabaseLite(rootGroup = root),
            remote = KdbxDatabaseLite(rootGroup = root, meta = meta(name = "远端"))
        )

        assertNull("local 未提供 Meta ⇒ 不参与合并（导入路径语义：Meta 保持本地）", result.mergedMeta)
        assertTrue(result.metaConflicts.isEmpty())
    }

    @Test
    fun `两条镜像都提供 Meta 时合并器产出合并结果`() {
        val root = KdbxGroup(id = KdbxUuid(ByteArray(16) { 1 }), name = "根")
        val result = KdbxMerger.mergeDatabases(
            base = KdbxDatabaseLite(rootGroup = root, meta = meta(name = "旧库名", nameChanged = t0)),
            local = KdbxDatabaseLite(rootGroup = root, meta = meta(name = "旧库名", nameChanged = t0)),
            remote = KdbxDatabaseLite(rootGroup = root, meta = meta(name = "新库名", nameChanged = t1))
        )

        assertEquals("新库名", result.mergedMeta?.databaseName)
        assertTrue(result.metaConflicts.isEmpty())
    }

    private fun meta(
        name: String = "库",
        description: String = "",
        defaultUserName: String = "",
        color: String = "",
        customData: Map<String, String> = emptyMap(),
        historyMaxItems: Int = 10,
        historyMaxSize: Long = 6 * 1024 * 1024L,
        maintenanceHistoryDays: Int = 365,
        nameChanged: Instant? = null,
        descriptionChanged: Instant? = null,
        defaultUserNameChanged: Instant? = null,
        settingsChanged: Instant? = null
    ): KdbxMetaLite = KdbxMetaLite(
        databaseName = name,
        databaseDescription = description,
        defaultUserName = defaultUserName,
        color = color,
        customData = customData,
        historyMaxItems = historyMaxItems,
        historyMaxSize = historyMaxSize,
        maintenanceHistoryDays = maintenanceHistoryDays,
        databaseNameChanged = nameChanged,
        databaseDescriptionChanged = descriptionChanged,
        defaultUserNameChanged = defaultUserNameChanged,
        settingsChanged = settingsChanged
    )
}
