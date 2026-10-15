package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream

class SyncProviderResolverOwnershipTest {

    @Test
    fun `配置描述不解封凭据且生产配置可在没有测试Provider时构造`() = runTest {
        val fixture = Fixture()
        fixture.saveS3()
        var decryptCalls = 0
        fixture.store.customDecryptor = { _, cipher ->
            decryptCalls++
            cipher.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
        }

        val target = fixture.resolver.configuredTarget("fallback.kdbx")

        assertNotNull(target)
        assertEquals("vault/object.kdbx", target!!.remotePath)
        assertTrue(target.identity.isNotBlank())
        assertTrue(fixture.resolver.hasConfiguredProvider())
        assertEquals("metadata lookup must not decrypt secrets", 0, decryptCalls)
        assertEquals("configuration lookup must not construct a Provider", 0, fixture.factory.createCalls)

        assertTrue(
            fixture.store.saveS3Config(
                endpoint = "https://s3.example.test",
                bucket = "bucket",
                region = "auto",
                accessKey = CharArray(0),
                secretKey = CharArray(0),
                objectKey = "vault/changed.kdbx"
            )
        )
        val changedTarget = fixture.resolver.configuredTarget("fallback.kdbx")
        assertEquals("vault/changed.kdbx", changedTarget?.remotePath)
        assertTrue("target changes must invalidate in-flight probe results", changedTarget!!.identity != target.identity)
        assertEquals("metadata lookup must not decrypt secrets", 0, decryptCalls)

        val result = fixture.resolver.useProvider(testOverride = null) { provider ->
            assertTrue("must use the persisted production configuration", provider === fixture.factory.provider)
            "used"
        }

        assertEquals("used", result)
        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.factory.releaseCalls)
        assertAllZero(fixture.factory.accessKeyClone!!)
        assertAllZero(fixture.factory.secretKeyClone!!)
    }

    @Test
    fun `创建Provider时返回的目标身份来自同一配置快照`() = runTest {
        val fixture = Fixture()
        fixture.saveS3()
        val expected = requireNotNull(fixture.resolver.configuredTarget("fallback.kdbx"))
        var captured: SyncProviderTarget? = null

        val result = fixture.resolver.useProviderWithTarget("fallback.kdbx") { provider, target ->
            assertTrue(provider === fixture.factory.provider)
            captured = target
            "used"
        }

        assertEquals("used", result)
        assertEquals("provider 的 endpoint/path 身份必须与同一配置描述一致", expected, captured)
        assertEquals(1, fixture.factory.releaseCalls)
        assertAllZero(fixture.factory.accessKeyClone!!)
        assertAllZero(fixture.factory.secretKeyClone!!)
    }

    @Test
    fun `S3 拥有凭据在提前返回异常和取消时均释放`() = runTest {
        val fixture = Fixture()
        fixture.saveS3()

        assertEquals(
            "early",
            fixture.resolver.useProvider(testOverride = null) { "early" }
        )
        assertReleased(fixture.factory)

        try {
            fixture.resolver.useProvider(testOverride = null) { throw IllegalStateException("synthetic") }
            throw AssertionError("expected callback exception")
        } catch (_: IllegalStateException) {
            // 下方断言 Provider 的释放结果。
        }
        assertReleased(fixture.factory)

        try {
            fixture.resolver.useProvider(testOverride = null) { throw CancellationException("synthetic") }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // 取消必须在 Provider 清理后继续向上传播。
        }
        assertReleased(fixture.factory)
        assertEquals(3, fixture.factory.releaseCalls)
    }

    @Test
    fun `S3 Provider构造失败会清除已克隆的凭据`() = runTest {
        val fixture = Fixture()
        fixture.saveS3()
        fixture.factory.failS3Creation = true

        try {
            fixture.resolver.useProvider(testOverride = null) { "unreachable" }
            throw AssertionError("expected Provider construction failure")
        } catch (_: IllegalArgumentException) {
            // 构造失败时必须先清除克隆数组再抛出异常。
        }

        assertReleased(fixture.factory)
        assertEquals(0, fixture.factory.releaseCalls)
    }

    @Test
    fun `WebDAV 解封密码在Provider构造后立即清理`() = runTest {
        val fixture = Fixture()
        fixture.saveWebDav()

        val early = fixture.resolver.useProvider(testOverride = null) { provider ->
            assertTrue(provider === fixture.factory.provider)
            assertArrayEquals(CharArray("fake-password".length) { '0' }, fixture.factory.webDavPassword!!)
            return@useProvider "early"
        }

        assertEquals("early", early)
        assertEquals(1, fixture.factory.releaseCalls)
        assertArrayEquals(CharArray("fake-password".length) { '0' }, fixture.factory.webDavPassword!!)

        try {
            fixture.resolver.useProvider(testOverride = null) { throw CancellationException("synthetic") }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // 借用的 WebDAV 密码已在回调开始前清除。
        }
        assertEquals(2, fixture.factory.releaseCalls)
        assertArrayEquals(CharArray("fake-password".length) { '0' }, fixture.factory.webDavPassword!!)
    }

    @Test
    fun `测试Provider作为借用的同接口依赖不会被生产工厂释放`() = runTest {
        val fixture = Fixture()
        val borrowed = RecordingProvider()

        val result = fixture.resolver.useProvider(testOverride = borrowed) { provider ->
            assertTrue(provider === borrowed)
            "borrowed"
        }

        assertEquals("borrowed", result)
        assertEquals(0, fixture.factory.releaseCalls)
        assertNull(fixture.factory.accessKeyClone)
    }

    private class Fixture {
        val preferences = InMemorySharedPreferences()
        val store = SyncCredentialsStore(preferences.context(), keystoreManager = null)
        val factory = RecordingFactory()
        private val debugLog = DebugLogBuffer()
        val resolver = SyncProviderResolver(
            store,
            SyncPreferences(debugLog, null),
            debugLog,
            factory
        )

        init {
            store.customEncryptor = { plaintext ->
                Pair(byteArrayOf(1, 2, 3), plaintext.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray())
            }
            store.customDecryptor = { _, cipher ->
                cipher.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
            }
        }

        fun saveS3() {
            assertTrue(
                store.saveS3Config(
                    endpoint = "https://s3.example.test",
                    bucket = "bucket",
                    region = "auto",
                    accessKey = "fake-access".toCharArray(),
                    secretKey = "fake-secret".toCharArray(),
                    objectKey = "vault/object.kdbx"
                )
            )
            store.saveProvider(CloudSyncProvider.S3_COMPATIBLE)
        }

        fun saveWebDav() {
            assertTrue(
                store.saveWebDavConfig(
                    url = "https://dav.example.test/remote.php/webdav",
                    username = "fake-user",
                    password = "fake-password".toCharArray(),
                    remotePath = "/vault.kdbx"
                )
            )
            store.saveProvider(CloudSyncProvider.WEBDAV)
        }
    }

    private class RecordingFactory : SyncProviderFactory {
        var provider: SyncProvider = RecordingProvider()
        var webDavPassword: CharArray? = null
        var accessKeyClone: CharArray? = null
        var secretKeyClone: CharArray? = null
        var releaseCalls = 0
        var createCalls = 0
        var failS3Creation = false

        override fun createWebDav(
            serverUrl: String,
            username: String,
            passwordChars: CharArray,
            networkOptions: SyncNetworkOptions,
            transferOptions: SyncTransferOptions
        ): SyncProvider {
            createCalls++
            webDavPassword = passwordChars
            provider = RecordingProvider()
            return provider
        }

        override fun createS3(
            endpoint: String,
            bucketName: String,
            region: String,
            accessKeyId: CharArray,
            secretAccessKey: CharArray,
            usePathStyle: Boolean,
            networkOptions: SyncNetworkOptions,
            initialClockOffsetMillis: Long,
            clockOffsetUpdater: (Long) -> Unit
        ): SyncProvider {
            createCalls++
            accessKeyClone = accessKeyId
            secretKeyClone = secretAccessKey
            if (failS3Creation) throw IllegalArgumentException("synthetic")
            provider = RecordingProvider()
            return provider
        }

        override fun releaseOwned(provider: SyncProvider) {
            releaseCalls++
            accessKeyClone?.fill('0')
            secretKeyClone?.fill('0')
        }
    }

    private class RecordingProvider : SyncProvider {
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.failure(UnsupportedOperationException())

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun upload(remotePath: String, data: ByteArray, expectedEtag: String?): Result<String> =
            Result.failure(UnsupportedOperationException())

        override suspend fun delete(remotePath: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }

    private fun assertReleased(factory: RecordingFactory) {
        assertAllZero(factory.accessKeyClone!!)
        assertAllZero(factory.secretKeyClone!!)
    }

    private fun assertAllZero(chars: CharArray) {
        assertTrue(chars.all { it == '0' })
    }

    private companion object {
        const val XOR_MASK = 0x5A
    }
}
