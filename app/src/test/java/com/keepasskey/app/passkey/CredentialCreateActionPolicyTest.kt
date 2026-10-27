package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialCreateActionPolicyTest {
    @Test
    fun `只有密码请求零候选可写且入口开启才提供新建动作`() {
        listOf(true, false).forEach { passwordRequested ->
            listOf(0, 1, 3).forEach { candidates ->
                listOf(true, false).forEach { readOnly ->
                    listOf(true, false).forEach { enabled ->
                        assertEquals(
                            passwordRequested && candidates == 0 && !readOnly && enabled,
                            shouldOfferPasswordCreateAction(
                                sawPasswordOption = passwordRequested,
                                passwordCandidateCount = candidates,
                                sessionReadOnly = readOnly,
                                offerCreateEntryEnabled = enabled,
                            ),
                        )
                    }
                }
            }
        }
    }
}
