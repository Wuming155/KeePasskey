package com.keepasskey.app.security

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * BiometricCredentialStorage 统一快速解锁封印存储单元测试（Wave 12）：
 * 覆盖 IV+密文持久化往返、按库清理、损坏数据 fail-safe 与遗留 QuickUnlock 数据清理容错。
 *
 * 测试策略：SharedPreferences 以 java.lang.reflect.Proxy 内存实现模拟；
 * Base64 编解码使用 java.util.Base64（生产实现同源），可在 JVM 直接验证真实往返。
 */
class BiometricCredentialStorageTest {

    private val memoryStorage = mutableMapOf<String, Any?>()

    private val fakePrefs = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getString" -> {
                val key = args[0] as String
                val def = args[1] as? String
                (memoryStorage[key] as? String) ?: def
            }
            "contains" -> memoryStorage.containsKey(args[0] as String)
            "edit" -> fakeEditor
            else -> null
        }
    } as SharedPreferences

    private val fakeEditor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "putString" -> {
                memoryStorage[args[0] as String] = args[1]
                proxy
            }
            "remove" -> {
                memoryStorage.remove(args[0] as String)
                proxy
            }
            "clear" -> {
                memoryStorage.clear()
                proxy
            }
            "apply", "commit" -> null
            else -> proxy
        }
    } as SharedPreferences.Editor

    private val fakeContext: Context = object : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
    }

    private lateinit var storage: BiometricCredentialStorage

    @Before
    fun setUp() {
        memoryStorage.clear()
        // keystoreManager = null：JVM 环境无 Android Keystore；遗留清理路径须静默容错不抛异常
        storage = BiometricCredentialStorage(fakeContext, keystoreManager = null)
    }

    @Test
    fun `封印凭据保存与读取往返一致`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(48) { (it * 7).toByte() }

        storage.saveEncryptedCredential("vault_a", iv, ciphertext)

        val loaded = storage.getEncryptedCredential("vault_a")
        assertTrue(loaded != null)
        assertArrayEquals(iv, loaded!!.first)
        assertArrayEquals(ciphertext, loaded.second)
        assertTrue(storage.hasEncryptedCredential("vault_a"))
    }

    @Test
    fun `按库清理仅移除目标数据库凭据`() {
        val iv = ByteArray(12) { 1 }
        val ciphertext = ByteArray(16) { 2 }
        storage.saveEncryptedCredential("vault_a", iv, ciphertext)
        storage.saveEncryptedCredential("vault_b", iv, ciphertext)

        storage.clearCredential("vault_a")

        assertNull(storage.getEncryptedCredential("vault_a"))
        assertFalse(storage.hasEncryptedCredential("vault_a"))
        assertTrue(storage.hasEncryptedCredential("vault_b"))
    }

    @Test
    fun `损坏的 Base64 数据读取时 fail-safe 返回 null`() {
        memoryStorage["vault_bad_iv"] = "not-base64!!!"
        memoryStorage["vault_bad_cipher"] = "@@@@"

        // 解码失败返回 null（fail-safe），由调用方引导重新登记；原始数据保持原状不清除
        assertNull(storage.getEncryptedCredential("vault_bad"))
        assertTrue(memoryStorage.containsKey("vault_bad_iv"))
        assertTrue(memoryStorage.containsKey("vault_bad_cipher"))
    }

    @Test
    fun `clearAll 清空全部封印凭据`() {
        val iv = ByteArray(12) { 3 }
        val ciphertext = ByteArray(16) { 4 }
        storage.saveEncryptedCredential("vault_a", iv, ciphertext)
        storage.saveEncryptedCredential("vault_b", iv, ciphertext)

        storage.clearAll()

        assertNull(storage.getEncryptedCredential("vault_a"))
        assertNull(storage.getEncryptedCredential("vault_b"))
    }

    @Test
    fun `未登记凭据读取返回 null`() {
        assertNull(storage.getEncryptedCredential("vault_missing"))
        assertFalse(storage.hasEncryptedCredential("vault_missing"))
    }
}
