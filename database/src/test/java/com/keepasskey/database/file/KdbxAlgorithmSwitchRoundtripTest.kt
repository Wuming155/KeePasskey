package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 「会话内切换加密算法 / KDF 变体 → 保存 → 重开解锁」的 `:database:` 层端到端往返
 * （ISSUE-P2-271 AC⑤ 的 database 面验收）。
 *
 * 设置页选择器（`SettingsPreferencesController`）经 `updateDatabaseMeta` 写入
 * `KdbxHeader.cipherUuid` / `kdfParameters` 后由 `KdbxFile.save` 落盘：save 按保存时
 * cipherUuid 生成**该算法官方长度**的全新 EncryptionIV（ChaCha20 12B / AES、Twofish
 * 16B，P0-4 契约），并按保存时 kdfParameters 以全新随机 salt/seed 重派生加密密钥。
 * 本测试在 database 层锁定该写侧语义：头部换轴后旧凭据仍能解锁新文件（密钥按新参数
 * 真实重派生，而非只改头），且旧加密产物不再被误判。
 *
 * 原生可用性：`database/build.gradle.kts` 已把 `:crypto` 的宿主原生库注入本模块单测 JVM，
 * 装了 cargo 的环境走原生内核，否则自动降级 JCE / BouncyCastle；两条路径都必须通过。
 */
class KdbxAlgorithmSwitchRoundtripTest {

    private fun sampleDatabase(header: KdbxHeader): KdbxDatabase {
        val entry = KdbxEntry(
            id = KdbxUuid.random(),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Switch Probe", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("Switch!Secret#2026", isProtected = true)
            )
        )
        return KdbxDatabase(
            header = header,
            databaseName = "SwitchVault",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
    }

    private fun roundtrip(
        header: KdbxHeader,
        password: CharArray,
        verify: (KdbxDatabase) -> Unit
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, sampleDatabase(header), password)
        val fileBytes = bos.toByteArray()
        val loaded = KdbxFile.load(ByteArrayInputStream(fileBytes), password)
        verify(loaded)
        val entries = loaded.rootGroup.allEntries()
        assertEquals(1, entries.size)
        assertEquals("Switch!Secret#2026", entries[0].password?.readString())
        return fileBytes
    }

    @Test
    fun `会话头换 ChaCha20 后保存重开外层头为 ChaCha20 且 IV 为官方 12 字节`() {
        val base = KdbxHeader.createDefault() // AES-256-CBC + Argon2id（建库缺省形态）
        val switched = base.copy(cipherUuid = KdbxConstants.Cipher.CHACHA20)
        val password = "SwitchChaCha@2026".toCharArray()

        roundtrip(switched, password) { loaded ->
            assertEquals(KdbxConstants.Cipher.CHACHA20, loaded.header.cipherUuid)
            assertEquals(
                "ChaCha20 的 EncryptionIV 必须为 RFC 7539 的 12 字节 nonce（P0-4 契约）",
                KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH,
                loaded.header.encryptionIv.size
            )
        }

        // 旧 AES 头产物与新 ChaCha20 头产物不同密文（换算法真实生效，非同文件重写）
        val oldBytes = ByteArrayOutputStream().also { KdbxFile.save(it, sampleDatabase(base), password) }.toByteArray()
        val newBytes = ByteArrayOutputStream().also { KdbxFile.save(it, sampleDatabase(switched), password) }.toByteArray()
        assertTrue("换算法后的密文应有实质差异", oldBytes.size != newBytes.size || !oldBytes.contentEquals(newBytes))
    }

    @Test
    fun `会话头换 Twofish 后保存重开外层头为 Twofish 且可解锁`() {
        val base = KdbxHeader.createDefault()
        val switched = base.copy(cipherUuid = KdbxConstants.Cipher.TWOFISH)
        val password = "SwitchTwofish@2026".toCharArray()

        roundtrip(switched, password) { loaded ->
            assertEquals(KdbxConstants.Cipher.TWOFISH, loaded.header.cipherUuid)
            assertEquals(16, loaded.header.encryptionIv.size)
        }
    }

    @Test
    fun `Argon2id 换 Argon2d 携带 I·M·P 保存重开读回同型且错误密码被拒`() {
        val base = KdbxHeader.createDefault()
        val argon2id = base.kdfParameters as KdfParameters.Argon2
        // 模拟换型携带现行 I/M/P（设置层 copy(type) 的 database 面等价操作）
        val switched = base.copy(
            kdfParameters = argon2id.copy(type = KdfParameters.Argon2.Argon2Type.ARGON2D)
        )
        val password = "SwitchArgon2d@2026".toCharArray()

        roundtrip(switched, password) { loaded ->
            val kdf = loaded.header.kdfParameters
            assertTrue("KDF 应为 Argon2，实际 $kdf", kdf is KdfParameters.Argon2)
            kdf as KdfParameters.Argon2
            assertEquals(KdfParameters.Argon2.Argon2Type.ARGON2D, kdf.type)
            assertEquals(argon2id.iterations, kdf.iterations)
            assertEquals(argon2id.memoryInBytes, kdf.memoryInBytes)
            assertEquals(argon2id.parallelism, kdf.parallelism)
        }

        try {
            KdbxFile.load(
                ByteArrayInputStream(
                    ByteArrayOutputStream().also { KdbxFile.save(it, sampleDatabase(switched), password) }.toByteArray()
                ),
                "WrongPassword".toCharArray()
            )
            org.junit.Assert.fail("错误密码必须被拒绝（密钥按新变体真实重派生）")
        } catch (e: IOException) {
            assertTrue(
                "应以凭据/HMAC 语义失败，实际: ${e.message}",
                e.message?.contains("密码") == true || e.message?.contains("HMAC") == true
            )
        }
    }

    @Test
    fun `换入 AES-KDF 补齐 rounds 后保存重开读回同型`() {
        val base = KdbxHeader.createDefault()
        // 模拟换入 AES-KDF 补齐 rounds/seed（设置层构造 Aes 目标变体的 database 面等价操作）
        val switched = base.copy(
            kdfParameters = KdfParameters.Aes(seed = ByteArray(32) { 7 }, rounds = 50L)
        )
        val password = "SwitchAesKdf@2026".toCharArray()

        roundtrip(switched, password) { loaded ->
            val kdf = loaded.header.kdfParameters
            assertTrue("KDF 应为 AES-KDF，实际 $kdf", kdf is KdfParameters.Aes)
            assertEquals(50L, (kdf as KdfParameters.Aes).rounds)
        }
    }

    @Test
    fun `自 AES-KDF 换入 Argon2id 按缺省补齐 I·M·P 后保存重开读回同型`() {
        val aesBase = KdbxHeader.createDefault(useArgon2 = false)
        // 模拟换入 Argon2id 按类型缺省补齐（设置层构造 Argon2 目标变体的 database 面等价操作）
        val switched = aesBase.copy(
            kdfParameters = KdfParameters.Argon2(
                type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
                salt = ByteArray(32) { 5 }
            )
        )
        val password = "SwitchBackArgon2@2026".toCharArray()

        roundtrip(switched, password) { loaded ->
            val kdf = loaded.header.kdfParameters
            assertTrue("KDF 应为 Argon2，实际 $kdf", kdf is KdfParameters.Argon2)
            kdf as KdfParameters.Argon2
            assertEquals(KdfParameters.Argon2.Argon2Type.ARGON2ID, kdf.type)
            assertEquals("换入侧 iterations 应为类型缺省 2", 2L, kdf.iterations)
            assertEquals(64L * 1024 * 1024, kdf.memoryInBytes)
            assertEquals(2, kdf.parallelism)
        }
    }
}
