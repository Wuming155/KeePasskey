package com.keepasskey.app.ui.screens.vault

import com.keepasskey.app.R
import com.keepasskey.app.passkey.ImportedPasskey
import com.keepasskey.app.passkey.PasskeyCxfDisplayField
import com.keepasskey.app.passkey.PasskeyCxfDropped
import com.keepasskey.app.passkey.PasskeyCxfNotes
import com.keepasskey.app.passkey.PasskeyCxfSourceShape
import com.keepasskey.app.passkey.PasskeyImportDraft
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-337` 第 4 片 AC⑥⑩⑪：导入前确认对话框的**账目纪律**（纯函数层，可宿主机检）。
 *
 * 锁四件事，每件都是「写错就静默失真」的形态：
 * ① 秘密不回显（私钥 PEM / `credentialId` / `userHandle` 一律不得出现在正文里）；
 * ② `PD-48` 裁决二的两行分离——`credWithoutUV` 是**存下来**的，「规范形缺一枚种子」属**来源缺陷**，
 *    把它写进「不会导入」行就等于对用户宣称我们丢了它（而 AC⑪② 明写它**不属**丢弃项）；
 * ③ `§292` 文案口径落在**资源文件**上（类型行必须写「FIDO2 软件密钥（库内加密存储）」、
 *    禁「芯片 / 硬件」），扫源码而非比对自造标签，避免「拿自己的桩测自己的桩」。
 *
 * 解析器 [text] 用**哨兵标签**（`SITE` / `DROP_BLOB` …）：断言只看「哪些项进了哪一行」的结构，
 * 不重复断言文案本身（那由 ③ 的资源扫描负责）。
 */
class PasskeyImportConfirmDialogTest {

    private fun draft(
        dropped: List<PasskeyCxfDropped> = emptyList(),
        missing: List<PasskeyCxfDisplayField> = emptyList(),
        extraPasskeys: Int = 0,
        shape: PasskeyCxfSourceShape = PasskeyCxfSourceShape.BarePasskeyObject
    ): PasskeyImportDraft {
        val credential = ImportedPasskey(
            relyingPartyId = "webauthn.io",
            credentialId = "SECRET_CRED_ID",
            userHandle = "SECRET_USER_HANDLE",
            userName = "johndoe",
            userDisplayName = "John Doe",
            privateKeyPemChars = "SECRET_PEM_CHARS".toCharArray(),
            algorithmId = -7,
            prfWithUv = "SECRET_PRF_WITH".toByteArray(),
            prfWithoutUv = null
        )
        return PasskeyImportDraft(
            credential,
            PasskeyCxfNotes(shape, dropped, missing, extraPasskeys, skippedCredentialCount = 3)
        )
    }

    private fun render(d: PasskeyImportDraft, replacesEntry: Boolean = false): String =
        passkeyImportSummary(d, replacesEntry) { id ->
            when (id) {
                R.string.passkey_import_site -> "SITE:"
                R.string.passkey_import_kind -> "KIND"
                R.string.passkey_import_entry_title -> "TITLE:"
                R.string.passkey_import_replace_target -> "REPLACE_ON_ENTRY"
                R.string.passkey_import_dropped -> "NOT_SAVED:"
                R.string.passkey_import_extra_credentials -> "EXTRA:"
                R.string.passkey_import_source_missing -> "SOURCE:"
                R.string.passkey_import_drop_cred_blob -> "credBlob"
                R.string.passkey_import_drop_large_blob -> "largeBlob"
                R.string.passkey_import_drop_payments -> "payments"
                R.string.passkey_import_drop_hmac_unknown -> "hmac-unknown-alg"
                R.string.passkey_import_source_incomplete_prf -> "hmac-one-seed-missing"
                R.string.passkey_import_missing_username -> "username"
                R.string.passkey_import_missing_display_name -> "display name"
                else -> error("确认对话框引用了未登记的资源 id：$id")
            }
        }

    @Test
    fun `一 正文不回显任何凭据类材料`() {
        val text = render(draft())
        assertTrue("rpId 与拟用标题须在正文内", text.contains("SITE:webauthn.io") && text.contains("TITLE:johndoe@webauthn.io"))
        assertTrue("类型行须在场", text.contains("KIND"))
        for (secret in listOf("SECRET_PEM_CHARS", "SECRET_CRED_ID", "SECRET_USER_HANDLE", "SECRET_PRF_WITH")) {
            assertFalse("正文出现凭据类材料 $secret（AC③/口径 4 禁止回显）", text.contains(secret))
        }
        assertFalse("载荷原文不得出现在正文", text.contains("\"type\""))
    }

    /** AC⑪② + `PD-48` 裁决二的分界：丢弃行只列本库真不承载 / 按规范丢项，来源缺陷另起一行。 */
    @Test
    fun `二 不承载扩展逐条点名而缺种子归来源缺陷行`() {
        val both = render(
            draft(
                dropped = listOf(
                    PasskeyCxfDropped.CredBlob,
                    PasskeyCxfDropped.LargeBlob,
                    PasskeyCxfDropped.Payments,
                    PasskeyCxfDropped.HmacUnknownAlgorithm,
                    PasskeyCxfDropped.HmacIncomplete
                )
            )
        )
        val notSavedLine = both.lines().first { it.startsWith("NOT_SAVED:") }
        val sourceLine = both.lines().first { it.startsWith("SOURCE:") }
        for (item in listOf("credBlob", "largeBlob", "payments", "hmac-unknown-alg")) {
            assertTrue("「不会导入」行漏点名 $item：$notSavedLine", notSavedLine.contains(item))
        }
        assertFalse(
            "缺种子被写进「不会导入」行 ⇒ 与 PD-48 裁决二「credWithoutUV 全量存入、不属丢弃项」冲突",
            notSavedLine.contains("hmac-one-seed-missing")
        )
        assertTrue("缺种子须作为来源缺陷如实点名：$sourceLine", sourceLine.contains("hmac-one-seed-missing"))
    }

    @Test
    fun `三 多把凭据与来源未提供均须点名`() {
        val text = render(
            draft(
                missing = listOf(PasskeyCxfDisplayField.UserDisplayName),
                extraPasskeys = 2,
                shape = PasskeyCxfSourceShape.KeePassXCPasskeyFile
            )
        )
        assertTrue("PD-49 裁决三点名项缺失：$text", text.lines().any { it == "EXTRA:2" })
        assertTrue("来源未提供的展示字段须点名", text.lines().any { it.startsWith("SOURCE:") && it.contains("display name") })
        assertTrue(
            "KeePassXC 兼容形须让用户看见「按哪套词表解的」（其字段从未按 CXF 对齐）",
            text.contains("KeePassXC")
        )
    }

    /**
     * Q1 的替换语义（第 4 片（下））：编辑页改的是**当前条目**的凭据，标题与备注一行都不动。
     * 此时若仍写「将新建条目 …」就是当面撒谎——正文里出现「新建」二字即判红。
     */
    @Test
    fun `四 替换模式不得说新建`() {
        val text = render(draft(), replacesEntry = true)
        assertTrue("替换模式须点名「在本条目上替换」：$text", text.lines().any { it == "REPLACE_ON_ENTRY" })
        assertFalse("替换模式不得出现「将新建条目」行", text.contains("TITLE:"))
        assertTrue("rpId 与类型行在两种模式下都须在场", text.contains("SITE:webauthn.io") && text.contains("KIND"))
        assertFalse("替换模式同样不回显凭据材料", text.contains("SECRET_PEM_CHARS"))
    }

    /**
     * `§292` 文案纪律的**资源面**守卫：扫字符串文件本身，不测哨兵标签。
     * 立规缘由：哨兵标签只能证明「代码取了哪个资源」，证明不了「该资源说了什么」。
     */
    @Test
    fun `五 资源文案符合软件密钥口径且不宣称硬件`() {
        val zh = File(repositoryRoot, ZH_STRINGS).readText()
        val en = File(repositoryRoot, EN_STRINGS).readText()
        val zhKind = zh.substringAfter("""<string name="passkey_import_kind">""").substringBefore("</string>")
        val enKind = en.substringAfter("""<string name="passkey_import_kind">""").substringBefore("</string>")
        assertTrue("中文类型行须写库内加密存储口径，实为「$zhKind」", zhKind.contains("FIDO2 软件密钥") && zhKind.contains("库内加密存储"))
        assertTrue("英文类型行须与中文同口径，实为「$enKind」", enKind.contains("software key", true) && enKind.contains("encrypted", true))
        for ((label, value) in listOf("zh-kind" to zhKind, "en-kind" to enKind)) {
            for (banned in listOf("芯片", "硬件", "chip", "hardware", "secure element")) {
                assertFalse("$label 出现被禁表述「$banned」：$value", value.contains(banned, ignoreCase = true))
            }
        }
    }

    private companion object {
        const val ZH_STRINGS = "app/src/main/res/values/strings_sync_passkey.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"

        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory && File(candidate, "core/src/main/java").isDirectory) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }
    }
}
