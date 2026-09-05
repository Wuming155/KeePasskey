package com.keepasskey.app.sync

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * SyncCredentialsStore 凭据持久化与加解密往返单元测试 (Wave 3-E P2-19)
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
        store.saveWebDavConfig(
            url = "https://dav.example.com/remote.php/webdav",
            username = "dav_admin",
            password = "Secret#Password#2026",
            remotePath = "/keepass/vault.kdbx"
        )

        val loaded = store.loadWebDavConfig()
        assertNotNull(loaded)
        assertEquals("https://dav.example.com/remote.php/webdav", loaded!!.url)
        assertEquals("dav_admin", loaded.username)
        assertEquals("Secret#Password#2026", loaded.password)
        assertEquals("/keepass/vault.kdbx", loaded.remotePath)
    }

    @Test
    fun `S3 凭据保存加密与读取解密 round-trip`() {
        store.saveS3Config(
            endpoint = "https://s3.ap-northeast-1.amazonaws.com",
            bucket = "my-secure-vault",
            region = "ap-northeast-1",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            objectKey = "databases/primary.kdbx"
        )

        val loaded = store.loadS3Config()
        assertNotNull(loaded)
        assertEquals("https://s3.ap-northeast-1.amazonaws.com", loaded!!.endpoint)
        assertEquals("my-secure-vault", loaded.bucket)
        assertEquals("ap-northeast-1", loaded.region)
        assertEquals("AKIAIOSFODNN7EXAMPLE", loaded.accessKey)
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", loaded.secretKey)
        assertEquals("databases/primary.kdbx", loaded.objectKey)
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
}
