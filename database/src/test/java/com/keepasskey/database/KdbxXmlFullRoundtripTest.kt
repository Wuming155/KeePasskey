package com.keepasskey.database

import com.keepasskey.core.model.CustomIcon
import com.keepasskey.core.model.DeletedObject
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxAutoType
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.model.MemoryProtectionConfig
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.exception.KdbxCorruptFileException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.xml.KdbxXmlParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * 验证 KDBX 4 XML 完整往返：
 * 包括二进制池与去重、AutoType、Meta（回收站/自定义图标/删除对象/内存保护/自定义数据）、分组与条目扩展字段。
 */
class KdbxXmlFullRoundtripTest {

    private val testPassword = "VaultPassword@2026".toCharArray()

    @Test
    fun testBinaryPoolRoundtripAndDeduplication() {
        val sharedData = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val distinctData = byteArrayOf(9, 8, 7, 6)

        // 条目 1 与条目 2 包含相同的二进制数据（应当去重）
        val att1 = KdbxAttachment(name = "doc.pdf", data = sharedData)
        val att2 = KdbxAttachment(name = "doc_copy.pdf", data = sharedData.clone())
        // 条目 3 包含独立的二进制数据
        val att3 = KdbxAttachment(name = "photo.jpg", data = distinctData)

        val entry1 = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Entry1")),
            attachments = listOf(att1)
        )
        val entry2 = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Entry2")),
            attachments = listOf(att2)
        )
        val entry3 = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Entry3")),
            attachments = listOf(att3)
        )

        val rootGroup = KdbxGroup(
            name = "Root",
            entries = listOf(entry1, entry2, entry3)
        )

        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = rootGroup
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, database, testPassword)

        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)

        // 验证二进制池仅有 2 个条目（3 个附件中相同内容的 2 个已去重）
        assertEquals(2, loadedDb.binaries.size)

        val loadedEntries = loadedDb.rootGroup.entries
        assertEquals(3, loadedEntries.size)

        val loadedAtt1 = loadedEntries[0].attachments[0]
        val loadedAtt2 = loadedEntries[1].attachments[0]
        val loadedAtt3 = loadedEntries[2].attachments[0]

        // 验证条目 1 与条目 2 引用同一二进制索引
        assertEquals(loadedAtt1.refIndex, loadedAtt2.refIndex)
        assertEquals(0, loadedAtt1.refIndex)
        assertArrayEquals(sharedData, loadedAtt1.data)
        assertArrayEquals(sharedData, loadedAtt2.data)

        // 验证条目 3 引用不同的索引
        assertEquals(1, loadedAtt3.refIndex)
        assertArrayEquals(distinctData, loadedAtt3.data)
    }

    @Test
    fun testAutoTypeRoundtrip() {
        val autoType = KdbxAutoType(
            enabled = true,
            dataTransferObfuscation = 1,
            defaultSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}",
            associations = listOf(
                KdbxAutoType.AutoTypeAssociation("Mozilla Firefox", "{PASSWORD}{ENTER}"),
                KdbxAutoType.AutoTypeAssociation("Google Chrome", "{USERNAME}{TAB}{PASSWORD}")
            )
        )

        val entry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("AutoTypeEntry")),
            autoType = autoType
        )

        val rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        val db = KdbxDatabase(header = KdbxHeader.createDefault(useArgon2 = false), rootGroup = rootGroup)

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, testPassword)

        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)
        val loadedAutoType = loadedDb.rootGroup.entries[0].autoType

        assertNotNull(loadedAutoType)
        assertEquals(true, loadedAutoType!!.enabled)
        assertEquals(1, loadedAutoType.dataTransferObfuscation)
        assertEquals("{USERNAME}{TAB}{PASSWORD}{ENTER}", loadedAutoType.defaultSequence)
        assertEquals(2, loadedAutoType.associations.size)
        assertEquals("Mozilla Firefox", loadedAutoType.associations[0].window)
        assertEquals("{PASSWORD}{ENTER}", loadedAutoType.associations[0].keystrokeSequence)
        assertEquals("Google Chrome", loadedAutoType.associations[1].window)
        assertEquals("{USERNAME}{TAB}{PASSWORD}", loadedAutoType.associations[1].keystrokeSequence)
    }

    @Test
    fun testMetaCompleteRoundtrip() {
        val recycleBinUuid = KdbxUuid.random()
        val recycleBinChanged = Instant.parse("2024-03-01T10:00:00Z")
        val entryTemplatesGroup = KdbxUuid.random()
        val entryTemplatesGroupChanged = Instant.parse("2024-03-02T12:00:00Z")
        val iconUuid = KdbxUuid.random()
        val customIcon = CustomIcon(iconUuid, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val delUuid = KdbxUuid.random()
        val delTime = Instant.parse("2024-03-03T15:30:00Z")
        val deletedObject = DeletedObject(delUuid, delTime)
        val memProt = MemoryProtectionConfig(
            protectTitle = true,
            protectUserName = false,
            protectPassword = true,
            protectUrl = true,
            protectNotes = false
        )
        val customData = mapOf("CustomMetaKey1" to "MetaVal1", "CustomMetaKey2" to "MetaVal2")

        val originalDb = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "FullMetaVault",
            databaseDescription = "Meta testing description",
            rootGroup = KdbxGroup(name = "Root"),
            recycleBinEnabled = true,
            recycleBinUuid = recycleBinUuid,
            recycleBinChanged = recycleBinChanged,
            entryTemplatesGroup = entryTemplatesGroup,
            entryTemplatesGroupChanged = entryTemplatesGroupChanged,
            historyMaxItems = 25,
            historyMaxSize = 10 * 1024 * 1024L,
            customIcons = listOf(customIcon),
            deletedObjects = listOf(deletedObject),
            memoryProtection = memProt,
            customData = customData,
            generator = "KeePasskey-Test"
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, originalDb, testPassword)

        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)

        assertEquals("FullMetaVault", loadedDb.databaseName)
        assertEquals("Meta testing description", loadedDb.databaseDescription)
        assertEquals(true, loadedDb.recycleBinEnabled)
        assertEquals(recycleBinUuid, loadedDb.recycleBinUuid)
        assertEquals(recycleBinChanged, loadedDb.recycleBinChanged)
        assertEquals(entryTemplatesGroup, loadedDb.entryTemplatesGroup)
        assertEquals(entryTemplatesGroupChanged, loadedDb.entryTemplatesGroupChanged)
        assertEquals(25, loadedDb.historyMaxItems)
        assertEquals(10 * 1024 * 1024L, loadedDb.historyMaxSize)
        assertEquals("KeePasskey-Test", loadedDb.generator)

        // CustomIcons
        assertEquals(1, loadedDb.customIcons.size)
        assertEquals(iconUuid, loadedDb.customIcons[0].uuid)
        assertArrayEquals(customIcon.data, loadedDb.customIcons[0].data)

        // DeletedObjects
        assertEquals(1, loadedDb.deletedObjects.size)
        assertEquals(delUuid, loadedDb.deletedObjects[0].id)
        assertEquals(delTime, loadedDb.deletedObjects[0].deletionTime)

        // MemoryProtection
        assertEquals(true, loadedDb.memoryProtection.protectTitle)
        assertEquals(false, loadedDb.memoryProtection.protectUserName)
        assertEquals(true, loadedDb.memoryProtection.protectPassword)
        assertEquals(true, loadedDb.memoryProtection.protectUrl)
        assertEquals(false, loadedDb.memoryProtection.protectNotes)

        // CustomData
        assertEquals(customData, loadedDb.customData)
    }

    @Test
    fun testGroupAndEntryExtendedFieldsRoundtrip() {
        val groupParentUuid = KdbxUuid.random()
        val entryParentUuid = KdbxUuid.random()
        val topVisibleEntryUuid = KdbxUuid.random()

        val entry = KdbxEntry(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("ExtEntry")),
            overrideUrl = "https://override.example.com",
            qualityCheck = false,
            previousParentGroup = entryParentUuid,
            customData = mapOf("EntryKey1" to "Val1", "EntryKey2" to "Val2")
        )

        val group = KdbxGroup(
            name = "ExtGroup",
            defaultAutoTypeSequence = "{USERNAME}{ENTER}",
            enableAutoType = false,
            enableSearching = true,
            lastTopVisibleEntry = topVisibleEntryUuid,
            previousParentGroup = groupParentUuid,
            entries = listOf(entry)
        )

        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = group
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, testPassword)

        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)
        val loadedGroup = loadedDb.rootGroup
        val loadedEntry = loadedGroup.entries[0]

        // Group extended fields
        assertEquals("{USERNAME}{ENTER}", loadedGroup.defaultAutoTypeSequence)
        assertEquals(false, loadedGroup.enableAutoType)
        assertEquals(true, loadedGroup.enableSearching)
        assertEquals(topVisibleEntryUuid, loadedGroup.lastTopVisibleEntry)
        assertEquals(groupParentUuid, loadedGroup.previousParentGroup)

        // Entry extended fields
        assertEquals("https://override.example.com", loadedEntry.overrideUrl)
        assertEquals(false, loadedEntry.qualityCheck)
        assertEquals(entryParentUuid, loadedEntry.previousParentGroup)
        assertEquals(mapOf("EntryKey1" to "Val1", "EntryKey2" to "Val2"), loadedEntry.customData)
    }

    @Test
    fun testCorruptUuidInXmlThrowsException() {
        val corruptXml = """
            <KeePassFile>
                <Root>
                    <Group>
                        <UUID>NOT_A_VALID_BASE64_UUID!</UUID>
                        <Name>CorruptGroup</Name>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()

        val parser = KdbxXmlParser(null)
        assertThrows(KdbxCorruptFileException::class.java) {
            parser.parse(ByteArrayInputStream(corruptXml.toByteArray()))
        }
    }

    /**
     * P1-8 回归：7 个官方 Meta 字段（DefaultUserName / MaintenanceHistoryDays / Color /
     * MasterKeyChanged / MasterKeyChangeRec / MasterKeyChangeForce / SettingsChanged）
     * 及 DefaultUserNameChanged 完整往返零丢失。
     */
    @Test
    fun testMetaOfficialFieldsRoundtrip() {
        val defaultUserNameChanged = Instant.parse("2024-04-01T09:15:00Z")
        val masterKeyChanged = Instant.parse("2024-04-02T18:45:00Z")
        val settingsChanged = Instant.parse("2024-04-03T08:00:00Z")

        val originalDb = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root"),
            defaultUserName = "alice@example.com",
            defaultUserNameChanged = defaultUserNameChanged,
            maintenanceHistoryDays = 180,
            color = "Firebrick",
            masterKeyChanged = masterKeyChanged,
            masterKeyChangeRec = 365,
            masterKeyChangeForce = 730,
            settingsChanged = settingsChanged
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, originalDb, testPassword)
        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)

        assertEquals("alice@example.com", loadedDb.defaultUserName)
        assertEquals(defaultUserNameChanged, loadedDb.defaultUserNameChanged)
        assertEquals(180, loadedDb.maintenanceHistoryDays)
        assertEquals("Firebrick", loadedDb.color)
        assertEquals(masterKeyChanged, loadedDb.masterKeyChanged)
        assertEquals(365, loadedDb.masterKeyChangeRec)
        assertEquals(730, loadedDb.masterKeyChangeForce)
        assertEquals(settingsChanged, loadedDb.settingsChanged)
    }

    /**
     * P1-8 回归：分组 <Tags> 与 <CustomData>（含嵌套子分组）往返零丢失。
     */
    @Test
    fun testGroupTagsAndCustomDataRoundtrip() {
        val subGroup = KdbxGroup(
            name = "TaggedSubGroup",
            tags = listOf("工作", "重要", "同步"),
            customData = mapOf("GroupKey1" to "GroupVal1", "GroupKey2" to "GroupVal2")
        )
        val rootGroup = KdbxGroup(
            name = "Root",
            tags = listOf("root-tag"),
            customData = mapOf("RootKey" to "RootVal"),
            subgroups = listOf(subGroup)
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = rootGroup
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, testPassword)
        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)

        assertEquals(listOf("root-tag"), loadedDb.rootGroup.tags)
        assertEquals(mapOf("RootKey" to "RootVal"), loadedDb.rootGroup.customData)

        val loadedSub = loadedDb.rootGroup.subgroups[0]
        assertEquals("TaggedSubGroup", loadedSub.name)
        assertEquals(listOf("工作", "重要", "同步"), loadedSub.tags)
        assertEquals(mapOf("GroupKey1" to "GroupVal1", "GroupKey2" to "GroupVal2"), loadedSub.customData)
    }

    /**
     * P3-3 回归：多行文本中的 CRLF（与孤立 CR）经 &#xD; 字符引用写出后往返一致，
     * 不被 XML 1.0 行尾规范化静默折叠为纯 LF。
     */
    @Test
    fun testCrlfTextPreservedRoundtrip() {
        val notesText = "第一行\r\n第二行\n第三行\r第四行\r\n尾部"
        val groupNotes = "分组备注A\r\n分组备注B\rlone-cr"

        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("CrlfEntry", isProtected = false),
                KdbxConstants.Fields.NOTES to ProtectedString(notesText, isProtected = false)
            )
        )
        val rootGroup = KdbxGroup(name = "Root", notes = groupNotes, entries = listOf(entry))
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = rootGroup
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, testPassword)
        val loadedDb = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), testPassword)

        // 条目 Notes（未受保护明文字段）
        assertEquals(notesText, loadedDb.rootGroup.entries[0].fields[KdbxConstants.Fields.NOTES]?.readString())
        // 分组 Notes
        assertEquals(groupNotes, loadedDb.rootGroup.notes)
    }

    /**
     * P3-2 回归：<Times> 子元素缺失时按「合理远古时间」（Instant.EPOCH）解析，
     * 不再默认 now()——三方合并中缺失时间不得被误判为「刚刚修改」而虚假覆盖对端。
     */
    @Test
    fun testMissingTimesSubElementsDefaultToEpoch() {
        val groupUuidBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        val xml = """
            <KeePassFile>
                <Root>
                    <Group>
                        <UUID>$groupUuidBase64</UUID>
                        <Name>TimesMissing</Name>
                        <Times>
                            <Expires>False</Expires>
                        </Times>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()

        val parser = KdbxXmlParser(null)
        val result = parser.parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals(Instant.EPOCH, result.rootGroup.times.creationTime)
        assertEquals(Instant.EPOCH, result.rootGroup.times.lastModificationTime)
        assertEquals(Instant.EPOCH, result.rootGroup.times.lastAccessTime)
        assertEquals(Instant.EPOCH, result.rootGroup.times.expiryTime)
        assertEquals(Instant.EPOCH, result.rootGroup.times.locationChanged)
        assertFalse(result.rootGroup.times.expires)
    }
}
