package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.ui.model.EntryIcon
import com.keepasskey.app.ui.model.EntryIconPresenter
import com.keepasskey.app.ui.model.EntryIconProjection
import com.keepasskey.app.ui.model.IconBitmapCache
import com.keepasskey.app.ui.model.IconBitmapDecoder
import com.keepasskey.app.ui.model.UiVaultEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-02（TASK-49）自定义图标投影单测。
 *
 * 覆盖：投影判定（未绑定 / 图标池命中 / 图标池缺失占位）、同一 iconId 只解码一次、
 * 解码失败按缺图占位且不反复重试、图标删除后缓存剔除下线载荷、有界 LRU 淘汰。
 *
 * 渲染载荷以 String 替身承载：投影与缓存语义与 Android 位图无关，故可在 JVM 直测；
 * 真实 `BitmapFactory` 解码/绘制需真机或 instrumented 环境，见交接文件残余项。
 */
class EntryIconPresentationTest {

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

    private fun presenter(
        pool: () -> Map<String, ByteArray>,
        decoder: IconBitmapDecoder<String>
    ) = EntryIconPresenter(loadIconBytes = { pool() }, decoder = decoder)

    // ===== 投影判定（纯函数） =====

    @Test
    fun `未绑定自定义图标投影为标准图标`() {
        val icon = EntryIconProjection.of<String>(null, "public", setOf("a"))
        assertEquals(EntryIcon.Default("public"), icon)
    }

    @Test
    fun `绑定且图标池存在的图标投影为自定义图标`() {
        val icon = EntryIconProjection.of<String>("a", "public", setOf("a", "b"))
        assertEquals(EntryIcon.Custom<String>("a"), icon)
    }

    @Test
    fun `绑定但图标池缺失的图标投影为缺图占位`() {
        // 图标已在其他端删除而引用未清理：如实呈现缺图占位，不回退标准图标谎报状态
        val icon = EntryIconProjection.of<String>("gone", "public", setOf("a"))
        assertEquals(EntryIcon.Missing, icon)
    }

    // ===== 解码去重与占位 =====

    @Test
    fun `同一图标被多条引用时只解码一次`() = runTest {
        val decoder = CountingDecoder()
        val presenter = presenter({ mapOf("a" to "a".toByteArray(), "b" to "b".toByteArray()) }, decoder)

        val icons = presenter.present(listOf(entry("e1", "a"), entry("e2", "a"), entry("e3", "b")))

        assertEquals(2, decoder.calls)
        assertEquals(EntryIcon.Custom("a", "bitmap:a"), icons["e1"])
        assertEquals(icons["e1"], icons["e2"])
        assertEquals(EntryIcon.Custom("b", "bitmap:b"), icons["e3"])
    }

    @Test
    fun `再次装配复用缓存不再解码`() = runTest {
        val decoder = CountingDecoder()
        val presenter = presenter({ mapOf("a" to "a".toByteArray()) }, decoder)

        presenter.present(listOf(entry("e1", "a")))
        presenter.present(listOf(entry("e1", "a"), entry("e2", "a")))

        assertEquals(1, decoder.calls)
    }

    @Test
    fun `条目均未绑定自定义图标时不读取图标池`() = runTest {
        var poolLoads = 0
        val presenter = presenter({ poolLoads++; emptyMap() }, CountingDecoder())

        val icons = presenter.present(listOf(entry("e1"), entry("e2", iconName = "folder")))

        assertEquals(0, poolLoads)
        assertEquals(EntryIcon.Default("key"), icons["e1"])
        assertEquals(EntryIcon.Default("folder"), icons["e2"])
    }

    @Test
    fun `解码失败按缺图占位且不反复重试`() = runTest {
        val decoder = CountingDecoder(failOn = setOf("broken"))
        val presenter = presenter({ mapOf("a" to "broken".toByteArray()) }, decoder)

        val first = presenter.present(listOf(entry("e1", "a")))["e1"]
        val second = presenter.present(listOf(entry("e1", "a")))["e1"]

        assertEquals(EntryIcon.Custom<String>("a", null), first)
        assertEquals(EntryIcon.Custom<String>("a", null), second)
        assertEquals("坏图只尝试解码一次，不因每次状态装配反复重试", 1, decoder.calls)
    }

    @Test
    fun `图标从池中删除后投影为缺图占位且缓存剔除下线载荷`() = runTest {
        var pool: Map<String, ByteArray> = mapOf("a" to "a".toByteArray())
        val decoder = CountingDecoder()
        val presenter = presenter({ pool }, decoder)

        assertEquals(EntryIcon.Custom("a", "bitmap:a"), presenter.present(listOf(entry("e1", "a")))["e1"])

        // 图标被删除：Meta 图标池不再包含该 id
        pool = emptyMap()
        assertEquals(EntryIcon.Missing, presenter.present(listOf(entry("e1", "a")))["e1"])

        // 同 id 重新出现（同步回滚 / 重新上传）→ 重新解码，不复用已下线载荷
        pool = mapOf("a" to "a".toByteArray())
        assertEquals(EntryIcon.Custom("a", "bitmap:a"), presenter.present(listOf(entry("e1", "a")))["e1"])
        assertEquals(2, decoder.calls)
    }

    // ===== 有界缓存 =====

    @Test
    fun `有界缓存按 LRU 淘汰且容量不超上限`() {
        val cache = IconBitmapCache<String>(maxSize = 2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertEquals("A", cache["a"]) // 访问 a → a 成为最近使用项
        cache.put("c", "C") // 淘汰最久未使用的 b

        assertEquals(2, cache.size)
        assertEquals(setOf("a", "c"), cache.cachedIds())
        assertNull(cache["b"])
    }

    @Test
    fun `缓存剔除下线图标与清空`() {
        val cache = IconBitmapCache<String>(maxSize = 4)
        cache.put("a", "A")
        cache.put("b", "B")

        cache.retainOnly(setOf("b"))
        assertEquals(setOf("b"), cache.cachedIds())

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `缓存上限非法时构造失败`() {
        assertTrue(runCatching { IconBitmapCache<String>(0) }.isFailure)
    }
}
