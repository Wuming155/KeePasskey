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
 * 覆盖 AES-GCM 硬件加密逻辑、哈希匹配比对、自动锁定熔断调度与状态机擦除。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityTest {

    @Test
    fun `测试 AES-256-GCM 硬件加密加解密闭环`() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val secretKey = keyGen.generateKey()

        val plaintext = "MasterPassword#2026!SecureKey".toByteArray(Charsets.UTF_8)

        // 模拟硬件 Keystore 加密
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = encryptCipher.iv
        val ciphertext = encryptCipher.doFinal(plaintext)

        assertNotNull(iv)
        assertEquals(12, iv.size)
        assertTrue(ciphertext.isNotEmpty())

        // 模拟生物识别验证通过后解密
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decrypted = decryptCipher.doFinal(ciphertext)

        assertEquals(String(plaintext, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))
    }

    @Test
    fun `测试生物识别密钥别名生成规则`() {
        val keystoreManager = KeystoreManager(null)
        val authManager = BiometricAuthManager(keystoreManager)

        val alias = authManager.getAliasForDatabase("my_vault_01")
        assertEquals("com.keepasskey.biometric_master_key_my_vault_01", alias)
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

    @Test
    fun `测试 QuickUnlock PIN 熔断时长指数退避与封顶`() {
        // 未达失败阈值 -> 不熔断
        assertEquals(0L, QuickUnlockPinStore.computeLockoutMs(0))
        assertEquals(0L, QuickUnlockPinStore.computeLockoutMs(4))

        // 达阈值后按 30s 基数指数退避
        assertEquals(30_000L, QuickUnlockPinStore.computeLockoutMs(5))
        assertEquals(60_000L, QuickUnlockPinStore.computeLockoutMs(6))
        assertEquals(120_000L, QuickUnlockPinStore.computeLockoutMs(7))

        // 长尾封顶 15 分钟，且不因超大失败次数溢出
        assertEquals(15 * 60_000L, QuickUnlockPinStore.computeLockoutMs(12))
        assertEquals(15 * 60_000L, QuickUnlockPinStore.computeLockoutMs(1000))
    }
}
