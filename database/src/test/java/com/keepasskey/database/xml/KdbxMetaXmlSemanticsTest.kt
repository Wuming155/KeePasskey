package com.keepasskey.database.xml

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64

/**
 * XML 层 Meta 语义回归（不经过加密管线，直接读写明文 XML）：
 * ① 墓碑写在 `<Root>` 作用域（缺陷 1/D2）；
 * ② Meta 内历史位置墓碑的兼容读；
 * ③ CustomData / CustomIcon 的 KDBX 4.1 时间字段往返（缺陷 2/D13、D14）；
 * ④ MemoryProtection 读入后重置为默认（缺陷 3）；
 * ⑤ 零 UUID 替换（缺陷 5/D8）；
 * ⑥ 补充：HistoryMaxSize 缺省 -1、MasterKeyChangeForceOnce 读写对称。
 */
class KdbxMetaXmlSemanticsTest {

    private val zeroUuidBase64 =
        Base64.getEncoder().encodeToString(ByteArray(KdbxUuid.UUID_SIZE))

    // ---------------------------------------------------------------- ① 墓碑位置

    @Test
    fun `墓碑写在 Root 之内且位于根分组之后`() {
        val deleted = DeletedObject(
            id = KdbxUuid.random(),
            deletionTime = Instant.parse("2024-06-01T00:00:00Z")
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            deletedObjects = listOf(deleted)
        )

        val xml = serialize(db)

        val metaEnd = xml.indexOf("</Meta>")
        val deletedStart = xml.indexOf("<${KdbxConstants.Xml.DELETED_OBJECTS}>")
        val rootEnd = xml.indexOf("</${KdbxConstants.Xml.ROOT_GROUP}>")

        assertTrue("墓碑元素必须写出", deletedStart >= 0)
        assertTrue("墓碑必须写在 <Meta> 之外（官方写在 Root 作用域）", deletedStart > metaEnd)
        assertTrue("墓碑必须写在 </Root> 之前", deletedStart < rootEnd)

        // 读回不丢（Root 层级接收）
        val parsed = KdbxXmlParser(null).parse(xml.byteInputStream())
        assertEquals(listOf(deleted), parsed.meta.deletedObjects)
    }

    // ---------------------------------------------------------------- ② 旧位置兼容

    @Test
    fun `Meta 内历史位置的墓碑仍被接收`() {
        val uuid = KdbxUuid.random()
        val deletionTime = Instant.parse("2024-05-05T08:00:00Z")
        val xml = metaXml(
            metaBody = legacyDeletedObjectsElement(uuid, deletionTime),
            rootBody = groupElement(KdbxUuid.random(), "Root")
        )

        val parsed = KdbxXmlParser(null).parse(xml.byteInputStream())

        assertEquals(
            listOf(DeletedObject(uuid, deletionTime)),
            parsed.meta.deletedObjects
        )
    }

    @Test
    fun `两处位置同时存在时合并去重`() {
        val sharedUuid = KdbxUuid.random()
        val legacyOnly = KdbxUuid.random()
        val rootOnly = KdbxUuid.random()
        val legacyTime = Instant.parse("2024-05-05T08:00:00Z")
        val rootTime = Instant.parse("2024-05-06T09:00:00Z")

        val xml = metaXml(
            metaBody = legacyDeletedObjectsElement(sharedUuid, legacyTime, legacyOnly),
            rootBody = groupElement(KdbxUuid.random(), "Root") +
                    rootDeletedObjectsElement(sharedUuid, rootTime, rootOnly)
        )

        val parsed = KdbxXmlParser(null).parse(xml.byteInputStream())
        val ids = parsed.meta.deletedObjects.map { it.id }

        assertEquals("共享 UUID 必须去重", 3, ids.size)
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.containsAll(listOf(sharedUuid, legacyOnly, rootOnly)))
        // 去重时保留先出现（历史位置）的记录
        assertEquals(
            legacyTime,
            parsed.meta.deletedObjects.first { it.id == sharedUuid }.deletionTime
        )
    }

    // ---------------------------------------------------------------- ③ 时间字段往返

    @Test
    fun `CustomData 与 CustomIcon 的 KDBX 4_1 时间字段往返`() {
        val iconUuid = KdbxUuid.random()
        val iconTime = Instant.parse("2024-07-01T10:20:30Z")
        val customDataTime = Instant.parse("2024-07-02T11:22:33Z")
        val icon = CustomIcon(iconUuid, byteArrayOf(1, 2, 3), name = "我的图标", lastModificationTime = iconTime)

        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            customIcons = listOf(icon),
            // K2 无时间戳：写出侧不得为它凭空补时间
            customData = mapOf("K1" to "V1", "K2" to "V2"),
            customDataTimes = mapOf("K1" to customDataTime)
        )

        val xml = serialize(db)
        val parsed = KdbxXmlParser(null).parse(xml.byteInputStream())

        val loadedIcon = parsed.meta.customIcons.single()
        assertEquals(iconUuid, loadedIcon.uuid)
        assertEquals("我的图标", loadedIcon.name)
        assertEquals(iconTime, loadedIcon.lastModificationTime)

        assertEquals(mapOf("K1" to "V1", "K2" to "V2"), parsed.meta.customData)
        assertEquals(mapOf("K1" to customDataTime), parsed.meta.customDataTimes)
    }

    @Test
    fun `空图标名与缺失时间戳不写出对应元素`() {
        val icon = CustomIcon(KdbxUuid.random(), byteArrayOf(9), name = "", lastModificationTime = null)
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            customIcons = listOf(icon),
            customData = mapOf("K" to "V")
        )

        val xml = serialize(db)
        val iconXml = xml.substringAfter("<${KdbxConstants.Xml.ICON}>").substringBefore("</${KdbxConstants.Xml.ICON}>")
        val customDataXml = xml.substringAfter("<${KdbxConstants.Xml.CUSTOM_DATA}>")
            .substringBefore("</${KdbxConstants.Xml.CUSTOM_DATA}>")

        assertFalse("空图标名不得写出 <Name>", iconXml.contains("<${KdbxConstants.Xml.NAME}>"))
        assertFalse(
            "无时间戳的 CustomData 项不得写出 <LastModificationTime>",
            customDataXml.contains("<${KdbxConstants.Xml.LAST_MODIFICATION_TIME}>")
        )
    }

    @Test
    fun `零 UUID 或缺 Data 的自定义图标按官方语义丢弃`() {
        val keepUuid = KdbxUuid.random()
        val xml = """
            <KeePassFile>
                <Meta>
                    <CustomIcons>
                        <Icon>
                            <UUID>${Base64.getEncoder().encodeToString(keepUuid.toByteArray())}</UUID>
                            <Data>AQID</Data>
                        </Icon>
                        <Icon>
                            <UUID>$zeroUuidBase64</UUID>
                            <Data>AQID</Data>
                        </Icon>
                        <Icon>
                            <UUID>${Base64.getEncoder().encodeToString(KdbxUuid.random().toByteArray())}</UUID>
                        </Icon>
                    </CustomIcons>
                </Meta>
                <Root>${groupElement(KdbxUuid.random(), "Root")}</Root>
            </KeePassFile>
        """.trimIndent()

        val parsed = KdbxXmlParser(null).parse(xml.byteInputStream())

        assertEquals("零 UUID 与无 Data 的图标都必须丢弃", 1, parsed.meta.customIcons.size)
        assertEquals(keepUuid, parsed.meta.customIcons.single().uuid)
    }

    // ---------------------------------------------------------------- ④ 内存保护

    @Test
    fun `MemoryProtection 读入后重置为默认值`() {
        val xml = metaXml(
            metaBody = """
                <MemoryProtection>
                    <ProtectTitle>True</ProtectTitle>
                    <ProtectUserName>True</ProtectUserName>
                    <ProtectPassword>False</ProtectPassword>
                    <ProtectURL>True</ProtectURL>
                    <ProtectNotes>True</ProtectNotes>
                </MemoryProtection>
            """.trimIndent(),
            rootBody = groupElement(KdbxUuid.random(), "Root")
        )

        val parsed = KdbxXmlParser(null).parse(xml.byteInputStream())

        // 官方装载收尾整对象重置（KdbxFile.Read.cs:246-248）：文件里的组合一律不采用
        assertEquals(MemoryProtectionConfig(), parsed.meta.memoryProtection)
        assertFalse("文件里的 ProtectTitle=true 不得生效", parsed.meta.memoryProtection.protectTitle)
        assertTrue("默认 ProtectPassword=true 必须保留", parsed.meta.memoryProtection.protectPassword)
    }

    @Test
    fun `写侧只写出默认内存保护组合`() {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            // 即便调用方传入非默认组合，写侧也必须只写官方默认值（与读侧重置对称）
            memoryProtection = MemoryProtectionConfig(
                protectTitle = true,
                protectUserName = true,
                protectPassword = false,
                protectUrl = true,
                protectNotes = true
            )
        )

        val xml = serialize(db)

        assertTrue(xml.contains("<${KdbxConstants.Xml.PROTECT_TITLE}>False</${KdbxConstants.Xml.PROTECT_TITLE}>"))
        assertTrue(xml.contains("<${KdbxConstants.Xml.PROTECT_PASSWORD}>True</${KdbxConstants.Xml.PROTECT_PASSWORD}>"))
        assertTrue(xml.contains("<${KdbxConstants.Xml.PROTECT_URL}>False</${KdbxConstants.Xml.PROTECT_URL}>"))
    }

    @Test
    fun `isProtectEnabledFor 按官方字段名映射保护开关`() {
        val config = MemoryProtectionConfig(protectTitle = true, protectPassword = true)

        assertTrue(config.isProtectEnabledFor(KdbxConstants.Fields.TITLE))
        assertTrue(config.isProtectEnabledFor(KdbxConstants.Fields.PASSWORD))
        assertFalse(config.isProtectEnabledFor(KdbxConstants.Fields.USER_NAME))
        assertFalse(config.isProtectEnabledFor(KdbxConstants.Fields.URL))
        assertFalse(config.isProtectEnabledFor(KdbxConstants.Fields.NOTES))
        assertFalse("自定义字段一律不保护", config.isProtectEnabledFor("MyCustomField"))
    }

    // ---------------------------------------------------------------- ⑤ 零 UUID

    @Test
    fun `Group 与 Entry 的全零 UUID 被替换为随机 UUID 且父引用同步修正`() {
        val xml = """
            <KeePassFile>
                <Root>
                    <Group>
                        <UUID>$zeroUuidBase64</UUID>
                        <Name>ZeroGroup</Name>
                        <Entry>
                            <UUID>$zeroUuidBase64</UUID>
                            <String>
                                <Key>Title</Key>
                                <Value>ZeroEntry</Value>
                            </String>
                        </Entry>
                        <Group>
                            <UUID>$zeroUuidBase64</UUID>
                            <Name>ZeroSub</Name>
                        </Group>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()

        val root = KdbxXmlParser(null).parse(xml.byteInputStream()).rootGroup

        assertNotEquals("根组零 UUID 必须替换", KdbxUuid.ZERO, root.id)
        val entry = root.entries.single()
        assertNotEquals("条目零 UUID 必须替换", KdbxUuid.ZERO, entry.id)
        val sub = root.subgroups.single()
        assertNotEquals("子组零 UUID 必须替换", KdbxUuid.ZERO, sub.id)

        // 父引用同步修正：否则父引用仍指向旧零值，树结构与墓碑依旧失配
        assertEquals(root.id, entry.parentGroupId)
        assertEquals(root.id, sub.parentGroupId)

        // 多个零 UUID 对象必须各自获得不同 UUID（避免互相撞名）
        assertEquals(3, setOf(root.id, entry.id, sub.id).size)
    }

    @Test
    fun `非零 UUID 原样保留`() {
        val groupUuid = KdbxUuid.random()
        val xml = metaXml(
            metaBody = "",
            rootBody = groupElement(groupUuid, "Keep")
        )

        val root = KdbxXmlParser(null).parse(xml.byteInputStream()).rootGroup

        assertEquals(groupUuid, root.id)
    }

    // ---------------------------------------------------------------- ⑥ 补充字段

    @Test
    fun `HistoryMaxSize 元素缺失时缺省为负一无限制`() {
        val xml = metaXml(
            metaBody = "<HistoryMaxItems>5</HistoryMaxItems>",
            rootBody = groupElement(KdbxUuid.random(), "Root")
        )

        val meta = KdbxXmlParser(null).parse(xml.byteInputStream()).meta

        assertEquals("官方 ReadLong(xr, -1)：-1 = 不限制体积", -1L, meta.historyMaxSize)
        assertEquals(5, meta.historyMaxItems)
        assertTrue("RecycleBinEnabled 官方缺省 true", meta.recycleBinEnabled)
    }

    @Test
    fun `MasterKeyChangeForceOnce 读写对称`() {
        val enabled = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            masterKeyChangeForceOnce = true
        )
        val enabledXml = serialize(enabled)
        val element = "<$XML_MASTER_KEY_CHANGE_FORCE_ONCE>True</$XML_MASTER_KEY_CHANGE_FORCE_ONCE>"

        assertTrue(enabledXml.contains(element))
        assertTrue(KdbxXmlParser(null).parse(enabledXml.byteInputStream()).meta.masterKeyChangeForceOnce)

        val disabled = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root")
        )
        val disabledXml = serialize(disabled)

        assertFalse("false 时官方不写出该元素", disabledXml.contains(XML_MASTER_KEY_CHANGE_FORCE_ONCE))
        assertFalse(KdbxXmlParser(null).parse(disabledXml.byteInputStream()).meta.masterKeyChangeForceOnce)
    }

    // ---------------------------------------------------------------- 辅助

    private fun serialize(db: KdbxDatabase): String {
        val bos = ByteArrayOutputStream()
        KdbxXmlSerializer(null).serialize(bos, db)
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    private fun metaXml(metaBody: String, rootBody: String): String = """
        <KeePassFile>
            <Meta>$metaBody</Meta>
            <Root>$rootBody</Root>
        </KeePassFile>
    """.trimIndent()

    private fun groupElement(uuid: KdbxUuid, name: String): String =
        "<Group><UUID>${Base64.getEncoder().encodeToString(uuid.toByteArray())}</UUID><Name>$name</Name></Group>"

    private fun legacyDeletedObjectsElement(uuid: KdbxUuid, time: Instant, vararg extra: KdbxUuid): String =
        "<DeletedObjects>" +
                deletedObjectElement(uuid, time) +
                extra.joinToString("") { deletedObjectElement(it, time) } +
                "</DeletedObjects>"

    private fun rootDeletedObjectsElement(uuid: KdbxUuid, time: Instant, vararg extra: KdbxUuid): String =
        legacyDeletedObjectsElement(uuid, time, *extra)

    private fun deletedObjectElement(uuid: KdbxUuid, time: Instant): String =
        "<DeletedObject>" +
                "<UUID>${Base64.getEncoder().encodeToString(uuid.toByteArray())}</UUID>" +
                "<DeletionTime>${KdbxXmlTimeHelper.formatDate(time)}</DeletionTime>" +
                "</DeletedObject>"
}
