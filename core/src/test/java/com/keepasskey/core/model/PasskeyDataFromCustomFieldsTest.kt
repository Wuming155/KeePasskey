package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * `ISSUE-P3-171`：`PasskeyData.fromCustomFields` 的**形状短路**不得改变判定语义。
 *
 * 该函数在自动填充评分路径上对**全库每条目**各调一次（见 `AutofillCandidateRanker`），
 * 本批为其加了「先扫 key 名、不建 Map、不解密」的前置短路。本类锁定短路前后的判定等价：
 * 三个必需字段（RP ID / Credential ID / PrivateKey）缺任一仍必须解析为 `null`，
 * 齐备时仍必须正常解析，无关自定义字段不得干扰。
 *
 * `ISSUE-P3-214`：另锁定历史 v1 私钥文本（64 字符 hex 标量 / 32 字节种子）的**精确算法嗅探**——
 * 两者此前的嗅探结果为 null，条目被静默回落成 ES256（Ed25519 历史条目将因此断言失败）。
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

    // ===== ISSUE-P3-214：v1 历史私钥文本形态的精确算法嗅探（不再盲回退 ES256） =====

    @Test
    fun `v1 六十四字符 hex 标量私钥嗅探为 ES256`() {
        // v1 ES256 的驻留形态：32 字节标量的 64 字符 hex 文本，且无 `Passkey.Algorithm` 扩展键
        val data = legacyEntry(privateKey = "1".repeat(64))

        assertNotNull(data)
        assertEquals(
            "64 字符 hex 标量必须被识别为 ES256（不得依赖静默回退）",
            PasskeyData.ALGORITHM_ES256,
            data!!.algorithmId
        )
    }

    @Test
    fun `v1 三十二字节种子私钥嗅探为 Ed25519`() {
        // v1 Ed25519 的驻留形态：32 字节种子的 Base64 文本（无 OID 可依，只能按长度判定）
        val seedBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val data = legacyEntry(privateKey = seedBase64)

        assertNotNull(data)
        assertEquals(
            "32 字节种子（Base64 承载）必须被识别为 Ed25519，否则历史 Ed25519 条目会以 ES256 断言而失败",
            PasskeyData.ALGORITHM_ED25519,
            data!!.algorithmId
        )
    }

    @Test
    fun `确实非已知形态的私钥文本仍 fail-safe 回落 ES256`() {
        // "priv"：非 64 字符 hex、Base64 解出 3 字节且无 OID、长度非 32 ⇒ 全非已知模式
        val data = PasskeyData.fromCustomFields(required())

        assertNotNull(data)
        assertEquals(
            "全非已知形态才允许回退 ES256（签名侧由 crypto 权威解析 fail-closed 兜底）",
            PasskeyData.ALGORITHM_ES256,
            data!!.algorithmId
        )
    }

    @Test
    fun `显式 Passkey Algorithm 扩展键优先于嗅探结果`() {
        // 私钥文本是 64 字符 hex（嗅探会得 ES256），显式键却声明 Ed25519：以显式键为准
        val fields = legacyEntryFields(privateKey = "1".repeat(64)) + listOf(
            field(PasskeyData.FIELD_ALGORITHM, PasskeyData.ALGORITHM_ED25519.toString())
        )

        val data = PasskeyData.fromCustomFields(fields)
        assertEquals(
            "扩展键存在时不得启用嗅探",
            PasskeyData.ALGORITHM_ED25519,
            data!!.algorithmId
        )
    }

    /** v1 旧 schema 条目（三个必需旧键齐备 + 指定私钥文本），无任何扩展键 */
    private fun legacyEntry(privateKey: String): PasskeyData? =
        PasskeyData.fromCustomFields(legacyEntryFields(privateKey))

    private fun legacyEntryFields(privateKey: String): List<KdbxCustomField> = listOf(
        field(PasskeyData.LEGACY_FIELD_RP_ID, "legacy.example"),
        field(PasskeyData.LEGACY_FIELD_CREDENTIAL_ID, "old-cred"),
        KdbxCustomField(
            PasskeyData.LEGACY_FIELD_PRIVATE_KEY,
            ProtectedString(privateKey, isProtected = true)
        )
    )

    private fun field(key: String, value: String): KdbxCustomField =
        KdbxCustomField(key, ProtectedString(value, isProtected = false))

    private fun required(): List<KdbxCustomField> = listOf(
        field(PasskeyData.FIELD_RP_ID, "example.com"),
        field(PasskeyData.FIELD_CREDENTIAL_ID, "cred"),
        field(PasskeyData.FIELD_PRIVATE_KEY, "priv")
    )
}
