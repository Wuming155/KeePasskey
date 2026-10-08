package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ISSUE-P0-531`：`VaultEntryMapper.mapKdbxEntryToUi` 的**展示面降级**回归。
 *
 * 真机崩溃形态（2026-10-08，`M332BF`）：会话层整树替换时对「被替换下线」的 `ProtectedString`
 * 实例就地清零，而 UI 投影链（`entriesFlow` 的 `flowOn(Dispatchers.Default)`）与擦除点
 * **不共享锁** ⇒ 投影读到已清零实例 ⇒ `KdbxEntry.getUserName()` 抛 `IllegalStateException`
 * ⇒ 逃逸到协程根 ⇒ 进程闪退。
 *
 * 本用例按**顺序复演**该竞态（先擦除、再投影），锁定整改后的外显行为：
 * ① 字段实例已清零时投影不抛、降级为空串（含历史条目与 TOTP 出码面）；
 * ② 未清零字段保持原值（**正向对照**——防「为了不抛而一律返回空」的退化实现）。
 */
class VaultEntryMapperDisplayFallbackTest {

    private val mapper = VaultEntryMapper(StringsProvider { id, _ -> "s$id" })

    @Test
    fun `字段实例已清零时投影降级为空串且不抛`() {
        val title = ProtectedString("My Bank", isProtected = true)
        val userName = ProtectedString("alice", isProtected = true)
        val url = ProtectedString("https://bank.example", isProtected = true)
        val notes = ProtectedString("备注", isProtected = true)
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to title,
                KdbxConstants.Fields.USER_NAME to userName,
                KdbxConstants.Fields.URL to url,
                KdbxConstants.Fields.NOTES to notes
            )
        )
        // 复演擦除侧：整树替换时「被替换下线」的实例被就地清零
        listOf(title, userName, url, notes).forEach { it.clear() }

        val ui = mapper.mapKdbxEntryToUi(entry)

        assertEquals("title 必须降级为空串", "", ui.title)
        assertEquals("username（真机崩溃堆栈命中字段）必须降级为空串", "", ui.username)
        assertEquals("url 必须降级为空串", "", ui.url)
        assertEquals("notes 必须降级为空串", "", ui.notes)
    }

    @Test
    fun `未清零字段投影保持原值`() {
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("My Bank", isProtected = true),
                KdbxConstants.Fields.USER_NAME to ProtectedString("alice", isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString("https://bank.example", isProtected = true),
                KdbxConstants.Fields.NOTES to ProtectedString("备注", isProtected = true)
            )
        )

        val ui = mapper.mapKdbxEntryToUi(entry)

        assertEquals("My Bank", ui.title)
        assertEquals("alice", ui.username)
        assertEquals("https://bank.example", ui.url)
        assertEquals("备注", ui.notes)
    }

    @Test
    fun `历史条目实例已清零时修订投影降级且不抛`() {
        val revisionName = ProtectedString("old-user", isProtected = true)
        val revisionNotes = ProtectedString("old-notes", isProtected = true)
        val revision = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.USER_NAME to revisionName,
                KdbxConstants.Fields.NOTES to revisionNotes
            )
        )
        val entry = KdbxEntry(history = listOf(revision))
        // `SessionPersistence.save` 的历史修剪擦的正是历史条目实例（真机崩溃的最近触发面）
        revisionName.clear()
        revisionNotes.clear()

        val ui = mapper.mapKdbxEntryToUi(entry)

        assertEquals("", ui.revisions.single().username)
        assertEquals("", ui.revisions.single().notes)
    }

    @Test
    fun `TOTP 种子实例已清零时出码面降级为 null 不抛`() {
        val seed = ProtectedString("JBSWY3DPEHPK3PXP", isProtected = true)
        val entry = KdbxEntry(fields = mapOf(KdbxConstants.Fields.OTP to seed))
        seed.clear()

        assertNull("已清零种子不得让投影抛异常，应降级为「无码」", mapper.parseTotpConfig(entry))

        // 正向对照：同形态条目在未清零时仍可解析（防「一律返回 null」的退化实现）
        val fresh = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.OTP to ProtectedString("JBSWY3DPEHPK3PXP", isProtected = true)
            )
        )
        val parsed = mapper.parseTotpConfig(fresh)
        assertNotNull("未清零种子必须仍可解析", parsed)
        parsed!!.secret.fill(0)
    }

    @Test
    fun `非保护自定义字段实例已清零时投影降级为空串且不抛`() {
        val value = ProtectedString("card-holder", isProtected = false)
        val entry = KdbxEntry(
            customFields = listOf(com.keepasskey.core.model.KdbxCustomField(key = "卡号", value = value))
        )
        value.clear()

        val ui = mapper.mapKdbxEntryToUi(entry)

        assertEquals("", ui.customFields.single().value)
    }
}
