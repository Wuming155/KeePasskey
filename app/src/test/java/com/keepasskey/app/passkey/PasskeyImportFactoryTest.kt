package com.keepasskey.app.passkey

import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.PasskeyData
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.passkey.PasskeyPrf
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-337` 第 3 片：导入凭据 → [PasskeyData] 的构造口径（AC③④⑦ 的构造半边）。
 *
 * 锁四件事，每件都对应一个「写错就静默坏掉」的点：
 * ① 私钥走 `CharArray → ProtectedString(受保护)` 的字符通道，与生成侧同形（AC③）；
 * ② PRF 种子**重新编码成标准 Base64** 后才驻留——[PasskeyPrf.computeValue] 的消费方用
 *    `Base64.getDecoder()`，若把载荷里的 Base64URL 原样存下，含 `+` / `/` 的种子
 *    会在断言时抛「PRF 秘密不是合法 Base64 文本」，一把完好凭据的 prf 就此失效。
 *    本用例不比对文本形态，而是**直接跑真实消费路径**（本地独立算一遍 HMAC 期望值），
 *    这样编码口径错、长度校验、域分隔式任何一处偏掉都会红；
 * ③ 计数器随机高位起点落 `[2^20, 2^24)` 且**确实在随机**（`PD-49` 裁决一）；
 * ④ `Passkey.PrfNoUv` 只存不用（`PD-48` 裁决二），无第二枚时**不写出该键**、更不复制假种子。
 */
class PasskeyImportFactoryTest {

    private fun secret(seed: Int): ByteArray = ByteArray(32) { (it + seed).toByte() }

    private fun imported(withPrf: Boolean = true, withNoUv: Boolean = true): ImportedPasskey = ImportedPasskey(
        relyingPartyId = "webauthn.io",
        credentialId = "Y3JlZGVudGlhbElkRXhhbXBsZQ",
        userHandle = "cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY",
        userName = "johndoe",
        userDisplayName = "John Doe",
        privateKeyPemChars = ("-----BEGIN PRIVATE KEY-----\n" +
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgmr4GQQjerojFuf0Z\n" +
            "ouOuUllMvAwxZSZAfB6gwDYcLiehRANCAAT0WR5zVSp6ieusvjkLkzaGc7fjGBmw\n" +
            "piuLPxR_d-ZjqMI9L2DKh-takp6wGt2x0n4jzr1KA352NZg0vjZX9CHh\n" +
            "-----END PRIVATE KEY-----\n").toCharArray(),
        algorithmId = PasskeyData.ALGORITHM_ES256,
        prfWithUv = if (withPrf) secret(1) else null,
        prfWithoutUv = if (withPrf && withNoUv) secret(2) else null
    )

    @Test
    fun `一 逐字段映射且私钥以受保护字符通道驻留`() {
        val credential = imported()
        val data = PasskeyImportFactory.toPasskeyData(credential)
        assertEquals("webauthn.io", data.relyingPartyId)
        assertEquals("Y3JlZGVudGlhbElkRXhhbXBsZQ", data.credentialId)
        assertEquals("cnEzaNHWcYK3coWZjvoaV1Hj9gnI12mKe2dL2HZVFlY", data.userHandle)
        assertEquals("johndoe", data.userName)
        assertEquals("John Doe", data.userDisplayName)
        assertEquals(PasskeyData.ALGORITHM_ES256, data.algorithmId)
        assertEquals("外部材料无公钥：留空走嗅探口径", "", data.publicKeyBase64)
        assertTrue("导出即已备份（BS），库可同步故 BE", data.backupEligible && data.backupState)
        val restored = data.usePrivateKeyBytes { String(it, Charsets.UTF_8) }
        assertTrue("私钥必须以 PKCS#8 PEM 驻留", restored.startsWith("-----BEGIN PRIVATE KEY-----"))
        val fields = data.toCustomFields().associateBy { it.key }
        assertSame(
            "KPEX 私钥字段必须直接引用同一 ProtectedString 实例（ISSUE-P1-02 零拷贝）",
            data.privateKey,
            fields.getValue(PasskeyData.FIELD_PRIVATE_KEY).value
        )
    }

    /** 真实消费路径：本地独立算一遍 `HMAC-SHA-256(secret, SHA-256("WebAuthn PRF"‖0x00‖input))`。 */
    @Test
    fun `二 驻留的 PRF 文本可被断言侧真实消费路径解出正确输出`() {
        val credential = imported()
        val data = PasskeyImportFactory.toPasskeyData(credential)
        val fields = data.toCustomFields().associateBy { it.key }
        val storedWithUv = requireNotNull(fields[PasskeyData.KPEX_FIELD_PRF]) { "缺 KPEX_PASSKEY_PRF" }.value
        val input = ByteArray(32) { (100 - it).toByte() }
        val actual = PasskeyPrf.computeValue(storedWithUv, input)
        try {
            assertArrayEquals(
                "导入种子必须能直接用于 prf 计算（编码口径与生成侧一致，无额外派生）",
                expectedPrf(credential.prfWithUv!!, input),
                actual
            )
        } finally {
            actual.fill(0)
        }
        val storedNoUv = requireNotNull(fields[PasskeyData.FIELD_PRF_NO_UV]) { "缺 Passkey.PrfNoUv" }.value
        val text = storedNoUv.readUtf8()
        try {
            assertEquals(
                "第二枚种子原样保真（标准 Base64 文本，与 withUV 同形）",
                Base64.getEncoder().encodeToString(credential.prfWithoutUv),
                String(text, Charsets.UTF_8)
            )
        } finally {
            text.fill(0)
        }
        assertSame(fields.getValue(PasskeyData.FIELD_PRF_NO_UV).value, data.prfNoUvSecret)
    }

    private fun expectedPrf(seed: ByteArray, input: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("WebAuthn PRF".toByteArray(Charsets.UTF_8))
        digest.update(0)
        val salt = digest.digest(input)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        return mac.doFinal(salt)
    }

    /** 别名形（规范附录 A 的 `hmacSecret`）只有一枚 ⇒ 第二枚留空且**不写出该键**（不得复制假种子）。 */
    @Test
    fun `三 只有一枚种子时不生成第二枚也不写出该键`() {
        val credential = imported(withPrf = true, withNoUv = false)
        val fields = PasskeyImportFactory.toPasskeyData(credential).toCustomFields().associateBy { it.key }
        assertTrue(fields.containsKey(PasskeyData.KPEX_FIELD_PRF))
        assertNull("禁止复制出第二枚假种子", fields[PasskeyData.FIELD_PRF_NO_UV])
    }

    @Test
    fun `四 无扩展时两枚 PRF 键都不写出`() {
        val credential = imported(withPrf = false)
        val fields = PasskeyImportFactory.toPasskeyData(credential).toCustomFields().associateBy { it.key }
        assertNull(fields[PasskeyData.KPEX_FIELD_PRF])
        assertNull(fields[PasskeyData.FIELD_PRF_NO_UV])
    }

    /** 计数器随机高位起点：区间边界与「确实在随机」两条都要锁（固定值等于没跨过去）。 */
    @Test
    fun `五 导入计数器起点落在随机高位区间且不恒定`() {
        val seen = HashSet<Int>()
        repeat(200) {
            val value = PasskeyImportFactory.randomImportSignCount()
            assertTrue(
                "起点必须在 [2^20, 2^24) 区间内（PD-49 裁决一），实际 $value",
                value in PasskeyImportFactory.IMPORT_SIGN_COUNT_MIN until PasskeyImportFactory.IMPORT_SIGN_COUNT_MAX_EXCLUSIVE
            )
            seen += value
        }
        assertTrue("200 次取样只得到 ${seen.size} 个值 ⇒ 并非均匀随机，裁决一落空", seen.size > 100)
        val data = PasskeyImportFactory.toPasskeyData(imported())
        assertNotEquals(
            "不得沿用「缺失即 0」的哨兵：0 会让首次断言就落进 RP 的 new≤stored 嫌疑区",
            PasskeyData.SIGN_COUNT_UNKNOWN,
            data.signCount
        )
    }

    /** AC④② 的构造半边：保护位逐键（明文四键 / 受保护五键，其余扩展键非保护）。 */
    @Test
    fun `六 保护位逐键符合 PD-48 口径`() {
        val fields = PasskeyImportFactory.toPasskeyData(imported()).toCustomFields().associateBy { it.key }
        for (key in listOf(
            PasskeyData.KPEX_FIELD_RELYING_PARTY,
            PasskeyData.KPEX_FIELD_USERNAME,
            PasskeyData.KPEX_FIELD_FLAG_BE,
            PasskeyData.KPEX_FIELD_FLAG_BS,
            PasskeyData.FIELD_ALGORITHM,
            PasskeyData.FIELD_SIGN_COUNT,
            PasskeyData.FIELD_USER_DISPLAY_NAME,
            PasskeyData.FIELD_CREATED_AT
        )) {
            assertTrue("$key 必须明文", requireNotNull(fields[key]).value.isProtected.not())
        }
        for (key in listOf(
            PasskeyData.KPEX_FIELD_USER_HANDLE,
            PasskeyData.KPEX_FIELD_CREDENTIAL_ID,
            PasskeyData.FIELD_PRIVATE_KEY,
            PasskeyData.KPEX_FIELD_PRF,
            PasskeyData.FIELD_PRF_NO_UV
        )) {
            assertTrue("$key 必须受保护（PRF 双值都是凭据秘密）", requireNotNull(fields[key]).value.isProtected)
        }
        // 读回侧：从字段表重建模型，计数器与第二枚种子都要保真
        val restored = requireNotNull(PasskeyData.fromCustomFields(fields.values.toList()))
        assertEquals("导入计数器须经读回口径保真", signCountOf(fields), restored.signCount)
        val restoredNoUv = requireNotNull(restored.prfNoUvSecret) { "读回丢失 Passkey.PrfNoUv" }
        val text = restoredNoUv.readUtf8()
        try {
            assertEquals(
                "读回的第二枚种子与写出文本逐字一致",
                String(text, Charsets.UTF_8),
                Base64.getEncoder().encodeToString(secret(2))
            )
        } finally {
            text.fill(0)
        }
    }

    private fun signCountOf(fields: Map<String, KdbxCustomField>): Int {
        val bytes = requireNotNull(fields[PasskeyData.FIELD_SIGN_COUNT]).value.readUtf8()
        return try {
            String(bytes, Charsets.UTF_8).toInt()
        } finally {
            bytes.fill(0)
        }
    }

    /** 擦除义务归调用链：[ImportedPasskey.wipeSecrets] 必须真的清零全部三件秘密材料且幂等。 */
    @Test
    fun `七 调用方擦除入口覆盖全部秘密材料且幂等`() {
        val credential = imported()
        val length = credential.privateKeyPemChars.size
        credential.wipeSecrets()
        assertTrue("私钥字符必须全零", credential.privateKeyPemChars.all { it == '0' })
        assertTrue("withUV 种子必须全零", credential.prfWithUv!!.all { it == 0.toByte() })
        assertTrue("withoutUV 种子必须全零", credential.prfWithoutUv!!.all { it == 0.toByte() })
        credential.wipeSecrets()
        assertEquals("清零不得改变数组长度（否则后续断言无从判断）", length, credential.privateKeyPemChars.size)
    }

    /** 非 32 字节的导入种子：**存得下、用不了**——`PD-49` 裁决二的这一半必须被锁住而非想当然。 */
    @Test
    fun `八 短种子存储保真但 消费时按  字节硬判拒绝`() {
        val short = ByteArray(14) { (it + 1).toByte() }
        val credential = imported().let { base ->
            ImportedPasskey(
                relyingPartyId = base.relyingPartyId,
                credentialId = base.credentialId,
                userHandle = base.userHandle,
                userName = base.userName,
                userDisplayName = base.userDisplayName,
                privateKeyPemChars = base.privateKeyPemChars,
                algorithmId = base.algorithmId,
                prfWithUv = short,
                prfWithoutUv = null
            )
        }
        val data = PasskeyImportFactory.toPasskeyData(credential)
        val stored = requireNotNull(data.toCustomFields().associateBy { it.key }[PasskeyData.KPEX_FIELD_PRF]).value
        val text = stored.readUtf8()
        try {
            assertEquals("长度不判不裁：14 字节原样保真", Base64.getEncoder().encodeToString(short), String(text, Charsets.UTF_8))
        } finally {
            text.fill(0)
        }
        val rejected = runCatching { PasskeyPrf.computeValue(stored, ByteArray(32)) }
        assertTrue(
            "PasskeyPrf.decodeSecret 硬要求 32 字节 ⇒ 短种子在断言时 fail-closed（该边界已登记进限界表）",
            rejected.exceptionOrNull() is IllegalArgumentException
        )
    }
}
