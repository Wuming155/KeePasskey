package com.keepasskey.app.autofill

import com.keepasskey.app.data.repository.VaultEntryAppBindingWriter
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * `AndroidApp` 应用关联字段的 **KDBX 互操作探针**（证据纪律见 AGENTS.md 规则 8）。
 *
 * 本用例只产出**真实库文件**到 `app/build/interop-probe/`（可丢弃、不入库），供
 * `tools/kdbx-app-binding-interop/verify_app_binding.py` 用两个非本仓实现
 * （pykeepass / keepassxc-cli）逐键对拍值与保护位，并复刻 KeePassDX
 * `AppOriginEntryField.parseFields` + `checkAndroidOrigin` 的匹配口径。
 * 类内断言（进程内往返）只能证伪自家两侧不一致，**不构成互操作结论**。
 *
 * 字段表一律经 [VaultEntryAppBindingWriter.mergedBindingFields] 生成，确保产物就是生产写入链
 * 会落库的形态，而不是探针另写一份字面量。
 *
 * 产物覆盖：
 * - 单绑定 + 已有 `https://` 网址（URL 不被改写）；
 * - 连续三组绑定（含一组无签名形态）+ 旧形态 `android://` 网址，验证多包名与旧数据共存；
 * - 一条无绑定普通条目作干扰项。
 */
class AndroidAppBindingInteropProbeTest {

    private val fingerprint1 = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
    private val fingerprint2 = "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF"

    private fun colon(hex: String): String = AndroidAppBindingFields.toColonHex(hex)

    private fun bindingFields(vararg entries: Pair<String, String?>): List<KdbxCustomField> {
        var fields = emptyList<KdbxCustomField>()
        for ((pkg, signature) in entries) {
            fields = VaultEntryAppBindingWriter.mergedBindingFields(
                customFields = fields,
                existingValues = VaultEntryAppBindingWriter.bindingFieldValues(fields)!!,
                packageName = pkg,
                fingerprintHex = signature.orEmpty()
            ) ?: error("duplicate package in probe: $pkg")
        }
        return fields
    }

    private fun entry(
        title: String,
        url: String,
        customFields: List<KdbxCustomField> = emptyList()
    ): KdbxEntry = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString(title, isProtected = false),
            KdbxConstants.Fields.USER_NAME to ProtectedString("probe-user", isProtected = false),
            KdbxConstants.Fields.PASSWORD to ProtectedString("probe-password", isProtected = true),
            KdbxConstants.Fields.URL to ProtectedString(url, isProtected = false)
        ),
        customFields = customFields
    )

    @Test
    fun `产出AndroidApp字段互操作探针库与期望清单`() {
        val singleFields = bindingFields("com.tencent.mobileqq" to fingerprint1)
        val multiFields = bindingFields(
            "com.example.one" to fingerprint1,
            "com.example.two" to null,
            "com.example.three" to fingerprint2
        )
        val entries = listOf(
            entry(SINGLE_TITLE, "https://example.com", singleFields),
            entry(MULTI_TITLE, "android://com.legacy.app", multiFields),
            entry(DECOY_TITLE, "https://decoy.example.org")
        )

        val outDir = File("build/interop-probe").absoluteFile
        assertTrue("探针目录必须可创建", outDir.isDirectory || outDir.mkdirs())
        val outFile = File(outDir, PROBE_DB_NAME)
        val passwordChars = PROBE_PASSWORD.toCharArray()
        val header = KdbxHeader.createDefault(useArgon2 = false).copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 0x5A }, rounds = 6_000L)
        )
        val database = KdbxDatabase(
            header = header,
            rootGroup = KdbxGroup(name = "Root", entries = entries)
        )
        try {
            FileOutputStream(outFile).use { KdbxFile.save(it, database, passwordChars) }
        } finally {
            passwordChars.fill('0')
        }

        // 进程内往返：必要但不充分（互操作结论只由外部实现给出）
        val reloadedChars = PROBE_PASSWORD.toCharArray()
        val restored = try {
            FileInputStream(outFile).use { KdbxFile.load(it, reloadedChars) }
        } finally {
            reloadedChars.fill('0')
        }
        val restoredEntries = restored.rootGroup.allEntries()
        assertEquals(3, restoredEntries.size)
        val single = restoredEntries.single { it.title == SINGLE_TITLE }
        assertEquals("https://example.com", single.url)
        assertEquals("com.tencent.mobileqq", single.customFields[0].value.readString())
        assertFalse(single.customFields[0].value.isProtected)
        assertEquals(colon(fingerprint1), single.customFields[1].value.readString())
        assertTrue(single.customFields[1].value.isProtected)
        val multi = restoredEntries.single { it.title == MULTI_TITLE }
        assertEquals("android://com.legacy.app", multi.url)
        assertEquals(
            listOf(
                "AndroidApp", "AndroidApp Signature",
                "AndroidApp_1", "AndroidApp_2", "AndroidApp Signature_2"
            ),
            multi.customFields.map { it.key }
        )

        File(outDir, PROBE_MANIFEST_NAME).writeText(buildManifest(singleFields, multiFields))
        File(outDir, PROBE_NOTE_NAME).writeText(
            buildString {
                appendLine("# AndroidApp 应用关联互操作探针")
                appendLine()
                appendLine("- 库文件：`${outFile.absolutePath}`")
                appendLine("- 口令：`$PROBE_PASSWORD`（仅测试用虚构值）")
                appendLine("- KDF：AES-KDF 6000 轮（避开第三方对 Argon2 变体的支持差异）")
                appendLine("- 复现：`./gradlew :app:testDebugUnitTest --tests \"*AndroidAppBindingInteropProbeTest*\"`")
                appendLine("- 对拍：`python tools/kdbx-app-binding-interop/verify_app_binding.py`")
            }
        )
        println("APP_BINDING_PROBE_DB=${outFile.absolutePath}")
    }

    private fun buildManifest(
        single: List<KdbxCustomField>,
        multi: List<KdbxCustomField>
    ): String = buildString {
        appendLine("{")
        appendLine("  \"database\": \"$PROBE_DB_NAME\",")
        appendLine("  \"password\": \"$PROBE_PASSWORD\",")
        appendLine("  \"caller_fingerprints\": [\"${colon(fingerprint1)}\", \"${colon(fingerprint2)}\"],")
        appendLine("  \"entries\": [")
        appendEntry(SINGLE_TITLE, "https://example.com", single, trailingComma = true)
        appendEntry(MULTI_TITLE, "android://com.legacy.app", multi, trailingComma = true)
        appendEntry(DECOY_TITLE, "https://decoy.example.org", emptyList(), trailingComma = false)
        appendLine("  ]")
        appendLine("}")
    }

    private fun StringBuilder.appendEntry(
        title: String,
        url: String,
        fields: List<KdbxCustomField>,
        trailingComma: Boolean
    ) {
        appendLine("    {")
        appendLine("      \"title\": \"$title\",")
        appendLine("      \"url\": \"$url\",")
        appendLine("      \"fields\": [")
        fields.forEachIndexed { index, field ->
            appendLine(
                "        {\"key\": \"${field.key}\", \"value\": \"${field.value.readString()}\", " +
                    "\"protected\": ${field.value.isProtected}}" +
                    if (index < fields.size - 1) "," else ""
            )
        }
        appendLine("      ]")
        appendLine("    }${if (trailingComma) "," else ""}")
    }

    private companion object {
        const val PROBE_DB_NAME = "keepasskey-app-binding-probe.kdbx"
        const val PROBE_MANIFEST_NAME = "keepasskey-app-binding-probe.expected.json"
        const val PROBE_NOTE_NAME = "APP_BINDING_PROBE.md"
        const val PROBE_PASSWORD = "appbinding-probe-2026"
        const val SINGLE_TITLE = "KeePasskey AppBinding Single"
        const val MULTI_TITLE = "KeePasskey AppBinding Multi"
        const val DECOY_TITLE = "KeePasskey AppBinding Decoy"
    }
}
