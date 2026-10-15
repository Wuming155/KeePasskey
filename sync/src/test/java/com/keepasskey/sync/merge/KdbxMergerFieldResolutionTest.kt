package com.keepasskey.sync.merge

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P2-281 AC②／AC④：`resolveConflictByFields` 扩展词汇的回归——
 * 自定义字段键（`custom:` 前缀）与四个标量键可与标准字段键同表裁决。
 *
 * ## 锁定的缺陷
 *
 * 整改前仅自定义字段分歧时冲突界面无从裁决（`resolveConflictByFields` 只认标准字段键），
 * 对端改动必然丢失。本用例锁定「用户选云端 ⇒ 远端值真实落到产物条目」。
 */
class KdbxMergerFieldResolutionTest {

    private val entryId = KdbxUuid.random()

    private fun pair(): ConflictedEntryPair {
        val local = KdbxEntry(
            id = entryId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("本地标题", false)),
            customFields = listOf(KdbxCustomField("OTP", ProtectedString("local-otp", true))),
            iconId = 1,
            overrideUrl = null,
            qualityCheck = true
        )
        val remote = KdbxEntry(
            id = entryId,
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("远端标题", false)),
            customFields = listOf(KdbxCustomField("OTP", ProtectedString("remote-otp", true))),
            iconId = 7,
            overrideUrl = "https://override.example",
            qualityCheck = false
        )
        return ConflictedEntryPair(
            entryId = entryId.toHexString(),
            localEntry = local,
            remoteEntry = remote,
            modifiedFields = listOf(
                KdbxConstants.Fields.TITLE,
                KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP",
                KdbxMerger.CONFLICT_KEY_ICON_ID,
                KdbxMerger.CONFLICT_KEY_OVERRIDE_URL,
                KdbxMerger.CONFLICT_KEY_QUALITY_CHECK
            )
        )
    }

    @Test
    fun `仅自定义字段选云端：远端值落入产物且本地其余字段不动（AC④ 不丢）`() {
        val resolved = KdbxMerger.resolveConflictByFields(
            pair(),
            mapOf(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP" to FieldResolutionChoice.KEEP_REMOTE)
        )

        assertEquals(
            "自定义字段必须采纳远端值",
            "remote-otp",
            resolved.customFields.single { it.key == "OTP" }.value.readString()
        )
        assertEquals("未选字段必须保留本地值", "本地标题", resolved.title)
        assertEquals("未选标量必须保留本地值", 1, resolved.iconId)
    }

    @Test
    fun `标量键选云端：iconId 与 overrideUrl 与 qualityCheck 逐项采纳远端值`() {
        val resolved = KdbxMerger.resolveConflictByFields(
            pair(),
            mapOf(
                KdbxMerger.CONFLICT_KEY_ICON_ID to FieldResolutionChoice.KEEP_REMOTE,
                KdbxMerger.CONFLICT_KEY_OVERRIDE_URL to FieldResolutionChoice.KEEP_REMOTE,
                KdbxMerger.CONFLICT_KEY_QUALITY_CHECK to FieldResolutionChoice.KEEP_REMOTE
            )
        )

        assertEquals(7, resolved.iconId)
        assertEquals("https://override.example", resolved.overrideUrl)
        assertEquals(false, resolved.qualityCheck)
        assertEquals("未选字段必须保留本地值", "local-otp", resolved.customFields.single { it.key == "OTP" }.value.readString())
    }

    @Test
    fun `KEEP_LOCAL 选择不改写任何字段（本地为底版）`() {
        val resolved = KdbxMerger.resolveConflictByFields(
            pair(),
            mapOf(
                KdbxConstants.Fields.TITLE to FieldResolutionChoice.KEEP_LOCAL,
                KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP" to FieldResolutionChoice.KEEP_LOCAL
            )
        )

        assertEquals("本地标题", resolved.title)
        assertEquals("local-otp", resolved.customFields.single { it.key == "OTP" }.value.readString())
        assertNull(resolved.overrideUrl)
    }

    @Test
    fun `远端缺失的自定义字段：显式采用云端删除`() {
        val p = pair()
        val remoteWithoutOtp = p.remoteEntry.copy(customFields = emptyList())
        val resolved = KdbxMerger.resolveConflictByFields(
            p.copy(remoteEntry = remoteWithoutOtp),
            mapOf(KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP" to FieldResolutionChoice.DELETE_REMOTE)
        )

        assertTrue("显式采纳云端删除应移除本地自定义字段", resolved.customFields.none { it.key == "OTP" })
        assertTrue(resolved.customFields.none { it.value.readString() == "remote-otp" })
    }

    @Test
    fun `空值与缺失不同且标准字段可显式删除`() {
        val p = pair()
        val emptyRemote = p.remoteEntry.copy(
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("", false))
        )
        val empty = KdbxMerger.resolveConflictByFields(
            p.copy(remoteEntry = emptyRemote),
            mapOf(KdbxConstants.Fields.TITLE to FieldResolutionChoice.KEEP_REMOTE)
        )
        assertTrue("云端存在的空值必须仍是一个已存在字段", empty.fields.containsKey(KdbxConstants.Fields.TITLE))
        assertEquals("", empty.fields.getValue(KdbxConstants.Fields.TITLE).readString())

        val missingRemote = p.remoteEntry.copy(fields = emptyMap())
        val deleted = KdbxMerger.resolveConflictByFields(
            p.copy(remoteEntry = missingRemote),
            mapOf(KdbxConstants.Fields.TITLE to FieldResolutionChoice.DELETE_REMOTE)
        )
        assertTrue("缺失字段经用户选择删除后应从条目移除", KdbxConstants.Fields.TITLE !in deleted.fields)
    }

    @Test
    fun `删除决策只接受快照中的缺失键`() {
        val p = pair()
        val forged = KdbxMerger.resolveConflictByFields(
            p,
            mapOf(
                "UnknownField" to FieldResolutionChoice.DELETE_REMOTE,
                KdbxMerger.CUSTOM_FIELD_CONFLICT_PREFIX + "OTP" to FieldResolutionChoice.DELETE_REMOTE
            )
        )
        assertEquals("伪造未知键不得修改条目", p.localEntry, forged)
        assertEquals(
            "云端字段仍存在时 DELETE_REMOTE 必须安全保留本地",
            "local-otp",
            forged.customFields.single { it.key == "OTP" }.value.readString()
        )
    }
}
