package com.keepasskey.app.ui.screens.settings

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeSettingsRepository
import com.keepasskey.app.security.BiometricAuthManager
import com.keepasskey.app.security.BiometricCredentialStorage
import com.keepasskey.app.security.BiometricResult
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.unlock.BiometricSealedPayloadCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ISSUE-P2-398：改密成功后生物识别封印凭据**重封印**协调器单测。
 *
 * 覆盖验收标准：
 * 1. AC① 生物识别启用且已封印 → 陈旧封印被**新密码**封印替换（落库载荷可解码出新密码与密钥文件因子）；
 * 2. AC② 生物识别未启用 / 未封印 / 无活动库 → 不弹窗、不动存储；
 * 3. AC③ 授权取消 / 封印密钥供给失败 → 陈旧封印已摘除、新封印不落库；
 * 4. AC⑤ 载荷明文与密钥文件快照不驻留（密封路径 `finally` 清零为源码守卫）。
 *
 * 测试边界如实说明：Android `BiometricPrompt` / `FragmentActivity` / 真实 Keystore 无法在 JVM
 * 构造，封印密钥供给、强生物识别探测与 Prompt 发起三处触点经 `*Override` 替身注入；
 * 授权 Cipher 用桌面 JVM AES/GCM（测试密钥）模拟，落库载荷以生产 [BiometricSealedPayloadCodec]
 * 解码回读（行为级判据，非 mock 自证）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricResealCoordinatorTest {

    // ── AC①：成功重封印 ─────────────────────────────────────────────────

    @Test
    fun `改密后陈旧封印被新密码封印替换且载荷可解码出新密码`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val coordinator = createCoordinator(storage = storage)

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        val sealed = storage.getEncryptedCredential(ACTIVE_DB_ID)
        assertNotNull("重封印后必须存在新封印凭据", sealed)
        val (iv, ciphertext) = sealed!!
        // 新 IV / 新密文（AES-GCM 随机 IV ⇒ 与旧封印不同）
        assertFalse("不得复用陈旧封印的 IV", iv.contentEquals(OLD_IV))
        val payload = decryptWithTestKey(ciphertext, iv)
        val decoded = BiometricSealedPayloadCodec.decode(payload)
        try {
            assertArrayEquals("封印载荷必须承载新密码", NEW_PASSWORD, decoded.passwordChars)
            // ISSUE-P1-431：封印载荷不再承载密钥文件因子（StrongBox 大载荷不可用）
            assertNull("新口径封印载荷不得再携带密钥文件字节", decoded.keyFileData)
        } finally {
            decoded.wipe()
            payload.fill(0)
        }
    }

    @Test
    fun `改密后重封印对新密码可直接复验密封结果`() = runTest {
        // 上一例验证「解得开、内容对」；本例从密封侧复验：同一测试密钥可解密且认证标签通过，
        // 排除「存进去的是旧载荷」的假阳性
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val coordinator = createCoordinator(storage = storage)

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        val sealed = requireNotNull(storage.getEncryptedCredential(ACTIVE_DB_ID))
        val payload = decryptWithTestKey(sealed.second, sealed.first)
        try {
            val decoded = BiometricSealedPayloadCodec.decode(payload)
            assertArrayEquals(NEW_PASSWORD, decoded.passwordChars)
        } finally {
            payload.fill(0)
        }
    }

    // ── AC②：前置不满足即空操作 ─────────────────────────────────────────

    @Test
    fun `生物识别未启用时不动存储且不弹窗`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val coordinator = createCoordinator(
            storage = storage,
            biometricEnabled = false
        )

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        // 未启用 → 未登记语义：陈旧封印保持原样（不摘除、不重封），下次启用后自然走登记/恢复通道
        val sealed = requireNotNull(storage.getEncryptedCredential(ACTIVE_DB_ID))
        assertArrayEquals(OLD_IV, sealed.first)
        assertArrayEquals(OLD_CIPHERTEXT, sealed.second)
    }

    @Test
    fun `无已封印凭据时不弹窗且不落库`() = runTest {
        val storage = inMemoryCredentialStorage()
        val coordinator = createCoordinator(storage = storage)
        var promptCalls = 0
        coordinator.promptOverride = { _, _, _ -> promptCalls++ }

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        assertEquals("未登记时不得发起 BiometricPrompt（登记交由下次主密码解锁自然完成）", 0, promptCalls)
        assertFalse(storage.hasEncryptedCredential(ACTIVE_DB_ID))
    }

    @Test
    fun `无活动库时整体空操作`() = runTest {
        val storage = inMemoryCredentialStorage()
        val coordinator = createCoordinator(storage = storage, databaseId = null)
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        val sealed = requireNotNull(storage.getEncryptedCredential(ACTIVE_DB_ID))
        assertArrayEquals(OLD_IV, sealed.first)
        assertArrayEquals(OLD_CIPHERTEXT, sealed.second)
    }

    // ── AC③：失败路径 fail-safe ─────────────────────────────────────────

    @Test
    fun `用户取消授权时陈旧封印已摘除且新封印不落库`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val coordinator = createCoordinator(storage = storage)
        coordinator.promptOverride = { _, _, onResult -> onResult(BiometricResult.Cancelled) }

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        assertFalse("取消后不得残留注定失败的旧封印", storage.hasEncryptedCredential(ACTIVE_DB_ID))
    }

    @Test
    fun `封印密钥供给失败时摘除陈旧封印且不落库`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val coordinator = createCoordinator(storage = storage)
        coordinator.sealKeyProvisionOverride = { null }
        var promptCalls = 0
        coordinator.promptOverride = { _, _, _ -> promptCalls++ }

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        assertEquals("密钥不可用时不得发起 BiometricPrompt", 0, promptCalls)
        assertFalse(storage.hasEncryptedCredential(ACTIVE_DB_ID))
    }

    @Test
    fun `软件级落位未经降级确认时不重封印`() = runTest {
        // ISSUE-P1-22 闸门不得被重封印路径绕过：SOFTWARE 落位 + 无确认记录 → 中止（不弹窗、不落库）
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val settings = FakeSettingsRepository()
        settings.setBiometricEnabled(true)
        val coordinator = BiometricResealCoordinator(
            settingsRepository = settings,
            activeDbId = { ACTIVE_DB_ID },
            biometricAuthManager = BiometricAuthManager(KeystoreManager(null)),
            biometricCredentialStorage = storage,
            strings = StringsProvider { _, _ -> "" },
            debugLog = DebugLogBuffer()
        )
        coordinator.sealKeyProvisionOverride = { provisionOf(KeystoreManager.KeySecurityLevel.SOFTWARE) }
        coordinator.strongBiometricCheckOverride = { true }
        var promptCalls = 0
        coordinator.promptOverride = { _, _, _ -> promptCalls++ }

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        assertEquals("降级闸门未过时不得发起 BiometricPrompt", 0, promptCalls)
        assertFalse(storage.hasEncryptedCredential(ACTIVE_DB_ID))
    }

    @Test
    fun `无强生物识别时摘除陈旧封印且不落库`() = runTest {
        val storage = inMemoryCredentialStorage()
        storage.saveEncryptedCredential(ACTIVE_DB_ID, OLD_IV, OLD_CIPHERTEXT)
        val coordinator = createCoordinator(storage = storage)
        coordinator.strongBiometricCheckOverride = { false }
        var promptCalls = 0
        coordinator.promptOverride = { _, _, _ -> promptCalls++ }

        coordinator.resealAfterMasterKeyChange(activity = null, newPasswordChars = NEW_PASSWORD)

        assertEquals("能力校验不过时不得发起 BiometricPrompt", 0, promptCalls)
        assertFalse(storage.hasEncryptedCredential(ACTIVE_DB_ID))
    }

    // ── AC⑤：敏感缓冲不驻留（源码守卫） ─────────────────────────────────

    @Test
    fun `载荷明文必须在密封后清零`() {
        val source = readSource(RESEAL_COORDINATOR_PATH)
        val encode = source.substringAfter("val bytes = BiometricSealedPayloadCodec.encode")
        val sealBlock = encode.substringBefore("} catch (e: Exception)")
        assertTrue(
            "载荷明文必须在密封完成后 finally 清零（敏感数据铁律）",
            Regex("""try\s*\{[^}]*authorizeAndSeal[^}]*\}\s*finally\s*\{\s*bytes\.fill\(0\)""").containsMatchIn(sealBlock)
        )
    }

    // ── 装配与替身 ──────────────────────────────────────────────────────

    private suspend fun createCoordinator(
        storage: BiometricCredentialStorage,
        databaseId: String? = ACTIVE_DB_ID,
        biometricEnabled: Boolean = true
    ): BiometricResealCoordinator {
        val settings = FakeSettingsRepository()
        settings.setBiometricEnabled(biometricEnabled)
        val coordinator = BiometricResealCoordinator(
            settingsRepository = settings,
            activeDbId = { databaseId },
            biometricAuthManager = BiometricAuthManager(KeystoreManager(null)),
            biometricCredentialStorage = storage,
            strings = StringsProvider { _, _ -> "" },
            debugLog = DebugLogBuffer()
        )
        // 桌面 JVM AES/GCM 替身封印密钥（测试密钥，仅存于测试进程），落位按硬件放行；
        // 默认替身 Prompt：授权通过并回传同一授权 Cipher（模拟 BiometricPrompt CryptoObject 成功）
        val sealCipher = Cipher.getInstance("AES/GCM/NoPadding")
        sealCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(TEST_KEY, "AES"))
        coordinator.sealKeyProvisionOverride = {
            com.keepasskey.app.ui.screens.unlock.SealedKeyProvision(
                sealCipher,
                KeystoreManager.KeySecurityLevel.TRUSTED_ENVIRONMENT
            )
        }
        coordinator.promptOverride = { _, cipher, onResult -> onResult(BiometricResult.Success(cipher)) }
        coordinator.strongBiometricCheckOverride = { true }
        return coordinator
    }

    /** 以授权态把替身 Cipher 回传给协调器（模拟 BiometricPrompt 成功携带 CryptoObject） */
    private fun provisionOf(level: KeystoreManager.KeySecurityLevel): com.keepasskey.app.ui.screens.unlock.SealedKeyProvision {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(TEST_KEY, "AES"))
        return com.keepasskey.app.ui.screens.unlock.SealedKeyProvision(cipher, level)
    }

    private fun decryptWithTestKey(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(TEST_KEY, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * JVM 内存版封印凭据存储：`SharedPreferences` 以 `java.lang.reflect.Proxy` 替换，
     * Base64 编解码与按库存在性判定全部走生产实现（与 `BiometricEnableCoordinatorTest` 同源做法）。
     */
    private fun inMemoryCredentialStorage(): BiometricCredentialStorage {
        val entries = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    entries[args[0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    entries.remove(args[0] as String)
                    proxy
                }
                "clear" -> {
                    entries.clear()
                    proxy
                }
                "apply", "commit" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> (entries[args[0] as String] as? String) ?: args[1] as? String
                "getAll" -> HashMap(entries)
                "contains" -> entries.containsKey(args[0] as String)
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
        return BiometricCredentialStorage(
            object : ContextWrapper(null) {
                override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            },
            keystoreManager = null
        )
    }

    private fun readSource(path: String): String {
        val file = java.io.File(repositoryRoot, path)
        assertTrue("源文件不存在: $path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ACTIVE_DB_ID = "db_personal"
        val NEW_PASSWORD = "New-Master-Pass#2026".toCharArray()
        val OLD_IV = ByteArray(12) { (it + 1).toByte() }
        val OLD_CIPHERTEXT = ByteArray(16) { (it + 1).toByte() }

        /** 测试专用 AES-256 密钥（仅 JVM 测试进程，非生产向量） */
        val TEST_KEY = ByteArray(32) { (it * 7 + 3).toByte() }

        const val RESEAL_COORDINATOR_PATH =
            "app/src/main/java/com/keepasskey/app/ui/screens/settings/BiometricResealCoordinator.kt"

        val repositoryRoot: java.io.File by lazy {
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(4) {
                val candidate = dir ?: return@repeat
                if (java.io.File(candidate, "app/src/main/java").isDirectory &&
                    java.io.File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根目录")
        }
    }
}
