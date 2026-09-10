package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Twofish 头部数据库的**端到端**读写用例（ISSUE-P3-35 验收标准 4）。
 *
 * 覆盖链路：`KdbxFile.save` → CipherFactory 分派 Twofish → 加密流 → HMAC 块流 →
 * GZip → XML 流式写出；反向 `KdbxFile.load` 走 `KdbxCipherKeyResolver` 的首块解密探针
 * （该探针正是**依赖解密流在收尾时抛 `IOException`** 来截断半截密文的路径，
 * 见 [com.keepasskey.crypto.cipher.CbcDecryptingInputStream] 的契约说明）。
 *
 * 原生可用性：`database/build.gradle.kts` 已把 `:crypto` 的宿主原生库注入本模块单测 JVM，
 * 因此**装了 cargo 的环境下本用例走原生 Twofish 路径，否则自动降级 BouncyCastle**；
 * 两条路径都必须通过——这是「原生与兜底可互换」的端到端证据。
 */
class KdbxTwofishRoundTripTest {

    private fun twofishHeader(): KdbxHeader =
        KdbxHeader.createDefault(
            cipherUuid = KdbxConstants.Cipher.TWOFISH,
            useArgon2 = false
        ).copy(kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 9 }, rounds = 50L))

    private fun sampleDatabase(): KdbxDatabase {
        val entry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Twofish Site", isProtected = false),
                KdbxConstants.Fields.USER_NAME to ProtectedString("twofish-user", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Tw0fish!Secret#2026", isProtected = true),
                KdbxConstants.Fields.URL to ProtectedString("https://example.org", isProtected = false)
            ),
            customFields = listOf(KdbxCustomField("PIN", ProtectedString("4321", isProtected = true))),
            tags = listOf("twofish", "e2e")
        )
        return KdbxDatabase(
            header = twofishHeader(),
            databaseName = "TwofishVault",
            databaseDescription = "Twofish E2E",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
    }

    @Test
    fun `Twofish 头部数据库保存后能原样读回`() {
        val password = "TwofishMaster@2026".toCharArray()
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, sampleDatabase(), password)

        val fileBytes = bos.toByteArray()
        assertTrue("产物应包含头部与载荷", fileBytes.size > 200)

        // 头部密文标识必须真的是 Twofish（防「测试以为测了 Twofish 实则走 AES」）
        val loaded = KdbxFile.load(ByteArrayInputStream(fileBytes), password)
        assertEquals(KdbxConstants.Cipher.TWOFISH, loaded.header.cipherUuid)
        assertEquals("TwofishVault", loaded.databaseName)
        assertEquals("Twofish E2E", loaded.databaseDescription)

        val entries = loaded.rootGroup.allEntries()
        assertEquals(1, entries.size)
        assertEquals("Twofish Site", entries[0].title)
        assertEquals("twofish-user", entries[0].userName)
        assertEquals("Tw0fish!Secret#2026", entries[0].password?.readString())
        assertEquals("4321", entries[0].customFields[0].value.readString())
        assertEquals(listOf("twofish", "e2e"), entries[0].tags)
    }

    @Test
    fun `Twofish 库错误主密码被拒绝`() {
        val password = "TwofishMaster@2026".toCharArray()
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, sampleDatabase(), password)
        val fileBytes = bos.toByteArray()

        try {
            KdbxFile.load(ByteArrayInputStream(fileBytes), "WrongPassword".toCharArray())
            fail("错误密码必须抛出异常")
        } catch (e: IOException) {
            assertTrue(
                "错误密码应以凭据/HMAC 语义失败，实际: ${e.message}",
                e.message?.contains("密码") == true || e.message?.contains("HMAC") == true
            )
        }
    }

    @Test
    fun `Twofish 库载荷被篡改后被拒绝`() {
        val password = "TwofishMaster@2026".toCharArray()
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, sampleDatabase(), password)
        val fileBytes = bos.toByteArray()

        // 翻转载荷区靠后的一字节（避开头部，命中 HMAC 块流覆盖范围）
        val tampered = fileBytes.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        try {
            KdbxFile.load(ByteArrayInputStream(tampered), password)
            fail("篡改载荷必须被 HMAC 块流拒绝")
        } catch (e: IOException) {
            assertTrue("应以认证失败语义拒绝，实际: ${e.message}", e.message?.contains("HMAC") == true)
        }
    }

    /** 空库（仅根分组、无条目）也必须能往返——覆盖「载荷解压后仅内层 Header + 极短 XML」边界。 */
    @Test
    fun `Twofish 空库往返`() {
        val password = "EmptyVault@2026".toCharArray()
        val db = KdbxDatabase(
            header = twofishHeader(),
            databaseName = "EmptyTwofish",
            rootGroup = KdbxGroup(name = "Root")
        )
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, db, password)

        val loaded = KdbxFile.load(ByteArrayInputStream(bos.toByteArray()), password)
        assertEquals("EmptyTwofish", loaded.databaseName)
        assertEquals(0, loaded.rootGroup.allEntries().size)
    }
}
