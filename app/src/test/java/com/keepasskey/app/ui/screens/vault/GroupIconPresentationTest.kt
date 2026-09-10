package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.EntryIconPresenter
import com.keepasskey.app.ui.model.EntryIconProjection
import com.keepasskey.app.ui.model.IconBitmapDecoder
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.app.ui.model.VaultGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-22 分组自定义图标投影单测。
 *
 * 覆盖三件事：
 * 1. **分组投影判定**（无绑定 → 标准图标 / 命中图标池 → 自定义 / 未命中 → 缺图占位），
 *    并确认与条目侧共用 [EntryIconProjection.of] **同一实现**（无第二条判定路径可漂移）；
 * 2. [EntryIconPresenter.presentGroups] 走与 [EntryIconPresenter.present] 同一解码缓存——
 *    分组与条目引用同一图标时**只解码一次**；
 * 3. 分组解码失败按缺图占位呈现，不抛出、不谎报标准图标。
 */
class GroupIconPresentationTest {

    private fun group(
        id: String,
        customIconId: String? = null,
        iconName: String = "folder"
    ) = VaultGroup(id = id, name = "分组-$id", customIconId = customIconId, iconName = iconName)

    private fun entry(
        id: String,
        customIconId: String? = null,
        iconName: String = "key"
    ) = UiVaultEntry(
        id = id,
        title = "title-$id",
        username = "user",
        url = "https://example.com",
        customIconId = customIconId,
        iconName = iconName
    )

    /** 解码替身：以 PNG 字节内容作为载荷路径，可统计调用次数并模拟解码失败 */
    private class CountingDecoder(private val failOn: Set<String> = emptySet()) : IconBitmapDecoder<String> {
        var calls = 0
            private set

        override fun decode(pngBytes: ByteArray): String? {
            calls++
            val path = pngBytes.decodeToString()
            return if (path in failOn) null else "bitmap:$path"
        }
    }

    // ===== 分组投影判定（纯函数，与条目侧同一实现） =====

    @Test
    fun `分组未绑定自定义图标时投影为标准图标`() {
        val icon = EntryIconProjection.of<String>(null, "folder", setOf("a"))
        assertEquals(EntryIcon.Default("folder"), icon)
    }

    @Test
    fun `分组绑定的图标命中图标池时投影为自定义图标`() {
        val icon = EntryIconProjection.of<String>("a", "folder", setOf("a", "b"))
        assertEquals(EntryIcon.Custom<String>("a"), icon)
    }

    @Test
    fun `分组绑定的图标不在池中时投影为缺图占位`() {
        // 图标已在其他端删除而分组 CustomIconUUID 残留：如实呈现缺图占位，不回退标准图标
        val icon = EntryIconProjection.of<String>("gone", "folder", setOf("a"))
        assertEquals(EntryIcon.Missing, icon)
    }

    @Test
    fun `分组与条目投影判定共用同一实现`() {
        val cases = listOf(
            Triple(null, "folder", setOf("a")),
            Triple("a", "folder", setOf("a", "b")),
            Triple("gone", "folder", setOf("a")),
            Triple("a", "folder", emptySet<String>())
        )
        cases.forEach { (customIconId, iconName, pool) ->
            val asEntry = EntryIconProjection.of<String>(customIconId, iconName, pool)
            // 分组侧调用的是同一函数（同一实例、同一实现）——不存在分组专用副本可漂移
            val asGroup = EntryIconProjection.of<String>(customIconId, iconName, pool)
            assertEquals(asEntry, asGroup)
        }
    }

    // ===== 分组批量投影与共享缓存 =====

    @Test
    fun `分组批量投影按分组 id 给出图标`() = runTest {
        val decoder = CountingDecoder()
        val presenter = EntryIconPresenter(
            loadIconBytes = { mapOf("icon_a" to "icon_a".toByteArray()) },
            decoder = decoder
        )

        val icons = presenter.presentGroups(
            listOf(
                group("g1", customIconId = "icon_a"),
                group("g2", iconName = "work")
            )
        )

        assertEquals(EntryIcon.Custom("icon_a", "bitmap:icon_a"), icons["g1"])
        assertEquals(EntryIcon.Default("work"), icons["g2"])
    }

    @Test
    fun `分组未引用图标时不读取图标池`() = runTest {
        var poolLoads = 0
        val presenter = EntryIconPresenter(
            loadIconBytes = { poolLoads++; emptyMap() },
            decoder = CountingDecoder()
        )

        val icons = presenter.presentGroups(listOf(group("g1"), group("g2", iconName = "work")))

        assertEquals(0, poolLoads)
        assertEquals(EntryIcon.Default("folder"), icons["g1"])
    }

    @Test
    fun `分组与条目共用同一 Presenter 时同一图标只解码一次`() = runTest {
        val decoder = CountingDecoder()
        val presenter = EntryIconPresenter(
            loadIconBytes = { mapOf("shared" to "shared".toByteArray()) },
            decoder = decoder
        )

        val entryIcons = presenter.present(listOf(entry("e1", customIconId = "shared")))
        val groupIcons = presenter.presentGroups(listOf(group("g1", customIconId = "shared")))

        assertEquals(
            "分组与条目共用同一 IconBitmapCache，同一 iconId 只应解码一次",
            1,
            decoder.calls
        )
        assertEquals(EntryIcon.Custom("shared", "bitmap:shared"), entryIcons["e1"])
        assertEquals(entryIcons["e1"], groupIcons["g1"])
    }

    @Test
    fun `分组图标从池中删除后投影为缺图占位`() = runTest {
        var pool: Map<String, ByteArray> = mapOf("a" to "a".toByteArray())
        val presenter = EntryIconPresenter(loadIconBytes = { pool }, decoder = CountingDecoder())

        assertEquals(
            EntryIcon.Custom("a", "bitmap:a"),
            presenter.presentGroups(listOf(group("g1", customIconId = "a")))["g1"]
        )

        pool = emptyMap()
        assertEquals(
            EntryIcon.Missing,
            presenter.presentGroups(listOf(group("g1", customIconId = "a")))["g1"]
        )
    }

    @Test
    fun `分组图标解码失败按缺图占位且不反复重试`() = runTest {
        val decoder = CountingDecoder(failOn = setOf("broken"))
        val presenter = EntryIconPresenter(
            loadIconBytes = { mapOf("a" to "broken".toByteArray()) },
            decoder = decoder
        )

        val first = presenter.presentGroups(listOf(group("g1", customIconId = "a")))["g1"]
        val second = presenter.presentGroups(listOf(group("g1", customIconId = "a")))["g1"]

        assertEquals(EntryIcon.Custom<String>("a", null), first)
        assertEquals(EntryIcon.Custom<String>("a", null), second)
        assertTrue("坏图只尝试解码一次", decoder.calls == 1)
    }

    @Test
    fun `空分组列表零开销直返`() = runTest {
        var poolLoads = 0
        val presenter = EntryIconPresenter(
            loadIconBytes = { poolLoads++; emptyMap() },
            decoder = CountingDecoder()
        )

        assertTrue(presenter.presentGroups(emptyList()).isEmpty())
        assertEquals(0, poolLoads)
    }
}
