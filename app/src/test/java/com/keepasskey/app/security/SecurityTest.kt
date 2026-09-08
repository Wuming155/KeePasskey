package com.keepasskey.app.security

import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * 阶段 3 系统级安全加固与生物识别机制单元测试：
 * 覆盖 AES-GCM 加解密语义、哈希匹配比对、自动锁定熔断调度与状态机擦除。
 *
 * P2-35 澄清（TASK-40）：本测试运行于 JVM，`KeyGenerator` 为 JDK 软件实现——
 * 验证的是 AES-256-GCM 算法语义与完整性保证，**非** AndroidKeyStore 硬件路径；
 * 硬件隔离（TEE/StrongBox）与生物识别绑定属 Instrumented 测试范畴，此处不虚标。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityTest {

    @Test
    fun `测试 AES-256-GCM 加解密闭环（JDK 软件密钥算法语义）`() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val plaintext = "MasterPassword#2026!SecureKey".toByteArray(Charsets.UTF_8)

        // JDK 软件密钥 AES-GCM 加密（生产封印路径同款 TRANSFORMATION；硬件隔离见 Instrumented 测试）
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = encryptCipher.iv
        val ciphertext = encryptCipher.doFinal(plaintext)

        assertNotNull(iv)
        assertEquals(12, iv.size)
        assertTrue(ciphertext.isNotEmpty())

        // 解密还原
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decrypted = decryptCipher.doFinal(ciphertext)

        assertEquals(String(plaintext, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    /**
     * AES-GCM 完整性保证回归锁（P2-35 补强）：密文或认证标签任一比特被篡改，
     * 解密必须失败（AEADBadTagException）——绝不返回被篡改的明文。
     */
    @Test
    fun `GCM 密文篡改时解密必须 fail-closed 抛认证异常`() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val plaintext = "integrity-check-payload".toByteArray(Charsets.UTF_8)
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = encryptCipher.iv
        val ciphertext = encryptCipher.doFinal(plaintext)

        // 篡改密文首字节
        val tampered = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val failed = try {
            decryptCipher.doFinal(tampered)
            false
        } catch (_: javax.crypto.AEADBadTagException) {
            true
        }
        assertTrue("GCM 认证标签校验必须拒绝被篡改的密文", failed)
    }

    @Test
    fun `测试生物识别密钥别名生成规则`() {
        val keystoreManager = KeystoreManager(null)
        val authManager = BiometricAuthManager(keystoreManager)

        val alias = authManager.getAliasForDatabase("my_vault_01")
        assertEquals("com.keepasskey.biometric_master_key_my_vault_01", alias)
    }

    /**
     * Wave 12 敏感数据卫生回归锁：GCM 封印路径每次独立 init Cipher，
     * 平台必须为每次加密生成全新随机 IV——同一密钥连续封印绝不复用 IV（AES-GCM 灾难性失效条件）。
     */
    @Test
    fun `GCM 随机 IV 连续初始化不重复`() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val ivs = (1..16).map {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            cipher.iv
        }

        assertEquals(16, ivs.toSet().size)
        ivs.forEach { iv -> assertEquals(12, iv.size) }
    }

    @Test
    fun `测试剪贴板敏感数据 SHA-256 哈希比对逻辑`() {
        val original = "SuperSecretPassword123"
        val altered = "UserPastedSomethingElse"

        val md = MessageDigest.getInstance("SHA-256")
        val originalHash = md.digest(original.toByteArray(Charsets.UTF_8))
        md.reset()
        val matchHash = md.digest(original.toByteArray(Charsets.UTF_8))
        md.reset()
        val alteredHash = md.digest(altered.toByteArray(Charsets.UTF_8))

        assertTrue(originalHash.contentEquals(matchHash))
        assertFalse(originalHash.contentEquals(alteredHash))
    }

    @Test
    fun `测试会话自动锁定熔断与内存清空`() = runTest {
        val session = DatabaseSession()
        val settingsRepository = FakeSettingsRepository()

        // 初始状态
        assertEquals(DatabaseSession.SessionState.CLOSED, session.state.value)

        // 打开假定数据库
        val header = KdbxHeader.createDefault(KdbxConstants.Cipher.AES_256_CBC, false)
        val db = KdbxDatabase(header, "TestVault", "", KdbxGroup(name = "Root"))
        session.setDatabaseForTesting(db)
        assertEquals(DatabaseSession.SessionState.OPENED, session.state.value)

        // 模拟触发自动锁定熔断
        session.lock()

        // 验证已彻底锁定且数据库引用已销毁
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
        assertEquals(null, session.databaseFlow.value)
    }

    @Test
    fun `测试后台停留超时判定逻辑`() {
        val now = 100_000L
        val backgroundTimestamp = 30_000L
        val timeoutSeconds = 60 // 60秒

        val elapsedMillis = now - backgroundTimestamp
        val timeoutMillis = timeoutSeconds * 1000L

        // 离开 70 秒，超时 60 秒 -> 应触发熔断
        assertTrue(elapsedMillis >= timeoutMillis)

        // 离开 20 秒，未超时 -> 不触发熔断
        val recentBackground = 85_000L
        val elapsedShort = now - recentBackground
        assertFalse(elapsedShort >= timeoutMillis)
    }
}
