package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ISSUE-P2-634`：自定义字段的删除 / 改名必须真正落地。
 *
 * 缺陷背景：整改前 `mergeCustomFields` 无条件保留「未出现在 UI 键集合中的既有字段」，
 * 于是删除的旧键被补回、改名后旧键与新键并存——用户以为敏感值已清除，库里仍在。
 *
 * 现行语义见 [CustomFieldMergePolicy]：载入基线 + 退役键集合区分删除 / 改名 / 保留，
 * 无基线调用方（导入、草稿）退化为整改前语义。
 */
class CustomFieldMergePolicyTest {

    private val entryId = "0123456789abcdef0123456789abcdef"

    /** 库内既有条目：普通字段 X + 受保护字段 SECRET。 */
    private fun existingEntry(): KdbxEntry = KdbxEntry(
        customFields = listOf(
            KdbxCustomField("X", ProtectedString("x-value", isProtected = false)),
            KdbxCustomField("SECRET", ProtectedString("s-value", isProtected = true))
        )
    )

    private fun uiField(key: String, value: String = "", protected: Boolean = false): UiCustomField =
        UiCustomField(id = "${entryId}_$key", key = key, value = value, isProtected = protected)

    private fun uiEntry(vararg fields: UiCustomField): UiVaultEntry = UiVaultEntry(
        id = entryId,
        title = "t",
        username = "u",
        url = "",
        customFields = fields.toList()
    )

    private fun baseline(vararg keys: String): Map<String, String> =
        keys.associate { "${entryId}_$it" to it }

    private fun List<KdbxCustomField>.keys(): List<String> = map { it.key }

    @Test
    fun `删除普通字段后旧键不再被补回`() {
        val merged = CustomFieldMergePolicy.merge(
            existing = existingEntry(),
            entry = uiEntry(uiField("SECRET", protected = true)),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = baseline("X", "SECRET")
        )

        assertEquals("被删除的 X 必须真的消失", listOf("SECRET"), merged.keys())
    }

    @Test
    fun `改名后旧键消失、新键写入且值保留`() {
        val merged = CustomFieldMergePolicy.merge(
            existing = existingEntry(),
            entry = uiEntry(uiField("Y", value = "x-value")),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = baseline("X")
        )

        assertEquals("改名不得同时留下旧键", listOf("Y", "SECRET"), merged.keys())
        assertEquals("新键值取 UI 提交", "x-value", merged.first().value.readString())
    }

    @Test
    fun `受保护字段删除后旧键不再被补回`() {
        val merged = CustomFieldMergePolicy.merge(
            existing = existingEntry(),
            entry = uiEntry(uiField("X", value = "x-value")),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = baseline("X", "SECRET")
        )

        assertEquals("被保护的 SECRET 同样必须消失", listOf("X"), merged.keys())
    }

    @Test
    fun `无载入基线的调用方保持整改前的保留语义`() {
        val merged = CustomFieldMergePolicy.merge(
            existing = existingEntry(),
            entry = uiEntry(uiField("X", value = "x-value")),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = emptyMap()
        )

        assertEquals(
            "无基线时不得推断删除意图（导入 / 草稿通道依赖该语义）",
            listOf("X", "SECRET"),
            merged.keys()
        )
    }

    @Test
    fun `Passkey 凭据材料键即使缺失于 UI 也不得退役`() {
        // 极端情形：投影因并发刷新丢掉了 Passkey 行，仍不得删掉私钥
        val existing = KdbxEntry(
            customFields = listOf(
                KdbxCustomField("X", ProtectedString("x-value", isProtected = false)),
                KdbxCustomField(PasskeyData.FIELD_PRIVATE_KEY, ProtectedString("pem", isProtected = true))
            )
        )
        val merged = CustomFieldMergePolicy.merge(
            existing = existing,
            entry = uiEntry(uiField("X", value = "x-value")),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = baseline("X", PasskeyData.FIELD_PRIVATE_KEY)
        )

        assertEquals(
            "Passkey 键属保留集，缺失于 UI 时也必须原样保留",
            listOf("X", PasskeyData.FIELD_PRIVATE_KEY),
            merged.keys()
        )
    }

    @Test
    fun `基线之外新增的既有字段一律保留`() {
        // 编辑期间同步合并进来的字段：基线里没有 ⇒ 不认识的键不删
        val merged = CustomFieldMergePolicy.merge(
            existing = existingEntry(),
            entry = uiEntry(uiField("X", value = "x-value")),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = baseline("X")
        )

        assertEquals(
            listOf("X", "SECRET"),
            merged.keys()
        )
    }

    @Test
    fun `改名的同时新增同名行不会产生重复键`() {
        // 用户把 X 改名为 Y，同时又新增一行仍叫 X 的字段：库内只应存在一个 X（取 UI 值）
        val merged = CustomFieldMergePolicy.merge(
            existing = existingEntry(),
            entry = uiEntry(
                uiField("Y", value = "x-value"),
                UiCustomField(id = "field_new", key = "X", value = "new-value")
            ),
            protectedFieldChars = emptyMap(),
            loadedCustomFieldKeys = baseline("X")
        )

        assertEquals(listOf("Y", "X", "SECRET"), merged.keys())
        assertEquals("X 的值应是用户新填的值", "new-value", merged[1].value.readString())
    }

    @Test
    fun `重名键被判定为非法`() {
        val invalid = CustomFieldMergePolicy.findInvalidKey(
            listOf(uiField("dup"), uiField("dup"))
        )
        assertEquals("dup", invalid)
    }

    @Test
    fun `与标准字段键冲突（含大小写）被判定为非法`() {
        assertEquals(
            "Title",
            CustomFieldMergePolicy.findInvalidKey(
                listOf(UiCustomField(id = "f1", key = KdbxConstants.Fields.TITLE, value = "v"))
            )
        )
        assertEquals(
            "password",
            CustomFieldMergePolicy.findInvalidKey(
                listOf(UiCustomField(id = "f2", key = "password", value = "v"))
            )
        )
    }

    @Test
    fun `空键与普通键不构成非法`() {
        assertNull(
            CustomFieldMergePolicy.findInvalidKey(
                listOf(
                    UiCustomField(id = "f1", key = "", value = ""),
                    UiCustomField(id = "f2", key = "", value = ""),
                    UiCustomField(id = "f3", key = "X", value = ""),
                    // `otp` 是历史里以自定义字段形态存在的标准 TOTP 字段，不得判为冲突
                    UiCustomField(id = "f4", key = KdbxConstants.Fields.OTP, value = "")
                )
            )
        )
    }
}
