package com.keepasskey.database

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.crypto.cipher.CipherFactory
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 真实世界官方 KeePass 4.0 库与复合密钥自动化互操作回归测试。
 *
 * 夹具说明（测试专用，请勿当作生产凭据）：
 * - [fixtures/test_vault.kdbx] 与 [fixtures/111.keyx] 为一次性测试库，
 *   主密码以 `xdqaCEGFEAHBETAH72732` 开头（含斜杠与星号等特殊字符，完整值见下方常量）、
 *   密钥文件因子为 XML KeyFile v2.0，凭据仅用于本测试，绝不可复用。
 * - 该夹具实测为 **AES-256-CBC + Argon2d + GZip**（非 ChaCha20），
 *   ChaCha20 的写侧路径由本测试内的「切换 Cipher 重序列化」用例单独覆盖。
 *
 * 覆盖：
 * - 真实 AES-256-CBC + Argon2d + XML KeyFile v2.0 复合密钥解锁
 * - 子群组与条目明文密码校验
 * - 序列化保存往返一致性 + 写侧契约断言（CipherUUID 保留、EncryptionIV 长度 == 算法 IV 长度，钉死 P0-4）
 * - ChaCha20 Cipher 重序列化往返（覆盖 P0-4 回归区域：ChaCha20 须写 12 字节 nonce）
 * - 错误凭据拦截防线
 */
class RealKdbxInteroperabilityTest {

    private val masterPassword = "xdqaCEGFEAHBETAH72732/*632.".toCharArray()

    private fun loadResourceBytes(name: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: error("未找到测试资源: $name")
        return stream.use { it.readBytes() }
    }

    @Test
    fun loadRealVaultWithCompositeKey_successAndVerifyEntry() {
        val kdbxBytes = loadResourceBytes("fixtures/test_vault.kdbx")
        val keyFileBytes = loadResourceBytes("fixtures/111.keyx")

        val database = KdbxFile.load(
            ByteArrayInputStream(kdbxBytes),
            masterPassword,
            keyFileBytes
        )

        assertNotNull("数据库加载不能为空", database)
        val allGroups = database.rootGroup.allGroups()
        val targetGroup = allGroups.find { it.name == "111" }
        assertNotNull("必须包含名为 '111' 的分组", targetGroup)

        val allEntries = database.rootGroup.allEntries()
        val targetEntry = allEntries.find { it.title == "11" }
        assertNotNull("必须包含标题为 '11' 的条目", targetEntry)

        val passwordField = targetEntry!!.password
        assertNotNull("密码字段不能为空", passwordField)
        assertEquals("密码明文必须与真实官方 KeePass 完全一致", "~W4hUziUy7FSRR#K@N@K", passwordField!!.readString())

        // 往返保存并重新加载测试
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, database, masterPassword, keyFileBytes)
        val savedBytes = bos.toByteArray()
        assertTrue("保存后的字节流必须大于零", savedBytes.isNotEmpty())

        // 写侧契约断言：保存后重加载的外层头部必须保留原始 CipherUUID，
        // 且 EncryptionIV 长度必须严格等于该算法的 IV 长度（钉死 P0-4：
        // 曾恒写 16 字节，凡 ChaCha20 保存文件官方均打不开）。
        val reloaded = KdbxFile.load(
            ByteArrayInputStream(savedBytes),
            masterPassword,
            keyFileBytes
        )
        assertEquals(
            "保存往返不得变更 Cipher 算法 (AES-256-CBC)",
            KdbxConstants.Cipher.AES_256_CBC,
            reloaded.header.cipherUuid
        )
        assertEquals(
            "EncryptionIV 长度必须等于 AES-256-CBC 算法 IV 长度 (16)",
            CipherFactory.getEngine(KdbxConstants.Cipher.AES_256_CBC).ivLength,
            reloaded.header.encryptionIv.size
        )

        val reloadedEntry = reloaded.rootGroup.allEntries().find { it.title == "11" }
        assertNotNull(reloadedEntry)
        assertEquals("~W4hUziUy7FSRR#K@N@K", reloadedEntry!!.password?.readString())
    }

    /**
     * ChaCha20 写侧互操作覆盖（P0-4 回归区域）：
     * 将真实 AES 库在内存中切换为 ChaCha20 Cipher 后重序列化并重新加载，
     * 校验 CipherUUID 保留为 ChaCha20、EncryptionIV 长度为 12（RFC 7539 nonce），
     * 且条目明文仍正确。直接钉死「ChaCha20 保存恒写 12 字节 IV」的契约，
     * 避免回归导致官方客户端无法打开。
     */
    @Test
    fun saveWithChaCha20Cipher_preservesCipherAndIvLength() {
        val kdbxBytes = loadResourceBytes("fixtures/test_vault.kdbx")
        val keyFileBytes = loadResourceBytes("fixtures/111.keyx")

        val database = KdbxFile.load(
            ByteArrayInputStream(kdbxBytes),
            masterPassword,
            keyFileBytes
        )

        val chachaDb = database.copy(
            header = database.header.copy(cipherUuid = KdbxConstants.Cipher.CHACHA20)
        )

        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, chachaDb, masterPassword, keyFileBytes)
        val savedBytes = bos.toByteArray()
        assertTrue("保存后的字节流必须大于零", savedBytes.isNotEmpty())

        val reloaded = KdbxFile.load(
            ByteArrayInputStream(savedBytes),
            masterPassword,
            keyFileBytes
        )
        assertEquals(
            "Cipher 算法必须为 ChaCha20",
            KdbxConstants.Cipher.CHACHA20,
            reloaded.header.cipherUuid
        )
        assertEquals(
            "ChaCha20 EncryptionIV 长度必须为 12 字节 (RFC 7539 nonce)",
            KdbxConstants.Cipher.CHACHA20_NONCE_LENGTH,
            reloaded.header.encryptionIv.size
        )
        assertEquals(
            "ChaCha20 EncryptionIV 长度必须与引擎 ivLength 一致",
            CipherFactory.getEngine(KdbxConstants.Cipher.CHACHA20).ivLength,
            reloaded.header.encryptionIv.size
        )

        val reloadedEntry = reloaded.rootGroup.allEntries().find { it.title == "11" }
        assertNotNull(reloadedEntry)
        assertEquals("~W4hUziUy7FSRR#K@N@K", reloadedEntry!!.password?.readString())
    }

    @Test
    fun loadRealVaultWithMissingKeyFile_failsWithInvalidCredentials() {
        val kdbxBytes = loadResourceBytes("fixtures/test_vault.kdbx")
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(
                ByteArrayInputStream(kdbxBytes),
                masterPassword,
                keyFileData = null
            )
        }
    }

    @Test
    fun loadRealVaultWithWrongPassword_failsWithInvalidCredentials() {
        val kdbxBytes = loadResourceBytes("fixtures/test_vault.kdbx")
        val keyFileBytes = loadResourceBytes("fixtures/111.keyx")
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(
                ByteArrayInputStream(kdbxBytes),
                "wrong_password".toCharArray(),
                keyFileBytes
            )
        }
    }
}
