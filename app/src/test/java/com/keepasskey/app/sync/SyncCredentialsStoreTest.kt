package com.keepasskey.app.sync

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * SyncCredentialsStore 凭据持久化与加解密往返单元测试 (Wave 3-E P2-19)
 *
 * Wave 15 整改补充：CharArray 借用语义（保存后调用方数组擦除）、
 * 保存结果如实回传（封印失败返回 false 且不写密文）、空密码清除旧密文。
 */
class SyncCredentialsStoreTest {

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
            "getBoolean" -> {
                val key = args[0] as String
                val def = args[1] as? Boolean ?: false
                (memoryStorage[key] as? Boolean) ?: def
            }
            "contains" -> {
                memoryStorage.containsKey(args[0] as String)
            }
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
            "apply", "commit" -> {
                null
            }
            else -> proxy
        }
    } as SharedPreferences.Editor

    private val fakeContext: Context = object : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
    }

    private lateinit var store: SyncCredentialsStore

    @Before
    fun setUp() {
        memoryStorage.clear()
        store = SyncCredentialsStore(fakeContext, keystoreManager = null)

        // 注入恒等/模拟可逆加解密闭包 (Wave 3-E 验收规范)
        store.customEncryptor = { plaintext ->
            val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            val cipher = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            Pair(iv, cipher)
        }
        store.customDecryptor = { _, cipher ->
            cipher.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun `WebDAV 凭据保存加密与读取解密 round-trip`() {
        val password = "Secret#Password#2026".toCharArray()
        store.saveWebDavConfig(
            url = "https://dav.example.com/remote.php/webdav",
            username = "dav_admin",
            password = password,
            remotePath = "/keepass/vault.kdbx"
        )
        // Wave 15 借用语义：保存后调用方密码数组被立即擦除
        assertTrue(password.all { it == '0' })

        val loaded = store.loadWebDavConfig()
        assertNotNull(loaded)
        assertEquals("https://dav.example.com/remote.php/webdav", loaded!!.url)
        assertEquals("dav_admin", loaded.username)
        assertArrayEquals("Secret#Password#2026".toCharArray(), loaded.password)
        assertEquals("/keepass/vault.kdbx", loaded.remotePath)
        // 读取侧同样为借用语义：消费方用毕清零
        loaded.password.fill('0')
    }

    @Test
    fun `S3 凭据保存加密与读取解密 round-trip`() {
        val accessKey = "AKIAIOSFODNN7EXAMPLE".toCharArray()
        val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toCharArray()
        store.saveS3Config(
            endpoint = "https://s3.ap-northeast-1.amazonaws.com",
            bucket = "my-secure-vault",
            region = "ap-northeast-1",
            accessKey = accessKey,
            secretKey = secretKey,
            objectKey = "databases/primary.kdbx"
        )
        // Wave 15 借用语义：保存后调用方密钥数组被立即擦除
        assertTrue(accessKey.all { it == '0' })
        assertTrue(secretKey.all { it == '0' })

        val loaded = store.loadS3Config()
        assertNotNull(loaded)
        assertEquals("https://s3.ap-northeast-1.amazonaws.com", loaded!!.endpoint)
        assertEquals("my-secure-vault", loaded.bucket)
        assertEquals("ap-northeast-1", loaded.region)
        assertArrayEquals("AKIAIOSFODNN7EXAMPLE".toCharArray(), loaded.accessKey)
        assertArrayEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toCharArray(), loaded.secretKey)
        assertEquals("databases/primary.kdbx", loaded.objectKey)
        loaded.accessKey.fill('0')
        loaded.secretKey.fill('0')
    }

    @Test
    fun `封印失败时保存如实返回 false 且不写入密文`() {
        store.customEncryptor = { throw IllegalStateException("keystore unavailable") }
        val password = "Secret#Password#2026".toCharArray()

        val saved = store.saveWebDavConfig(
            url = "https://dav.example.com/remote.php/webdav",
            username = "dav_admin",
            password = password,
            remotePath = "/keepass/vault.kdbx"
        )

        assertFalse(saved)
        assertNull(memoryStorage["webdav_password_iv"])
        assertNull(memoryStorage["webdav_password_cipher"])
        // 失败路径同样擦除调用方数组（fail-closed，绝不残留明文）
        assertTrue(password.all { it == '0' })
        // 无密文落盘 → 加载返回 null（上层按 fail-closed 要求显式重录）
        assertNull(store.loadWebDavConfig())
    }

    @Test
    fun `S3 封印失败时保存如实返回 false`() {
        store.customEncryptor = { throw IllegalStateException("keystore unavailable") }

        val saved = store.saveS3Config(
            endpoint = "https://s3.ap-northeast-1.amazonaws.com",
            bucket = "my-secure-vault",
            region = "ap-northeast-1",
            accessKey = "AKIAIOSFODNN7EXAMPLE".toCharArray(),
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY".toCharArray(),
            objectKey = "databases/primary.kdbx"
        )

        assertFalse(saved)
        assertNull(memoryStorage["s3_access_key_iv"])
        assertNull(memoryStorage["s3_secret_iv"])
    }

    @Test
    fun `空密码保存显式清除旧密文`() {
        store.saveWebDavConfig(
            url = "https://dav.example.com/remote.php/webdav",
            username = "dav_admin",
            password = "old_secret".toCharArray(),
            remotePath = "/keepass/vault.kdbx"
        )
        assertNotNull(memoryStorage["webdav_password_cipher"])

        val cleared = store.saveWebDavConfig(
            url = "https://dav.example.com/remote.php/webdav",
            username = "dav_admin",
            password = CharArray(0),
            remotePath = "/keepass/vault.kdbx"
        )

        assertTrue(cleared)
        assertNull(memoryStorage["webdav_password_iv"])
        assertNull(memoryStorage["webdav_password_cipher"])
        val loaded = store.loadWebDavConfig()
        assertNotNull(loaded)
        assertEquals(0, loaded!!.password.size)
    }

    @Test
    fun `Provider 切换与 clear 行为`() {
        store.saveProvider(CloudSyncProvider.S3_COMPATIBLE)
        assertEquals(CloudSyncProvider.S3_COMPATIBLE, store.loadProvider())

        store.clear()
        assertNull(store.loadWebDavConfig())
        assertNull(store.loadS3Config())
        assertEquals(CloudSyncProvider.WEBDAV, store.loadProvider())
    }

    @Test
    fun `加载时物理清除 Wave 14 前遗留的证书锁定键`() {
        // 模拟旧版本残留的证书锁定配置（Wave 14 已整体移除该功能）
        memoryStorage["webdav_cert_pins"] = "dav.example.com=sha256/AAAA="
        memoryStorage["webdav_url"] = "https://dav.example.com/remote.php/webdav"

        val loaded = store.loadWebDavConfig()

        // 遗留键必须在加载期一次性物理清除，杜绝残留配置继续留存
        assertNotNull(loaded)
        assertNull(memoryStorage["webdav_cert_pins"])
    }
}
