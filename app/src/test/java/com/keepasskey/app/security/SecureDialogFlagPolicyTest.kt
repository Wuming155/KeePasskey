package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureDialogFlagPolicyTest {
    @Test
    fun `只有解析到对话框窗口才施加保护且只撤销自己施加的标记`() {
        listOf(true, false).forEach { resolved ->
            val action = SecureDialogFlagPolicy.onEnter(dialogWindowResolved = resolved)
            assertEquals(
                if (resolved) SecureDialogFlagAction.ADD_SECURE else SecureDialogFlagAction.NONE,
                action,
            )
            assertEquals(
                if (resolved) SecureDialogFlagAction.CLEAR_SECURE else SecureDialogFlagAction.NONE,
                SecureDialogFlagPolicy.onDispose(addedByThisWrapper = action == SecureDialogFlagAction.ADD_SECURE),
            )
        }
    }
}
