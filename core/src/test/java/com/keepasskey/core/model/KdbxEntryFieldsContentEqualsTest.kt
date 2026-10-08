package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-543`：[KdbxEntry.fieldsContentEquals] 的语义锁定——**两侧判据的单一入口**
 * （合并侧 `KdbxEntryMerger.isModified` 与上传侧 `KdbxContentComparator.entryChanged` 同引）。
 *
 * 判据的**唯一例外**是标准五字段的 `isProtected`（由库级 MemoryProtection 决定、不参与
 * 序列化往返）；非标准 / 自定义字段的 per-value 标志仍是被序列化的真值，不得一并忽略。
 */
class KdbxEntryFieldsContentEqualsTest {

    private fun standard(
        titleProtected: Boolean,
        userNameProtected: Boolean,
        passwordProtected: Boolean
    ): KdbxEntry = KdbxEntry(
        fields = mapOf(
            KdbxConstants.Fields.TITLE to ProtectedString("标题", isProtected = titleProtected),
            KdbxConstants.Fields.USER_NAME to ProtectedString("alice", isProtected = userNameProtected),
            KdbxConstants.Fields.PASSWORD to ProtectedString("pw", isProtected = passwordProtected)
        )
    )

    @Test
    fun `标准字段仅受保护标志不同判为内容等值`() {
        // 「解析实例」（写侧以库级配置覆盖 ⇒ 非口令字段无 Protected 属性）vs「内存构造实例」
        val parsed = standard(titleProtected = false, userNameProtected = false, passwordProtected = true)
        val inMemory = standard(titleProtected = true, userNameProtected = true, passwordProtected = true)

        assertTrue(
            "同内容、仅标志不同（标准五字段）不得判为内容变更",
            parsed.fieldsContentEquals(inMemory)
        )
        assertTrue("（参数对调后同样成立）", inMemory.fieldsContentEquals(parsed))
    }

    @Test
    fun `标准字段内容不同判为不等值`() {
        val a = KdbxEntry(fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString("pw1", false)))
        val b = KdbxEntry(fields = mapOf(KdbxConstants.Fields.PASSWORD to ProtectedString("pw2", false)))

        assertFalse(a.fieldsContentEquals(b))
        assertFalse("（参数对调后同样成立）", b.fieldsContentEquals(a))
    }

    @Test
    fun `非标准字段的 per-value 标志仍参与比较`() {
        val a = KdbxEntry(fields = mapOf("Custom" to ProtectedString("v", isProtected = true)))
        val b = KdbxEntry(fields = mapOf("Custom" to ProtectedString("v", isProtected = false)))

        assertFalse(
            "非标准字段的 per-value 标志才是被序列化的真值 ⇒ 标志差异仍为不等值",
            a.fieldsContentEquals(b)
        )
        assertFalse("（参数对调后同样成立）", b.fieldsContentEquals(a))
    }

    @Test
    fun `键集不同判为不等值`() {
        val a = standard(titleProtected = false, userNameProtected = false, passwordProtected = true)
        val b = a.copy(fields = a.fields + ("Notes" to ProtectedString("备注", false)))
        val c = a.copy(fields = a.fields - KdbxConstants.Fields.USER_NAME)

        assertFalse("多一个键不得判为等值", a.fieldsContentEquals(b))
        assertFalse("少一个键不得判为等值", a.fieldsContentEquals(c))
    }

    @Test
    fun `同实例与同内容同标志判为等值`() {
        val a = standard(titleProtected = false, userNameProtected = false, passwordProtected = true)
        val same = standard(titleProtected = false, userNameProtected = false, passwordProtected = true)

        assertTrue(a.fieldsContentEquals(a))
        assertTrue("新实例、内容与标志均一致", a.fieldsContentEquals(same))
    }
}
