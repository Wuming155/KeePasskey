package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P2-280 AC②／AC④：自定义图标引用的**写出侧命中校验**与 `:database:` 往返回归。
 *
 * ## 锁定的不变量
 *
 * ① 图标在池 ⇒ 加密库**整库往返**后引用仍可解析（条目 `customIconId` 命中 Meta 池成员）；
 * ② 引用**不在池**（悬空 `CustomIconRef`）⇒ 写出即抛 [IllegalStateException]
 *    （可辨识失败，**禁静默丢图标**——此前写出侧直写引用、读侧不校验，悬空引用静默入库）；
 * ③ 分组级 `customIconId` 与条目历史快照内的引用同受校验。
 */
class CustomIconRefRoundtripTest {

    private val password = "IconRef#2026".toCharArray()

    private fun dbWith(
        icons: List<CustomIcon>,
        entryIconId: KdbxUuid?,
        groupIconId: KdbxUuid? = null,
        historyIconId: KdbxUuid? = null
    ): KdbxDatabase {
        val entry = KdbxEntry(
            id = ENTRY_ID,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("带图标的条目", false)),
            customIconId = entryIconId,
            history = if (historyIconId != null) {
                listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("历史快照", false)),
                        customIconId = historyIconId
                    )
                )
            } else {
                emptyList()
            }
        )
        val group = KdbxGroup(
            id = GROUP_ID,
            name = "分组",
            customIconId = groupIconId,
            entries = listOf(entry)
        )
        return KdbxDatabase(
            header = KdbxHeader.createDefault(),
            rootGroup = KdbxGroup(id = ROOT_ID, name = "Root", subgroups = listOf(group)),
            customIcons = icons
        )
    }

    private fun roundtrip(db: KdbxDatabase): KdbxDatabase {
        val baos = ByteArrayOutputStream()
        KdbxFile.save(baos, db, password, null)
        return KdbxFile.load(ByteArrayInputStream(baos.toByteArray()), password, null)
    }

    @Test
    fun `图标在池：整库往返后条目与分组引用均可解析（AC④ database 模块整库往返）`() {
        val icon = CustomIcon(uuid = ICON_ID, data = byteArrayOf(9, 8, 7), name = "站点图标")
        val parsed = roundtrip(dbWith(icons = listOf(icon), entryIconId = ICON_ID, groupIconId = ICON_ID))

        assertEquals("图标池必须完整往返", listOf(icon), parsed.customIcons)
        val group = parsed.rootGroup.subgroups.single { it.id == GROUP_ID }
        assertEquals("分组图标引用必须保留", ICON_ID, group.customIconId)
        val entry = group.entries.single { it.id == ENTRY_ID }
        assertEquals("条目图标引用必须保留", ICON_ID, entry.customIconId)
        assertNotNull(
            "往返后引用必须命中池成员（可解析、不悬空）",
            parsed.customIcons.firstOrNull { it.uuid == entry.customIconId }
        )
    }

    @Test
    fun `条目悬空引用：写出即可辨识地失败（AC②）`() {
        val dangling = KdbxUuid.random()
        val ex = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            roundtrip(dbWith(icons = emptyList(), entryIconId = dangling))
        }
        assertTrue(
            "失败消息必须可辨识（含悬空图标 UUID）: ${ex.message}",
            ex.message!!.contains(dangling.toHexString())
        )
    }

    @Test
    fun `分组悬空引用：写出即可辨识地失败（AC②）`() {
        val dangling = KdbxUuid.random()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            roundtrip(dbWith(icons = emptyList(), entryIconId = null, groupIconId = dangling))
        }
    }

    @Test
    fun `历史快照内悬空引用：写出即可辨识地失败（AC②）`() {
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            roundtrip(dbWith(icons = emptyList(), entryIconId = null, historyIconId = KdbxUuid.random()))
        }
    }

    private companion object {
        val ROOT_ID: KdbxUuid = KdbxUuid.random()
        val GROUP_ID: KdbxUuid = KdbxUuid.random()
        val ENTRY_ID: KdbxUuid = KdbxUuid.random()
        val ICON_ID: KdbxUuid = KdbxUuid.random()
    }
}
