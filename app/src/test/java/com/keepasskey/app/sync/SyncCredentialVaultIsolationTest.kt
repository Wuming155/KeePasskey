package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ActiveDatabaseIdStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 云同步配置**按库隔离**（`ISSUE-P2-465`）——两库两远端两配置、存量一次性迁移、单库清理。
 *
 * ## 替身口径（读断言前必读）
 *
 * `InMemorySharedPreferences.context()` 对任意 prefs 名返回**同一张内存表**，故本类的
 * 「当前活动库」（`keepasskey_vault_meta.active_database_id`）与同步键同处一张表——
 * 这恰好让用例能直接摆放「此刻活动库是谁」这一前提（生产里二者是两个文件）。
 * 库 ID 一律用 `content://` 形态：既避开沙盒裸文件名归一（那由
 * [ActiveVaultSyncNamespaceTest] 单独锁定），也不必让替身实现 `getFilesDir`。
 */
class SyncCredentialVaultIsolationTest {

    private val prefsFake = InMemorySharedPreferences()
    private val memoryStorage: MutableMap<String, Any?> get() = prefsFake.storage

    private lateinit var store: SyncCredentialsStore

    @Before
    fun setUp() {
        memoryStorage.clear()
        // 无会话替身：命名空间由库列表活动项驱动（与生产「未解锁 / 刚切库」路径同源）
        store = SyncCredentialsStore(prefsFake.context(), keystoreManager = null)
        store.customEncryptor = { plaintext ->
            Pair(
                byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
                plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            )
        }
        store.customDecryptor = { _, cipher -> cipher.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() }
    }

    /** 摆放「此刻哪个库是活动库」（null = 无活动库，即库选择器空态 / 全新安装） */
    private fun activateVault(dbId: String?) {
        if (dbId == null) memoryStorage.remove(ActiveDatabaseIdStore.KEY_ACTIVE_DATABASE_ID)
        else memoryStorage[ActiveDatabaseIdStore.KEY_ACTIVE_DATABASE_ID] = dbId
    }

    /** 读取 WebDAV 配置并交还借用语义的密码副本（调用方清零） */
    private fun loadWebDav(): WebDavCredentials? = store.loadWebDavConfig()?.also { it.password.fill('0') }

    @Test
    fun `两库各自配置互不可见_切库后读到本库自己的记录`() {
        activateVault(VAULT_A)
        assertTrue(
            store.saveWebDavConfig(
                url = "https://dav-a.example.com/remote.php/webdav",
                username = "user_a",
                password = "pw_a".toCharArray(),
                remotePath = "/vault-a.kdbx"
            )
        )

        activateVault(VAULT_B)
        assertNull("B 库不得读到 A 库的 WebDAV 配置", store.loadWebDavConfig())
        assertNull("B 库不得读到 A 库的 S3 配置", store.loadS3Config())
        assertEquals(
            "B 库未配置即默认协议，不继承 A 库的选择",
            CloudSyncProvider.WEBDAV,
            store.loadProvider()
        )

        assertTrue(
            store.saveS3Config(
                endpoint = "https://s3-b.example.com", bucket = "bucket-b", region = "auto",
                accessKey = "AKB".toCharArray(), secretKey = "SKB".toCharArray(),
                objectKey = "vault-b.kdbx", usePathStyle = true
            )
        )
        store.saveProvider(CloudSyncProvider.S3_COMPATIBLE)

        activateVault(VAULT_A)
        val a = store.loadWebDavConfig()
        assertNotNull("切回 A 库必须读回 A 库自己的配置", a)
        assertEquals("https://dav-a.example.com/remote.php/webdav", a!!.url)
        assertEquals("user_a", a.username)
        assertArrayEquals("pw_a".toCharArray(), a.password)
        assertEquals("/vault-a.kdbx", a.remotePath)
        a.password.fill('0')
        assertNull("A 库不得读到 B 库的 S3 配置", store.loadS3Config())
        assertEquals(CloudSyncProvider.WEBDAV, store.loadProvider())

        activateVault(VAULT_B)
        val b = store.loadS3Config()
        assertNotNull("切回 B 库必须读回 B 库自己的配置", b)
        assertEquals("https://s3-b.example.com", b!!.endpoint)
        assertEquals("vault-b.kdbx", b.objectKey)
        b.accessKey.fill('0')
        b.secretKey.fill('0')
        assertEquals(CloudSyncProvider.S3_COMPATIBLE, store.loadProvider())
    }

    @Test
    fun `存量全局配置一次性归属迁移时点的活动库_其余库初始未配置`() {
        // 升级前形态：无活动库（尚未解锁过）⇒ 落的是旧全局键
        activateVault(null)
        assertTrue(
            store.saveWebDavConfig(
                url = "https://legacy.example.com/dav/",
                username = "legacy_user",
                password = "legacy_pw".toCharArray(),
                remotePath = "/legacy.kdbx"
            )
        )
        assertNotNull("前提：无活动库时必须落在旧全局键上", memoryStorage["webdav_url"])

        // 升级后首次在活动库 A 的语境下读取 ⇒ 存量配置归属 A 并物理删除全局键
        activateVault(VAULT_A)
        val adopted = loadWebDav()
        assertNotNull("存量全局配置必须归属迁移时点的活动库", adopted)
        assertEquals("/legacy.kdbx", adopted!!.remotePath)
        assertNull("全局键必须被物理清除（一次性迁移）", memoryStorage["webdav_url"])

        // 其余库初始为未配置（不得「捡走」上一个库的配置）
        activateVault(VAULT_B)
        assertNull(store.loadWebDavConfig())
        assertNull(store.loadS3Config())

        // 回到 A：迁移后的配置仍在
        activateVault(VAULT_A)
        assertEquals("/legacy.kdbx", loadWebDav()!!.remotePath)
        assertTrue("迁移后不得遗留任何旧全局键", memoryStorage.keys.none { it == "webdav_url" })
    }

    @Test
    fun `迁移不覆盖该库已有的自己的配置_但全局键照样清除`() {
        // 该库已先配置过（升级后新录）
        activateVault(VAULT_A)
        assertTrue(store.saveWebDavConfig("https://new.example.com/dav/", "u", "p".toCharArray(), "/new.kdbx"))
        // 此时又出现一份历史残留的全局配置
        memoryStorage["webdav_url"] = "https://stale.example.com/dav/"

        assertEquals("https://new.example.com/dav/", loadWebDav()!!.url)
        assertNull("全局键无论如何都必须清除（不留给别的库捡）", memoryStorage["webdav_url"])
    }

    @Test
    fun `clearFor 只清目标库_其余库配置不受影响`() {
        activateVault(VAULT_A)
        assertTrue(store.saveWebDavConfig("https://a.example.com/dav/", "ua", "pa".toCharArray(), "/a.kdbx"))
        activateVault(VAULT_B)
        assertTrue(store.saveWebDavConfig("https://b.example.com/dav/", "ub", "pb".toCharArray(), "/b.kdbx"))

        store.clearFor(VAULT_A)

        activateVault(VAULT_A)
        assertNull("目标库配置必须被清除", store.loadWebDavConfig())
        activateVault(VAULT_B)
        val b = loadWebDav()
        assertNotNull("其余库配置不得被连带清除", b)
        assertEquals("https://b.example.com/dav/", b!!.url)
    }

    @Test
    fun `云端打开导入按落盘路径落凭据_解锁后会话读得到同一份`() {
        val vaultPath = File(
            File(System.getProperty("java.io.tmpdir") ?: ".", "kp-import"),
            "cloud.kdbx"
        ).absolutePath

        // 解锁前：导入链路显式指定库（落盘路径），此刻活动库尚未登记
        activateVault(null)
        assertTrue(
            store.saveWebDavConfig(
                url = "https://cloud.example.com/dav/", username = "u", password = "p".toCharArray(),
                remotePath = "/cloud.kdbx", dbId = vaultPath
            )
        )
        store.saveProvider(CloudSyncProvider.WEBDAV, dbId = vaultPath)
        assertNull("未登记该库时读不到（不得落到全局键上）", store.loadWebDavConfig())
        assertTrue("导入落点不得退化为旧全局键", memoryStorage.keys.none { it == "webdav_url" })

        // 解锁后：该库成为活动库（登记 ID 即落盘路径）⇒ 读到导入时落下的配置
        activateVault(vaultPath)
        assertEquals("/cloud.kdbx", loadWebDav()!!.remotePath)
        assertEquals(CloudSyncProvider.WEBDAV, store.loadProvider())
    }

    @Test
    fun `同步周期解析远端路径严格跟随本库`() {
        activateVault(VAULT_A)
        store.saveWebDavConfig("https://a.example.com/dav/", "ua", "pa".toCharArray(), "/a.kdbx")
        activateVault(VAULT_B)
        store.saveS3Config(
            "https://s3-b.example.com", "bucket-b", "auto",
            "AKB".toCharArray(), "SKB".toCharArray(), "b.kdbx", usePathStyle = true
        )
        store.saveProvider(CloudSyncProvider.S3_COMPATIBLE)

        val resolver = SyncProviderResolver(store, SyncPreferences(DebugLogBuffer(), null), DebugLogBuffer())

        activateVault(VAULT_A)
        assertEquals("/a.kdbx", resolver.resolveRemotePath("fallback.kdbx"))
        activateVault(VAULT_B)
        assertEquals("b.kdbx", resolver.resolveRemotePath("fallback.kdbx"))

        // 清空 B 后不得回落到 A 的远端路径（未配置即按默认文件名，绝不指向别的库）
        store.clearFor(VAULT_B)
        assertEquals("/fallback.kdbx", resolver.resolveRemotePath("fallback.kdbx"))
    }

    private companion object {
        const val VAULT_A = "content://docs/vault-a.kdbx"
        const val VAULT_B = "content://docs/vault-b.kdbx"
    }
}
