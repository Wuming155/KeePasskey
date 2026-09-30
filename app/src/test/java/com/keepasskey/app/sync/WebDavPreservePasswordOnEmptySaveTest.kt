package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 空密码保存必须保留已保存口令（改远程路径不得抹掉测连凭据）。
 */
class WebDavPreservePasswordOnEmptySaveTest {

    private lateinit var store: SyncCredentialsStore
    private val prefsFake = InMemorySharedPreferences()

    @Before
    fun setUp() {
        prefsFake.clear()
        store = SyncCredentialsStore(prefsFake.context(), null, DebugLogBuffer())
        store.customEncryptor = { plain ->
            val cipher = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12) to cipher
        }
        store.customDecryptor = { _, cipher ->
            cipher.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test
    fun `空密码保存后既有口令仍可读出`() {
        val first = "nutstore-secret".toCharArray()
        assertTrue(store.saveWebDavConfig("https://dav.jianguoyun.com/dav/", "u@e.com", first, "keepasskey.kdbx"))
        assertTrue(
            store.saveWebDavConfig(
                "https://dav.jianguoyun.com/dav/",
                "u@e.com",
                CharArray(0),
                "email/keepasskey.kdbx"
            )
        )
        val reloaded = store.loadWebDavConfig()
        assertNotNull(reloaded)
        assertEquals("nutstore-secret", String(reloaded!!.password))
        assertEquals("email/keepasskey.kdbx", reloaded.remotePath)
        reloaded.password.fill('0')
    }

    @Test
    fun `非空密码会覆盖旧口令`() {
        assertTrue(store.saveWebDavConfig("https://dav.jianguoyun.com/dav/", "u@e.com", "old".toCharArray(), "keepasskey.kdbx"))
        assertTrue(store.saveWebDavConfig("https://dav.jianguoyun.com/dav/", "u@e.com", "new".toCharArray(), "keepasskey.kdbx"))
        val reloaded = store.loadWebDavConfig()
        assertNotNull(reloaded)
        assertEquals("new", String(reloaded!!.password))
        reloaded.password.fill('0')
    }

    @Test
    fun `从未保存过口令时空密码保存不会凭空出现口令`() {
        assertTrue(store.saveWebDavConfig("https://dav.jianguoyun.com/dav/", "u@e.com", CharArray(0), "keepasskey.kdbx"))
        val reloaded = store.loadWebDavConfig()
        if (reloaded != null) {
            assertEquals(0, reloaded.password.size)
            reloaded.password.fill('0')
        }
    }
}
