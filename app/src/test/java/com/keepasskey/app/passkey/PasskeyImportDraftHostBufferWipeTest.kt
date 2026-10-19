package com.keepasskey.app.passkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyImportDraftHostBufferWipeTest {

    @Test
    fun `成功解析后入口清理UTF8与调用方字符数组且草案秘密独立存活`() {
        val pem = charArrayOf('f', 'a', 'k', 'e')
        val prfWithUv = byteArrayOf(1, 2, 3)
        val prfWithoutUv = byteArrayOf(4, 5, 6)
        val credential = ImportedPasskey(
            relyingPartyId = "example.test",
            credentialId = "YQ",
            userHandle = "Yg",
            userName = "user",
            userDisplayName = "User",
            privateKeyPemChars = pem,
            algorithmId = 1,
            prfWithUv = prfWithUv,
            prfWithoutUv = prfWithoutUv
        )
        val input = "payload-secret".toCharArray()
        var workingBytes: ByteArray? = null
        val host = PasskeyImportDraftHost(
            isReadOnly = { false },
            onNotice = {},
            readPayload = { bytes ->
                workingBytes = bytes
                PasskeyCxfOutcome.Parsed(
                    credential,
                    PasskeyCxfNotes(
                        PasskeyCxfSourceShape.BarePasskeyObject,
                        emptyList(),
                        emptyList(),
                        additionalPasskeyCount = 0,
                        skippedCredentialCount = 0
                    )
                )
            }
        )

        assertTrue(host.begin(input))
        assertTrue("原始字符输入在返回前清零", input.all { it == '0' })
        assertTrue("UTF-8工作数组在解析器返回后清零", workingBytes!!.all { it == 0.toByte() })
        assertArrayEquals("草案PEM是独立交付缓冲", charArrayOf('f', 'a', 'k', 'e'), pem)
        assertArrayEquals("草案PRF是独立交付缓冲", byteArrayOf(1, 2, 3), prfWithUv)
        assertArrayEquals("草案第二枚PRF是独立交付缓冲", byteArrayOf(4, 5, 6), prfWithoutUv)

        val draft = host.takeForImport()!!
        draft.wipe()
        assertTrue(pem.all { it == '0' })
        assertTrue(prfWithUv.all { it == 0.toByte() })
        assertTrue(prfWithoutUv.all { it == 0.toByte() })
    }

    @Test
    fun `拒绝与解析异常均清理UTF8工作数组和原字符数组`() {
        for (throwFromReader in listOf(false, true)) {
            val input = "secret-payload".toCharArray()
            var workingBytes: ByteArray? = null
            val host = PasskeyImportDraftHost(
                isReadOnly = { false },
                onNotice = {},
                readPayload = { bytes ->
                    workingBytes = bytes
                    if (throwFromReader) error("synthetic parser fault")
                    PasskeyCxfOutcome.Rejected(PasskeyCxfReject.MalformedJson)
                }
            )

            if (throwFromReader) {
                try {
                    host.begin(input)
                    throw AssertionError("期望解析异常传播")
                } catch (_: IllegalStateException) {
                    // 受控解析故障；这里只核对 finally 擦除。
                }
            } else {
                assertFalse(host.begin(input))
            }
            assertTrue("失败路径原始字符必须清零", input.all { it == '0' })
            assertTrue("失败路径UTF-8工作数组必须清零", workingBytes!!.all { it == 0.toByte() })
        }
    }

    @Test
    fun `取消草案时销毁交付的私钥与PRF`() {
        val pem = charArrayOf('p', 'e', 'm')
        val prfWithUv = byteArrayOf(7, 8)
        val prfWithoutUv = byteArrayOf(9, 10)
        val credential = ImportedPasskey(
            relyingPartyId = "example.test",
            credentialId = "YQ",
            userHandle = "Yg",
            userName = "user",
            userDisplayName = "User",
            privateKeyPemChars = pem,
            algorithmId = 1,
            prfWithUv = prfWithUv,
            prfWithoutUv = prfWithoutUv
        )
        val host = PasskeyImportDraftHost(
            isReadOnly = { false },
            onNotice = {},
            readPayload = { PasskeyCxfOutcome.Parsed(credential, emptyNotes()) }
        )

        assertTrue(host.begin("payload".toCharArray()))
        assertTrue("确认前草案材料仍有效", pem.any { it != '0' })
        host.dismiss()

        assertTrue("取消后私钥必须擦除", pem.all { it == '0' })
        assertTrue("取消后PRF必须擦除", prfWithUv.all { it == 0.toByte() })
        assertTrue("取消后第二枚PRF必须擦除", prfWithoutUv.all { it == 0.toByte() })
    }

    private fun emptyNotes() = PasskeyCxfNotes(
        PasskeyCxfSourceShape.BarePasskeyObject,
        emptyList(),
        emptyList(),
        additionalPasskeyCount = 0,
        skippedCredentialCount = 0
    )
}
