package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `ISSUE-P3-171`：`PasskeyData.fromCustomFields` 的**形状短路**不得改变判定语义。
 *
 * 该函数在自动填充评分路径上对**全库每条目**各调一次（见 `AutofillCandidateRanker`），
 * 本批为其加了「先扫 key 名、不建 Map、不解密」的前置短路。本类锁定短路前后的判定等价：
 * 三个必需字段（RP ID / Credential ID / PrivateKey）缺任一仍必须解析为 `null`，
 * 齐备时仍必须正常解析，无关自定义字段不得干扰。
 */
class PasskeyDataFromCustomFieldsTest {

    @Test
    fun `缺少任一必需字段即解析为 null`() {
        assertNull("空字段表", PasskeyData.fromCustomFields(emptyList()))
        assertNull(
            "缺 CredentialId 与 PrivateKey",
            PasskeyData.fromCustomFields(listOf(field(PasskeyData.FIELD_RP_ID, "example.com")))
        )
        assertNull(
            "缺 PrivateKey",
            PasskeyData.fromCustomFields(
                listOf(
                    field(PasskeyData.FIELD_RP_ID, "example.com"),
                    field(PasskeyData.FIELD_CREDENTIAL_ID, "cred")
                )
            )
        )
        assertNull(
            "缺 CredentialId",
            PasskeyData.fromCustomFields(
                listOf(
                    field(PasskeyData.FIELD_RP_ID, "example.com"),
                    field(PasskeyData.FIELD_PRIVATE_KEY, "priv")
                )
            )
        )
        assertNull(
            "缺 RP ID",
            PasskeyData.fromCustomFields(
                listOf(
                    field(PasskeyData.FIELD_CREDENTIAL_ID, "cred"),
                    field(PasskeyData.FIELD_PRIVATE_KEY, "priv")
                )
            )
        )
    }

    @Test
    fun `无关自定义字段表不得被误判为 passkey`() {
        assertNull(
            "普通条目的自定义字段（含受保护值）必须直接短路，不得因解密或建 Map 而产出对象",
            PasskeyData.fromCustomFields(
                listOf(
                    KdbxCustomField("TOTP Seed", ProtectedString("JBSWY3DPEHPK3PXP")),
                    KdbxCustomField("备注", ProtectedString("第三方登录"))
                )
            )
        )
    }

    @Test
    fun `三个必需字段齐备时正常解析且可选字段取缺省`() {
        val data = PasskeyData.fromCustomFields(required())

        assertNotNull("必需字段齐备必须解析成功", data)
        assertEquals("example.com", data!!.relyingPartyId)
        assertEquals("cred", data.credentialId)
        assertEquals("可选用户名缺省为空串", "", data.userName)
        assertEquals("可选计数器缺省为未知哨兵", PasskeyData.SIGN_COUNT_UNKNOWN, data.signCount)
    }

    @Test
    fun `无关字段与必需字段并存时仍解析要求字段`() {
        val data = PasskeyData.fromCustomFields(
            required() + field("TOTP Seed", "JBSWY3DPEHPK3PXP")
        )

        assertNotNull(data)
        assertEquals("example.com", data!!.relyingPartyId)
        assertEquals("cred", data.credentialId)
    }

    private fun field(key: String, value: String): KdbxCustomField =
        KdbxCustomField(key, ProtectedString(value, isProtected = false))

    private fun required(): List<KdbxCustomField> = listOf(
        field(PasskeyData.FIELD_RP_ID, "example.com"),
        field(PasskeyData.FIELD_CREDENTIAL_ID, "cred"),
        field(PasskeyData.FIELD_PRIVATE_KEY, "priv")
    )
}
