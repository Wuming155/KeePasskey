package com.keepasskey.app.ui.screens.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.keepasskey.app.R
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.ExtendedSettingsStore
import com.keepasskey.app.sync.SyncCoordinator
import com.keepasskey.app.sync.SyncCredentialsStore
import com.keepasskey.app.testutil.InMemorySharedPreferences
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.screens.settings.subscreens.persistThenContinue
import com.keepasskey.app.ui.screens.settings.subscreens.withSyncCredentialSubmissionCopy
import com.keepasskey.database.session.DatabaseSession
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-651`：保存失败后「输入显示」与「已擦除凭据副本」必须一致——修正端点即可重试，
 * 且**不需要重新键入秘密**。
 *
 * ## 本类证明什么（与既有用例的分工）
 *
 * - `CloudSyncSaveFlowTest` 只测两个纯函数（副本所有权 / 短路守卫），不碰真实存储；
 * - `WebDavPreservePasswordOnEmptySaveTest` 只测「空密码保留既有口令」；
 * - 本类把**真实** [SettingsSyncController] + **真实** [SyncCredentialsStore]（内存偏好 + 可逆
 *   测试封印）串起来，按用户实际动作序列驱动：
 *   表单草稿 → `copyOf` 提交副本 → 控制器（HTTPS 校验 / 封印 / 落盘）→ 表单草稿的命运。
 *
 * 关键判据是**草稿与存储的双向一致**：
 * 1. 失败时：提交副本被擦除（不残留第二份明文）、存储**未被半写**、表单草稿仍可重试；
 * 2. 重试时：同一份表单草稿（不重新键入）能保存成功，且落盘凭据与草稿逐字相等；
 * 3. 成功时：草稿被清空、一次性预填通道失效（成功/离页清理语义）。
 *
 * ## 边界（如实声明）
 *
 * 本类不驱动 Compose（`SecurePasswordField` 的显示态与 `DisposableEffect` 离页清理属于 UI 层，
 * 由设备用例覆盖）；也不发任何网络请求——「保存失败零请求」由 `SyncSavedConfigFailureAndCancelTest`
 * （编排层计数）与设备用例（真实 HTTPS 接收方计数）分别覆盖。
 */
class CloudSyncSaveRetryContractTest {

    @Test
    fun `HTTP被拒后只改HTTPS即可用同一份草稿重试且落盘值逐字相等`() = runTest {
        val fixture = Fixture()
        try {
            val draft = "fake-webdav-password".toCharArray()
            val draftSnapshot = draft.copyOf()

            // ① 端点写成 http://：保存必须被拒，且不触达存储
            assertFalse("显式 http:// 必须拒绝保存", fixture.submitWebDav(draft, "http://dav.example.test/sync"))
            assertEquals(
                "拒绝原因必须是「端点必须 HTTPS」",
                R.string.sync_error_https_required,
                fixture.controller.state.value.syncFeedbackMessage?.resId
            )
            assertArrayEquals("失败后表单草稿必须原样保留（用户不必重输）", draftSnapshot, draft)
            assertNull("拒绝保存不得落盘任何 WebDAV 配置", fixture.credentials.loadWebDavConfig())
            assertFalse("控制器不得把拒绝当成已验证", fixture.controller.state.value.isConnectionVerified)

            // ② 只把端点改成 https://，秘密字段原样重交（模拟用户只改了一处输入）
            assertTrue(
                "修正端点后必须能用同一份草稿保存成功",
                fixture.submitWebDav(draft, "https://dav.example.test/sync")
            )
            assertTrue("保存成功后表单草稿必须就地擦除", draft.all { it == '0' })

            val saved = requireNotNull(fixture.credentials.loadWebDavConfig()) {
                "保存成功后存储必须可读回"
            }
            try {
                assertEquals("端点已归一化为 https", "https://dav.example.test/sync", saved.url)
                assertEquals("用户名原样落盘", "fake-user", saved.username)
                assertEquals("远程路径原样落盘", "/saved.kdbx", saved.remotePath)
                assertArrayEquals(
                    "落盘口令必须与用户输入逐字相等（未重输也未变形）",
                    draftSnapshot,
                    saved.password
                )
            } finally {
                saved.password.fill('0')
            }

            // ③ 成功即清空本地草稿，并作废一次性预填通道（成功清理语义）
            assertTrue("保存成功后表单草稿必须就地擦除", draft.all { it == '0' })
            assertNull("保存成功后旧预填通道必须失效", fixture.controller.webdavPasswordPrefill.value)
            // 成功提示由页面组件（CloudSyncScreen 的 saveFeedbackMessage）呈现；控制器侧
            // 不得残留上一次失败提示，否则「已保存」与「保存失败」会同时可见。
            val residue = fixture.controller.state.value.syncFeedbackMessage?.resId
            assertTrue(
                "保存成功后控制器不得残留失败反馈（实际 resId=$residue）",
                residue == null || residue != R.string.sync_error_https_required &&
                    residue != R.string.sync_config_save_failed
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `封印失败不半写且草稿保留随后可重试成功`() = runTest {
        val fixture = Fixture()
        try {
            // 封印失败的真实形态：Keystore 密钥被作废 ⇒ 加密调用抛异常 ⇒ sealer 返回 null
            fixture.credentials.customEncryptor = { error("keystore key invalidated") }
            val draft = "fake-webdav-password".toCharArray()
            val draftSnapshot = draft.copyOf()

            assertFalse(
                "封印失败必须如实回传失败",
                fixture.submitWebDav(draft, "https://dav.example.test/sync")
            )
            assertEquals(
                "反馈必须是「保存失败：封印未成功」",
                R.string.sync_config_save_failed,
                fixture.controller.state.value.syncFeedbackMessage?.resId
            )
            assertArrayEquals("封印失败后草稿必须保留", draftSnapshot, draft)
            assertNull(
                "封印失败不得留下「URL 已写、凭据未写」的半写状态",
                fixture.credentials.loadWebDavConfig()
            )

            // 封印恢复后同一份草稿重试必须成功（不要求重输）
            fixture.credentials.customEncryptor = Fixture.reversibleEncryptor
            assertTrue(
                "封印恢复后必须能重试成功",
                fixture.submitWebDav(draft, "https://dav.example.test/sync")
            )
            val saved = requireNotNull(fixture.credentials.loadWebDavConfig())
            try {
                assertArrayEquals("重试成功后落盘口令与草稿一致", draftSnapshot, saved.password)
            } finally {
                saved.password.fill('0')
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `全零草稿被拒绝且不覆盖既有可用凭据`() = runTest {
        val fixture = Fixture()
        try {
            // 预置一份可用配置（模拟用户此前已保存成功）
            assertTrue(
                fixture.submitWebDav("fake-saved-password".toCharArray(), "https://dav.example.test/sync")
            )

            // 擦除指纹（全 '0'）再次提交：ISSUE-P2-403 语义，必须拒绝且不动既有密文
            val zeroed = "0000000000000000".toCharArray()
            assertFalse(
                "全零串必须拒绝封印",
                fixture.submitWebDav(zeroed, "https://dav.example.test/other")
            )
            assertArrayEquals("拒绝后草稿保持全零（由用户决定重输）", "0000000000000000".toCharArray(), zeroed)
            val stillSaved = requireNotNull(fixture.credentials.loadWebDavConfig())
            try {
                assertEquals("既有端点不得被全零提交改写", "https://dav.example.test/sync", stillSaved.url)
                assertArrayEquals(
                    "既有可用口令不得被全零提交顶掉",
                    "fake-saved-password".toCharArray(),
                    stillSaved.password
                )
            } finally {
                stillSaved.password.fill('0')
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `S3三类秘密在HTTP拒绝封印失败与全零时都不半写`() = runTest {
        val fixture = Fixture()
        try {
            fixture.controller.setSyncProvider(CloudSyncProvider.S3_COMPATIBLE)
            val access = "fake-access-key".toCharArray()
            val secret = "fake-secret-key".toCharArray()

            // ① HTTP 拒绝：AccessKey 与 SecretKey 都不得落盘
            assertFalse(
                "S3 端点 http:// 必须拒绝保存",
                fixture.submitS3(access, secret, endpoint = "http://s3.example.test")
            )
            assertEquals(
                R.string.sync_error_https_required,
                fixture.controller.state.value.syncFeedbackMessage?.resId
            )
            assertNull("HTTP 拒绝后不得存在 S3 配置", fixture.credentials.loadS3Config())
            assertArrayEquals("拒绝后 AccessKey 草稿保留", "fake-access-key".toCharArray(), access)
            assertArrayEquals("拒绝后 SecretKey 草稿保留", "fake-secret-key".toCharArray(), secret)

            // ② 封印失败：任一项失败即整体不写（不出现「AccessKey 已写、SecretKey 未写」）
            fixture.credentials.customEncryptor = { error("keystore key invalidated") }
            assertFalse(
                "封印失败必须如实回传失败",
                fixture.submitS3(access, secret, endpoint = "https://s3.example.test")
            )
            assertNull("封印失败后不得存在 S3 配置", fixture.credentials.loadS3Config())
            fixture.credentials.customEncryptor = Fixture.reversibleEncryptor

            // ③ 正常保存后落盘值逐字相等
            assertTrue(
                "封印恢复后必须保存成功",
                fixture.submitS3(access, secret, endpoint = "https://s3.example.test")
            )
            val saved = requireNotNull(fixture.credentials.loadS3Config())
            try {
                assertEquals("端点落盘", "https://s3.example.test", saved.endpoint)
                assertEquals("桶名落盘", "fake-bucket", saved.bucket)
                assertArrayEquals("AccessKey 落盘逐字相等", "fake-access-key".toCharArray(), saved.accessKey)
                assertArrayEquals("SecretKey 落盘逐字相等", "fake-secret-key".toCharArray(), saved.secretKey)
            } finally {
                saved.accessKey.fill('0')
                saved.secretKey.fill('0')
            }

            // ④ 全零 AccessKey（擦除指纹）不得顶掉既有密文
            val zeroAccess = "0000000000".toCharArray()
            val zeroSecret = "0000000000".toCharArray()
            assertFalse(
                "全零 AccessKey 必须拒绝封印",
                fixture.submitS3(zeroAccess, zeroSecret, endpoint = "https://s3.example.test/other")
            )
            val stillSaved = requireNotNull(fixture.credentials.loadS3Config())
            try {
                assertEquals("既有端点不得被全零提交改写", "https://s3.example.test", stillSaved.endpoint)
                assertArrayEquals(
                    "既有 AccessKey 不得被顶掉",
                    "fake-access-key".toCharArray(),
                    stillSaved.accessKey
                )
            } finally {
                stillSaved.accessKey.fill('0')
                stillSaved.secretKey.fill('0')
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `保存失败不触发任何后续联网动作`() = runTest {
        var networkActions = 0

        // 表单两处入口（「保存并同步」与「测试连接」）共用同一守卫
        assertFalse(
            "保存失败时不得继续",
            persistThenContinue(persist = { false }, continueAfterPersist = { networkActions++ })
        )
        assertEquals("保存失败必须零联网动作", 0, networkActions)

        assertTrue(
            "保存成功时必须继续",
            persistThenContinue(persist = { true }, continueAfterPersist = { networkActions++ })
        )
        assertEquals("保存成功恰好一次后续动作", 1, networkActions)
    }

    // ============================== 夹具 ==============================

    private class Fixture : AutoCloseable {
        private val prefs = InMemorySharedPreferences()
        private val filesDir: File = Files.createTempDirectory("p2-651-files").toFile()
        private val context: Context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs.prefs
            override fun getFilesDir(): File = filesDir
            override fun getCacheDir(): File = filesDir
            override fun getApplicationContext(): Context = this
        }
        private val session = DatabaseSession()
        val credentials = SyncCredentialsStore(context, keystoreManager = null).apply {
            customEncryptor = reversibleEncryptor
            customDecryptor = reversibleDecryptor
        }
        private val coordinator = SyncCoordinator(
            context = context,
            databaseSession = session,
            syncCredentialsStore = credentials,
            debugLog = DebugLogBuffer(),
            strings = StringsProvider { id, _ -> "s$id" }
        )
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        val controller = SettingsSyncController(
            credentials,
            coordinator,
            ExtendedSettingsStore(null),
            StringsProvider { id, _ -> "s$id" },
            scope
        )

        init {
            controller.setSyncProvider(CloudSyncProvider.WEBDAV)
        }

        /** 按生产表单语义提交：草稿 → 独立副本 → 控制器；成功则清空草稿（与 `CloudSyncScreen` 同序）。 */
        fun submitWebDav(draft: CharArray, url: String): Boolean {
            val saved = withSyncCredentialSubmissionCopy(draft) { submission ->
                controller.updateWebDavConfig(url, "fake-user", submission, "/saved.kdbx")
            }
            if (saved) draft.fill('0')
            return saved
        }

        fun submitS3(access: CharArray, secret: CharArray, endpoint: String): Boolean {
            val saved = withSyncCredentialSubmissionCopy(access) { accessCopy ->
                withSyncCredentialSubmissionCopy(secret) { secretCopy ->
                    controller.updateS3Config(
                        endpoint, "fake-bucket", "us-east-1",
                        accessCopy, secretCopy, "fake-object.kdbx", usePathStyle = true
                    )
                }
            }
            if (saved) {
                access.fill('0')
                secret.fill('0')
            }
            return saved
        }

        override fun close() {
            scope.cancel()
            runBlocking { session.close() }
            filesDir.deleteRecursively()
        }

        companion object {
            const val XOR_MASK = 0x5A
            private val TEST_IV = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
            val reversibleEncryptor: (ByteArray) -> Pair<ByteArray, ByteArray> = { plaintext ->
                Pair(TEST_IV, plaintext.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray())
            }
            val reversibleDecryptor: (ByteArray, ByteArray) -> ByteArray = { _, cipher ->
                cipher.map { (it.toInt() xor XOR_MASK).toByte() }.toByteArray()
            }
        }
    }
}
