package com.keepasskey.sync.merge

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ISSUE-P2-280 AC①／AC④：自定义图标池参与三方合并的回归。
 *
 * ## 锁定的缺陷
 *
 * 整改前 `KdbxDatabaseLite` 不含图标池、落库 `localDb.copy(...)` 恒取本地 ⇒
 * 对端新增的自定义图标在合并后丢失，引用它的条目 / 分组沦为悬空 `CustomIconRef`。
 *
 * ## 口径（对齐官方 `PwDatabase.MergeInCustomIcons`）
 *
 * 按 UUID 并集；同 UUID 不一致按 `lastModificationTime` LWW（null 视为最旧）；
 * 只增不删；base 不参与（官方即双向合并）。
 */
class KdbxMergerCustomIconsTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val t1: Instant = Instant.parse("2026-02-01T00:00:00Z")
    private val t2: Instant = Instant.parse("2026-03-01T00:00:00Z")

    private fun entry(mod: Instant, title: String, customIconId: KdbxUuid? = null): KdbxEntry = KdbxEntry(
        id = ENTRY_ID,
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString(title, false)),
        customIconId = customIconId,
        times = KdbxTimes(creationTime = t0, lastModificationTime = mod)
    )

    private fun lite(
        root: KdbxGroup,
        icons: List<CustomIcon> = emptyList()
    ) = KdbxDatabaseLite(rootGroup = root, customIcons = icons)

    private fun root(vararg entries: KdbxEntry) = KdbxGroup(id = ROOT_ID, name = "Root", entries = entries.toList())

    @Test
    fun `对端新增图标 + 本端改条目：合并后图标入池且引用可解析（AC④）`() {
        val icon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(1, 2, 3), lastModificationTime = t2)
        val base = lite(root(entry(t0, "原始标题")))
        val local = lite(root(entry(t1, "本端改标题")))
        val remote = lite(
            root(entry(t2, "原始标题", customIconId = ICON_ID)),
            icons = listOf(icon)
        )

        val result = KdbxMerger.mergeDatabases(base, local, remote)

        assertEquals(
            "对端新增图标必须进入合并池",
            listOf(icon),
            result.mergedCustomIcons
        )
        val mergedEntry = result.mergedRoot.allEntries().single { it.id == ENTRY_ID }
        assertEquals("本端的标题改动必须保留", "本端改标题", mergedEntry.fields[KdbxConstants.Fields.TITLE]?.readString())
        assertEquals(
            "对端给条目设置的图标引用必须保留且可解析（命中合并池成员）",
            ICON_ID,
            mergedEntry.customIconId
        )
        assertTrue(
            "合并后引用必须命中图标池（不得悬空）",
            result.mergedCustomIcons.any { it.uuid == mergedEntry.customIconId }
        )
    }

    @Test
    fun `同 UUID 两侧不一致：按 lastModificationTime LWW 取胜（AC① 官方口径）`() {
        val localIcon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(1), lastModificationTime = t1)
        val remoteIcon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(2), lastModificationTime = t2)

        assertEquals(
            "远端较新 ⇒ 远端胜",
            listOf(remoteIcon),
            KdbxMerger.mergeCustomIcons(listOf(localIcon), listOf(remoteIcon))
        )
        assertEquals(
            "本地较新 ⇒ 本地胜",
            listOf(localIcon),
            KdbxMerger.mergeCustomIcons(listOf(localIcon), listOf(remoteIcon.copy(lastModificationTime = t0)))
        )
    }

    @Test
    fun `同 UUID 时间 null 视为最旧；均 null 时取本地（AC① 官方口径边界）`() {
        val localIcon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(1), lastModificationTime = null)
        val remoteIcon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(2), lastModificationTime = t1)

        assertEquals(
            "远端有时间、本地 null ⇒ 远端胜",
            listOf(remoteIcon),
            KdbxMerger.mergeCustomIcons(listOf(localIcon), listOf(remoteIcon))
        )
        assertEquals(
            "两侧均 null ⇒ 取本地",
            listOf(localIcon),
            KdbxMerger.mergeCustomIcons(listOf(localIcon), listOf(remoteIcon.copy(lastModificationTime = null)))
        )
    }

    @Test
    fun `图标池只增不删：本端缺失不删除对端图标，对端缺失不删除本端图标`() {
        val localIcon = CustomIcon(uuid = KdbxUuid.random(), data = byteArrayOf(1), lastModificationTime = t1)
        val remoteIcon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(2), lastModificationTime = t2)

        val merged = KdbxMerger.mergeCustomIcons(listOf(localIcon), listOf(remoteIcon))

        assertEquals(
            "合并池必须为并集（同官方，图标不做删除合并）",
            setOf(localIcon, remoteIcon),
            merged.toSet()
        )
    }

    private companion object {
        val ROOT_ID: KdbxUuid = KdbxUuid.random()
        val ENTRY_ID: KdbxUuid = KdbxUuid.random()
        val ICON_ID: KdbxUuid = KdbxUuid.random()
    }
}
