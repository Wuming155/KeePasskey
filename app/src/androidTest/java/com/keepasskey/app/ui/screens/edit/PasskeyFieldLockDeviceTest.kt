package com.keepasskey.app.ui.screens.edit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.app.ui.model.UiCustomField
import com.keepasskey.core.model.PasskeyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android 运行时验证 Passkey 仪式字段经编辑器的更新、受保护值与删除入口均不可改。 */
@RunWith(AndroidJUnit4::class)
class PasskeyFieldLockDeviceTest {

    @Test
    fun `全部Passkey仪式字段拒绝普通编辑和删除且展示字段可编辑`() {
        val editableKeys = setOf(
            PasskeyData.FIELD_USER_NAME,
            PasskeyData.LEGACY_FIELD_USER_NAME,
            PasskeyData.FIELD_USER_DISPLAY_NAME
        )
        val lockedKeys = PasskeyData.SCHEMA_FIELD_KEYS - editableKeys
        assertTrue("设备用例必须覆盖多个仪式字段", lockedKeys.size > 10)
        lockedKeys.forEach { assertTrue("$it 必须被识别为锁定字段", isLockedPasskeyFieldKey(it)) }

        val initialFields = (lockedKeys + editableKeys).sorted().mapIndexed { index, key ->
            UiCustomField(
                id = "passkey-field-$index",
                key = key,
                value = "synthetic-$index",
                isProtected = key in PROTECTED_KEYS
            )
        }
        var state = EntryEditUiState(
            entryId = "synthetic-passkey-entry",
            isPasskey = true,
            customFields = initialFields
        )
        val protectedChars = mutableMapOf<String, CharArray>()
        val editor = EntryEditCustomFieldEditor(
            state = { state },
            update = { transform -> state = transform(state) },
            protectedChars = protectedChars
        )

        lockedKeys.forEach { key ->
            val field = initialFields.first { it.key == key }
            editor.updateField(field.id, "changed-$key", "changed", isProtected = false)
            val attemptedSecret = "synthetic-attempt".toCharArray()
            try {
                editor.updateProtectedValue(field.id, attemptedSecret)
            } finally {
                attemptedSecret.fill('\u0000')
            }
            editor.remove(field.id)
        }

        assertEquals("仪式字段的键和值必须原样保留", initialFields, state.customFields)
        assertTrue("拒绝受保护写入不得留下字符副本", protectedChars.isEmpty())
        assertFalse("被拒绝的编辑不得置脏", state.isDirty)

        val editable = initialFields.first { it.key == PasskeyData.FIELD_USER_DISPLAY_NAME }
        editor.updateField(editable.id, editable.key, "Synthetic Display Name", isProtected = false)
        assertEquals(
            "规范允许编辑的展示名应保留可编辑",
            "Synthetic Display Name",
            state.customFields.first { it.id == editable.id }.value
        )
        assertTrue("合法展示名修改必须置脏", state.isDirty)
    }

    private companion object {
        val PROTECTED_KEYS = setOf(
            PasskeyData.FIELD_USER_HANDLE,
            PasskeyData.FIELD_CREDENTIAL_ID,
            PasskeyData.FIELD_PRIVATE_KEY,
            PasskeyData.KPEX_FIELD_PRF,
            PasskeyData.FIELD_PRF_NO_UV
        )
    }
}
