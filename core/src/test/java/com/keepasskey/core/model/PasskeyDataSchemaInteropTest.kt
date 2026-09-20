package com.keepasskey.core.model

import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通行密钥落库 schema 单测（本次整改：**对齐 KeePassXC / KeePassDX 的 `KPEX_PASSKEY_*`**）。
 *
 * 整改前本仓用自造键名 `Passkey.*` + hex/Base64 私钥形态，结果是「库文件级零互操作」：
 * KeePassXC / KeePassDX 读不到本仓写出的通行密钥，本仓也读不到它们写的。
 *
 * 本文件锁定三件事：
 * 1. **写入口径**：KPEX 键名 + Kotlin 侧保护位（credentialId / userHandle / 私钥 / PRF 秘密受保护）；
 * 2. **读入兼容**：外部管理器条目（无 `Passkey.Algorithm` 扩展键）按 PKCS#8 OID 嗅探算法；
 * 3. **历史 v1 只读兼容**：旧键名 + hex/Base64 私钥仍可解析（既有库无需迁移）。
 */
class PasskeyDataSchemaInteropTest {

    /** 构造一段含指定 OID 的伪 PKCS#8 DER 并包成 PEM（嗅探只做 OID 子序列匹配，无需结构合法） */
    private fun pemCharsOf(vararg oidDer: ByteArray): CharArray =
        PasskeyKeyText.derToPemChars(oidDer.reduce { acc, bytes -> acc + bytes })

    private val oidEs256 = byteArrayOf(0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01)
    private val oidEd25519 = byteArrayOf(0x06, 0x03, 0x2B, 0x65, 0x70)
    private val oidRs256 = byteArrayOf(
        0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01
    )

    private fun unprotected(value: String): KdbxCustomField =
        KdbxCustomField("x", ProtectedString(value, isProtected = false))

    // ── 写入口径 ──

    @Test
    fun `写出 KPEX 键名且敏感字段逐一受保护`() {
        val privateKey = ProtectedString("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n", isProtected = true)
        val prfSecret = ProtectedString("c2VjcmV0", isProtected = true)
        val data = PasskeyData(
            relyingPartyId = "example.com",
            userHandle = "dXNlci1pZA",
            userName = "alice",
            userDisplayName = "Alice",
            credentialId = "cred-a",
            algorithmId = PasskeyData.ALGORITHM_ES256,
            publicKeyBase64 = "cHVibGlj",
            privateKey = privateKey,
            signCount = 7,
            backupEligible = true,
            backupState = false,
            prfSecret = prfSecret
        )

        val fields = data.toCustomFields().associateBy { it.key }

        assertEquals("alice", fields[PasskeyData.KPEX_FIELD_USERNAME]?.value?.readString())
        assertEquals("example.com", fields[PasskeyData.KPEX_FIELD_RELYING_PARTY]?.value?.readString())
        assertTrue(
            "Credential ID 按 KeePassXC / KeePassDX 口径受保护",
            fields[PasskeyData.KPEX_FIELD_CREDENTIAL_ID]?.value?.isProtected == true
        )
        assertTrue(
            "User Handle 按 KeePassXC / KeePassDX 口径受保护",
            fields[PasskeyData.KPEX_FIELD_USER_HANDLE]?.value?.isProtected == true
        )
        assertTrue(
            "私钥必须受保护",
            fields[PasskeyData.KPEX_FIELD_PRIVATE_KEY]?.value?.isProtected == true
        )
        assertSame(
            "私钥字段必须与模型持有同一实例（零拷贝别名，落库前严禁 clear）",
            privateKey,
            fields[PasskeyData.KPEX_FIELD_PRIVATE_KEY]?.value
        )
        assertSame("PRF 秘密字段必须与模型持有同一实例", prfSecret, fields[PasskeyData.KPEX_FIELD_PRF]?.value)
        assertTrue("PRF 秘密必须受保护", fields[PasskeyData.KPEX_FIELD_PRF]?.value?.isProtected == true)
        assertEquals("1", fields[PasskeyData.KPEX_FIELD_FLAG_BE]?.value?.readString())
        assertEquals("0", fields[PasskeyData.KPEX_FIELD_FLAG_BS]?.value?.readString())
        assertEquals("7", fields[PasskeyData.FIELD_SIGN_COUNT]?.value?.readString())
        // 不得再写出 v1 键名（否则同一库出现两套不自洽的字段）
        assertNull(fields[PasskeyData.LEGACY_FIELD_RP_ID])
        assertNull(fields[PasskeyData.LEGACY_FIELD_PRIVATE_KEY])
        assertNull(fields[PasskeyData.LEGACY_FIELD_CREDENTIAL_ID])
    }

    @Test
    fun `KPEX 写出后可无损读回`() {
        val data = PasskeyData(
            relyingPartyId = "example.com",
            userHandle = "handle",
            userName = "alice",
            userDisplayName = "Alice",
            credentialId = "cred-a",
            algorithmId = PasskeyData.ALGORITHM_ED25519,
            publicKeyBase64 = "cHVibGlj",
            privateKey = ProtectedString("priv"),
            signCount = 3,
            backupEligible = false,
            backupState = true,
            prfSecret = ProtectedString("c2VjcmV0")
        )

        val restored = PasskeyData.fromCustomFields(data.toCustomFields())
        assertNotNull(restored)
        assertEquals(data.relyingPartyId, restored!!.relyingPartyId)
        assertEquals(data.userHandle, restored.userHandle)
        assertEquals(data.userName, restored.userName)
        assertEquals(data.userDisplayName, restored.userDisplayName)
        assertEquals(data.credentialId, restored.credentialId)
        assertEquals(data.algorithmId, restored.algorithmId)
        assertEquals(data.publicKeyBase64, restored.publicKeyBase64)
        assertEquals(data.signCount, restored.signCount)
        assertFalse(restored.backupEligible)
        assertTrue(restored.backupState)
        assertEquals("c2VjcmV0", restored.prfSecret?.readString())
    }

    // ── 外部管理器条目（KeePassXC / KeePassDX 写出）：无扩展键，按 OID 嗅探算法 ──

    @Test
    fun `无 Passkey Algorithm 键时按 PKCS#8 OID 嗅探算法`() {
        fun readKeyAlgorithm(oidDer: ByteArray): Int? {
            val pem = pemCharsOf(oidDer)
            val fields = listOf(
                KdbxCustomField(
                    PasskeyData.KPEX_FIELD_RELYING_PARTY,
                    ProtectedString("example.com", isProtected = false)
                ),
                KdbxCustomField(
                    PasskeyData.KPEX_FIELD_CREDENTIAL_ID,
                    ProtectedString("cred-a", isProtected = true)
                ),
                KdbxCustomField(
                    PasskeyData.KPEX_FIELD_PRIVATE_KEY,
                    ProtectedString(pem, isProtected = true)
                )
            )
            return PasskeyData.fromCustomFields(fields)?.algorithmId
        }

        assertEquals(PasskeyData.ALGORITHM_ES256, readKeyAlgorithm(oidEs256))
        assertEquals(PasskeyData.ALGORITHM_ED25519, readKeyAlgorithm(oidEd25519))
        assertEquals(PasskeyData.ALGORITHM_RS256, readKeyAlgorithm(oidRs256))
    }

    @Test
    fun `外部条目缺 User Handle 与用户名时回落空串而不失败`() {
        val pem = pemCharsOf(oidEs256)
        val fields = listOf(
            KdbxCustomField(PasskeyData.KPEX_FIELD_RELYING_PARTY, ProtectedString("example.com", isProtected = false)),
            KdbxCustomField(PasskeyData.KPEX_FIELD_CREDENTIAL_ID, ProtectedString("cred-a", isProtected = true)),
            KdbxCustomField(PasskeyData.KPEX_FIELD_PRIVATE_KEY, ProtectedString(pem, isProtected = true))
        )

        val restored = PasskeyData.fromCustomFields(fields)
        assertNotNull(restored)
        assertEquals("", restored!!.userHandle)
        assertEquals("", restored.userName)
        assertEquals("", restored.publicKeyBase64)
        assertTrue("BE / BS 缺省按可备份处理", restored.backupEligible && restored.backupState)
        assertNull("未携带 PRF 秘密时应为 null", restored.prfSecret)
    }

    // ── 历史 v1 只读兼容 ──

    @Test
    fun `v1 旧键名与布尔字面量仍可解析`() {
        val fields = listOf(
            KdbxCustomField(PasskeyData.LEGACY_FIELD_RP_ID, ProtectedString("legacy.example", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_CREDENTIAL_ID, ProtectedString("old-cred", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_PRIVATE_KEY, ProtectedString("deadbeef", isProtected = true)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_USER_NAME, ProtectedString("bob", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_USER_HANDLE, ProtectedString("old-handle", isProtected = false)),
            KdbxCustomField(PasskeyData.FIELD_ALGORITHM, ProtectedString("-8", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_BACKUP_ELIGIBLE, ProtectedString("false", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_BACKUP_STATE, ProtectedString("true", isProtected = false))
        )

        val restored = PasskeyData.fromCustomFields(fields)
        assertNotNull(restored)
        assertEquals("legacy.example", restored!!.relyingPartyId)
        assertEquals("old-cred", restored.credentialId)
        assertEquals("bob", restored.userName)
        assertEquals("old-handle", restored.userHandle)
        assertEquals(PasskeyData.ALGORITHM_ED25519, restored.algorithmId)
        assertFalse(restored.backupEligible)
        assertTrue(restored.backupState)
    }

    @Test
    fun `KPEX 优先于 v1 同义键`() {
        val fields = listOf(
            KdbxCustomField(PasskeyData.KPEX_FIELD_RELYING_PARTY, ProtectedString("new.example", isProtected = false)),
            KdbxCustomField(PasskeyData.KPEX_FIELD_CREDENTIAL_ID, ProtectedString("new-cred", isProtected = true)),
            KdbxCustomField(
                PasskeyData.KPEX_FIELD_PRIVATE_KEY,
                ProtectedString(pemCharsOf(oidEs256), isProtected = true)
            ),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_RP_ID, ProtectedString("old.example", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_CREDENTIAL_ID, ProtectedString("old-cred", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_PRIVATE_KEY, ProtectedString("deadbeef", isProtected = true))
        )

        val restored = PasskeyData.fromCustomFields(fields)
        assertNotNull(restored)
        assertEquals("new.example", restored!!.relyingPartyId)
        assertEquals("new-cred", restored.credentialId)
    }

    // ── BE / BS 文本宽松解析（`1/0` 与 `true/false` 混用） ──

    @Test
    fun `BE-BS 文本兼容 1-0 与 true-false 与非法值`() {
        assertTrue(PasskeyData.parseFlag("1", default = false))
        assertTrue(PasskeyData.parseFlag("true", default = false))
        assertTrue(PasskeyData.parseFlag("YES", default = false))
        assertFalse(PasskeyData.parseFlag("0", default = true))
        assertFalse(PasskeyData.parseFlag("False", default = true))
        assertFalse(PasskeyData.parseFlag("no", default = true))
        assertTrue("无法识别的文本必须回落调用方缺省", PasskeyData.parseFlag("banana", default = true))
        assertFalse(PasskeyData.parseFlag(null, default = false))
    }

    // ── ISSUE-P3-213：v1 → KPEX 就地迁移的判据 ──

    @Test
    fun `持有 v1 旧键且 KPEX 核心不齐备才判定为待迁移`() {
        fun legacy(vararg extra: KdbxCustomField) = listOf(
            KdbxCustomField(PasskeyData.LEGACY_FIELD_RP_ID, ProtectedString("legacy.example", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_CREDENTIAL_ID, ProtectedString("old-cred", isProtected = true)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_PRIVATE_KEY, ProtectedString("deadbeef", isProtected = true))
        ) + extra

        assertTrue("纯 v1 旧条目必须待迁移", PasskeyData.needsKpexMigration(legacy()))
        assertTrue(
            "只有扩展键（计数器 / 展示名等）而不含 KPEX 核心三键时仍待迁移",
            PasskeyData.needsKpexMigration(
                legacy(
                    KdbxCustomField(PasskeyData.FIELD_SIGN_COUNT, ProtectedString("3", isProtected = false)),
                    KdbxCustomField(PasskeyData.FIELD_ALGORITHM, ProtectedString("-7", isProtected = false))
                )
            )
        )
        assertFalse(
            "KPEX 核心三键齐备即不再是待迁移对象（哪怕残留 v1 旧键）",
            PasskeyData.needsKpexMigration(
                legacy(
                    KdbxCustomField(PasskeyData.KPEX_FIELD_RELYING_PARTY, ProtectedString("new.example", isProtected = false)),
                    KdbxCustomField(PasskeyData.KPEX_FIELD_CREDENTIAL_ID, ProtectedString("new-cred", isProtected = true)),
                    KdbxCustomField(PasskeyData.KPEX_FIELD_PRIVATE_KEY, ProtectedString("pem", isProtected = true))
                )
            )
        )
        assertFalse(
            "本就无 v1 旧键的 KPEX 条目不得被判为待迁移（避免每次断言都走一遍迁移分支）",
            PasskeyData.needsKpexMigration(
                listOf(
                    KdbxCustomField(PasskeyData.KPEX_FIELD_RELYING_PARTY, ProtectedString("new.example", isProtected = false)),
                    KdbxCustomField(PasskeyData.KPEX_FIELD_CREDENTIAL_ID, ProtectedString("new-cred", isProtected = true)),
                    KdbxCustomField(PasskeyData.KPEX_FIELD_PRIVATE_KEY, ProtectedString("pem", isProtected = true))
                )
            )
        )
        assertFalse("非 passkey 条目（含完全无自定义字段）不得被判为待迁移", PasskeyData.needsKpexMigration(emptyList()))
        assertFalse(
            "普通条目的无关自定义字段不得被判为待迁移",
            PasskeyData.needsKpexMigration(listOf(unprotected("JBSWY3DPEHPK3PXP")))
        )
    }

    @Test
    fun `迁移判据不物化任何明文（受保护值在被判据扫描后仍可正常读取）`() {
        val secret = ProtectedString("c2VjcmV0", isProtected = true)
        val fields = listOf(
            KdbxCustomField(PasskeyData.LEGACY_FIELD_RP_ID, ProtectedString("legacy.example", isProtected = false)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_CREDENTIAL_ID, ProtectedString("old-cred", isProtected = true)),
            KdbxCustomField(PasskeyData.LEGACY_FIELD_PRIVATE_KEY, secret)
        )

        assertTrue(PasskeyData.needsKpexMigration(fields))
        assertEquals("判据只扫键名，不得读取（并因此改写 / 清零）任何字段值", "c2VjcmV0", secret.readString())
    }

    @Test
    fun `未知字段不影响解析且必需键仍强制`() {
        val required = listOf(
            KdbxCustomField(PasskeyData.KPEX_FIELD_RELYING_PARTY, ProtectedString("example.com", isProtected = false)),
            KdbxCustomField(PasskeyData.KPEX_FIELD_CREDENTIAL_ID, ProtectedString("cred", isProtected = true)),
            KdbxCustomField(PasskeyData.KPEX_FIELD_PRIVATE_KEY, ProtectedString("priv", isProtected = true))
        )
        val unknownField = KdbxCustomField(
            "KPEX_PASSKEY_GENERATED_USER_ID",
            ProtectedString("dXNlci1pZA", isProtected = false)
        )

        assertNotNull("外部管理器追加的未知 KPEX 属性不得干扰解析", PasskeyData.fromCustomFields(required + unknownField))
        assertNull(
            "KPEX 三必需键缺私钥即视为非通行密钥条目",
            PasskeyData.fromCustomFields(listOf(required[0], required[1], unknownField))
        )
    }
}
