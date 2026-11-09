package com.keepasskey.app.security

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * BiometricCredentialStorage 统一快速解锁封印存储单元测试（Wave 12）：
 * 覆盖 IV+密文持久化往返、按库清理、损坏数据 fail-safe，以及遗留数据清理的容错与
 * 幂等完成标记（ENG-20261028-44：成功才标完成、失败重试、撤销不清标记）。
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
            "getBoolean" -> (memoryStorage[args[0] as String] as? Boolean) ?: (args[1] as Boolean)
            "getAll" -> HashMap(memoryStorage)
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
            "putBoolean" -> {
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

    /**
     * 遗留迁移探针 Context（ENG-20261028-44）：记录 `deleteSharedPreferences` 的调用次数，
     * 并可切换其返回值——对应 Android 官方语义「返回 true 表示遗留 prefs 文件与其 `.bak`
     * 均不存在（含本就不存在）」。默认 [fakeContext] 不覆盖该方法（JVM 下委派到 null 底层而
     * 抛异常），即「清理未确认成功」，与旧用例的容错前提一致。
     */
    private inner class MigrationProbeContext(var deleteSucceeds: Boolean = true) :
        android.content.ContextWrapper(null) {
        var deleteCalls = 0

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs

        override fun deleteSharedPreferences(name: String?): Boolean {
            deleteCalls++
            return deleteSucceeds
        }
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
    fun `revokeAllBiometricData 撤销全部库的封印凭据`() {
        val iv = ByteArray(12) { 5 }
        val ciphertext = ByteArray(16) { 6 }
        storage.saveEncryptedCredential("vault_a", iv, ciphertext)
        storage.saveEncryptedCredential("vault_b", iv, ciphertext)

        // ISSUE-P2-253：关闭开关 = 删除——全部库的封印凭据一并撤销
        storage.revokeAllBiometricData()

        assertFalse(storage.hasEncryptedCredential("vault_a"))
        assertFalse(storage.hasEncryptedCredential("vault_b"))
        assertNull(storage.getEncryptedCredential("vault_a"))
        assertTrue("prefs 必须整体清空", memoryStorage.isEmpty())
    }

    @Test
    fun `revokeAllBiometricData 在无任何登记数据时安全执行`() {
        // Keystore 删除路径（keystoreManager = null）与空 prefs 均不得抛异常
        storage.revokeAllBiometricData()
        assertTrue(memoryStorage.isEmpty())
    }

    @Test
    fun `未登记凭据读取返回 null`() {
        assertNull(storage.getEncryptedCredential("vault_missing"))
        assertFalse(storage.hasEncryptedCredential("vault_missing"))
    }

    // ── ENG-20261028-44：遗留迁移的幂等完成标记与失败重试 ────────────────

    @Test
    fun `未注入KeystoreManager时保留遗留断言键作为重试索引且不触碰当前凭据`() {
        memoryStorage["vault_a_passkey_pub"] = "legacy"
        memoryStorage["vault_a_passkey_mac"] = "legacy"
        memoryStorage["vault_a_iv"] = "current-iv"

        BiometricCredentialStorage(MigrationProbeContext(), keystoreManager = null)

        assertTrue(
            "未注入 KeystoreManager 时必须保留遗留断言键作为重试索引，不得提前删除（ENG-20261028-44）",
            memoryStorage.containsKey("vault_a_passkey_pub")
        )
        assertTrue(memoryStorage.containsKey("vault_a_passkey_mac"))
        assertEquals("当前封印凭据不得被迁移触碰", "current-iv", memoryStorage["vault_a_iv"])
    }

    @Test
    fun `不传KeystoreManager时不得错误标记迁移完成`() {
        val context = MigrationProbeContext(deleteSucceeds = true)
        BiometricCredentialStorage(context, keystoreManager = null)
        assertFalse(
            "未注入 KeystoreManager 时无法清理硬件别名，不得错误标记全清（ENG-20261028-44）",
            memoryStorage.containsKey("legacy_migration_done")
        )
    }

    @Test
    fun `硬件别名删除成功后才清理对应遗留断言键且删除失败的库保留重试索引`() {
        memoryStorage["vault_a_passkey_pub"] = "legacy_pub_a"
        memoryStorage["vault_b_passkey_pub"] = "legacy_pub_b"
        memoryStorage["vault_b_passkey_mac"] = "legacy_mac_b"

        val deletedAliases = mutableListOf<String>()
        val context = MigrationProbeContext(deleteSucceeds = true)

        // 注入 keyDeleter：模拟 vault_b 硬件删除抛异常，vault_a 成功
        BiometricCredentialStorage(
            context = context,
            keystoreManager = null,
            keyDeleter = { alias ->
                if (alias.contains("vault_b")) {
                    throw RuntimeException("Hardware Keystore failure on vault_b")
                }
                deletedAliases.add(alias)
            }
        )

        // vault_a 硬件删除成功，其 prefs 键已清理
        assertFalse("成功库的断言键必须被清理", memoryStorage.containsKey("vault_a_passkey_pub"))
        // vault_b 硬件删除失败，其 prefs 键必须完整保留作为重试索引
        assertTrue("失败库的断言键必须完整保留作为重试索引", memoryStorage.containsKey("vault_b_passkey_pub"))
        assertTrue(memoryStorage.containsKey("vault_b_passkey_mac"))
        // 存在失败，整体不得写完成标记
        assertFalse("存在失败别名时不得标记迁移完成", memoryStorage.containsKey("legacy_migration_done"))

        // 再次构造（模拟下次启动恢复正常）：vault_b 应被重试并清除
        BiometricCredentialStorage(
            context = context,
            keystoreManager = null,
            keyDeleter = { alias -> deletedAliases.add(alias) }
        )
        assertFalse("重试成功后失败库的断言键也被清理", memoryStorage.containsKey("vault_b_passkey_pub"))
        assertFalse(memoryStorage.containsKey("vault_b_passkey_mac"))
        assertTrue("全部成功后必须标记完成", memoryStorage["legacy_migration_done"] == true)
    }

    @Test
    fun `迁移完成标记置位后再次构造不再访问遗留数据`() {
        // 预置完成标记（模拟此前已由注入真实 KeystoreManager 的实例确认成功落盘）
        memoryStorage["legacy_migration_done"] = true
        val context = MigrationProbeContext()

        // 第二个实例（同一进程内再次解析单例 / 下次进程启动的等价路径）直接读标记跳过迁移
        BiometricCredentialStorage(context, keystoreManager = null)
        assertEquals("完成标记落盘后不得重复执行遗留清理", 0, context.deleteCalls)
    }

    @Test
    fun `未完成迁移时下次构造继续重试`() {
        val context = MigrationProbeContext(deleteSucceeds = false)
        var deleterCalls = 0
        val failingDeleter: (String) -> Unit = { deleterCalls++; throw RuntimeException("Keystore unavailable") }

        BiometricCredentialStorage(context, keystoreManager = null, keyDeleter = failingDeleter)
        BiometricCredentialStorage(context, keystoreManager = null, keyDeleter = failingDeleter)
        assertTrue("未完成迁移时每次构造均重试清理", deleterCalls >= 2)
        assertFalse("未确认成功不得写入完成标记", memoryStorage.containsKey("legacy_migration_done"))
    }

    @Test
    fun `撤销全部凭据保留迁移完成标记`() {
        memoryStorage["legacy_migration_done"] = true
        val context = MigrationProbeContext()
        val storage = BiometricCredentialStorage(context, keystoreManager = null)
        storage.saveEncryptedCredential("vault_a", ByteArray(12) { 7 }, ByteArray(16) { 8 })

        storage.revokeAllBiometricData()
        assertFalse("撤销须清掉封印凭据", storage.hasEncryptedCredential("vault_a"))
        assertTrue("撤销凭据必须保留迁移完成标记", memoryStorage["legacy_migration_done"] == true)

        BiometricCredentialStorage(context, keystoreManager = null)
        assertEquals("撤销凭据不得让遗留迁移重跑", 0, context.deleteCalls)
    }
}
