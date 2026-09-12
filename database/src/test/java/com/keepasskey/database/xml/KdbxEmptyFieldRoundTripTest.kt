package com.keepasskey.database.xml

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 端到端往返回归：空字段值为空**且必须在场**（缺陷 D3 / P2）。
 *
 * 单点用例见 [KdbxEmptyValueFieldPreservationTest]；本类经完整 `KdbxFile.save/load`
 * 全链路（XML → GZip → 加密 → HMAC 块流）确证「空值字段不丢」在真实产物里成立。
 *
 * 同时充当缺陷 D7 的**全链路**回归锁：标准五字段的受保护标志在写出侧由**库级
 * `Meta/MemoryProtection` 无条件覆盖**（官方 `KdbxFile.Write.cs:838-854`），
 * per-value 标志对标准字段不参与判定（仅自定义字段保留 per-value）。
 */
class KdbxEmptyFieldRoundTripTest {

    private fun roundTrip(entry: KdbxEntry, memoryProtection: com.keepasskey.core.model.MemoryProtectionConfig =
        com.keepasskey.core.model.MemoryProtectionConfig()): KdbxEntry {
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry)),
            memoryProtection = memoryProtection
        )
        val password = "EmptyField#RoundTrip#2026".toCharArray()
        val bytes = ByteArrayOutputStream().also { KdbxFile.save(it, db, password) }.toByteArray()
        return KdbxFile.load(ByteArrayInputStream(bytes), password).rootGroup.entries.single()
    }

    @Test
    fun `空值标准字段往返后仍在场且为空`() {
        val loaded = roundTrip(
            KdbxEntry(
                fields = linkedMapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("Site", isProtected = false),
                    KdbxConstants.Fields.USER_NAME to ProtectedString("", isProtected = false),
                    KdbxConstants.Fields.URL to ProtectedString("", isProtected = false),
                    KdbxConstants.Fields.NOTES to ProtectedString("", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("", isProtected = false)
                )
            )
        )

        assertEquals("五个标准字段必须全部往返存活", 5, loaded.fields.size)
        for (name in listOf(
            KdbxConstants.Fields.USER_NAME,
            KdbxConstants.Fields.URL,
            KdbxConstants.Fields.NOTES,
            KdbxConstants.Fields.PASSWORD
        )) {
            val field = loaded.fields[name]
            assertNotNull("字段 $name 不得在往返后消失", field)
            assertEquals("字段 $name 应仍为空", 0, field!!.length)
        }
        assertEquals("Site", loaded.fields[KdbxConstants.Fields.TITLE]!!.readString())
    }

    @Test
    fun `空值自定义字段往返后仍在场`() {
        val loaded = roundTrip(
            KdbxEntry(
                customFields = listOf(
                    com.keepasskey.core.model.KdbxCustomField(
                        key = "EmptyCustom",
                        value = ProtectedString("", isProtected = false)
                    )
                )
            )
        )

        assertEquals(1, loaded.customFields.size)
        assertEquals("EmptyCustom", loaded.customFields.single().key)
        assertEquals("", loaded.customFields.single().value.readString())
    }

    @Test
    fun `库级配置无条件决定标准字段的受保护标志（覆盖 per-value）`() {
        // 库级配置保护 Title 与 Notes；URL 未开启（per-value 组合刻意制造冲突，见下方断言）
        val loaded = roundTrip(
            KdbxEntry(
                fields = linkedMapOf(
                    // 库级开启 + per-value 关闭 → 官方无条件覆盖 ⇒ 结果受保护
                    KdbxConstants.Fields.TITLE to ProtectedString("T", isProtected = false),
                    // 库级开启 + per-value 开启 → 结果受保护
                    KdbxConstants.Fields.NOTES to ProtectedString("N", isProtected = true),
                    // 库级关闭 + per-value 开启 → 官方无条件覆盖 ⇒ 结果**不**受保护
                    KdbxConstants.Fields.URL to ProtectedString("u", isProtected = true)
                )
            ),
            memoryProtection = com.keepasskey.core.model.MemoryProtectionConfig(
                protectTitle = true,
                protectNotes = true,
                protectPassword = false
            )
        )

        assertTrue("Title：库级开启 ⇒ 受保护（覆盖 per-value 的 false）", loaded.fields[KdbxConstants.Fields.TITLE]!!.isProtected)
        assertTrue("Notes：库级开启 + per-value 开启 ⇒ 受保护", loaded.fields[KdbxConstants.Fields.NOTES]!!.isProtected)
        assertFalse(
            "URL：库级关闭 ⇒ 官方无条件覆盖 per-value 的 true，往返后应**不**受保护",
            loaded.fields[KdbxConstants.Fields.URL]!!.isProtected
        )

        // 值仍须逐字往返（标志归一化绝不影响内容）
        assertEquals("T", loaded.fields[KdbxConstants.Fields.TITLE]!!.readString())
        assertEquals("N", loaded.fields[KdbxConstants.Fields.NOTES]!!.readString())
        assertEquals("u", loaded.fields[KdbxConstants.Fields.URL]!!.readString())
    }

    @Test
    fun `非空值往返不受空值语义影响`() {
        val loaded = roundTrip(
            KdbxEntry(
                fields = linkedMapOf(
                    KdbxConstants.Fields.TITLE to ProtectedString("NonEmptySite", isProtected = false),
                    KdbxConstants.Fields.PASSWORD to ProtectedString("Secret#1", isProtected = true)
                )
            )
        )

        assertEquals("NonEmptySite", loaded.fields[KdbxConstants.Fields.TITLE]!!.readString())
        assertEquals("Secret#1", loaded.fields[KdbxConstants.Fields.PASSWORD]!!.readString())
    }
}
