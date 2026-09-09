package com.keepasskey.app.sync

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

/**
 * ISSUE-P1-07 端到端验收：锁库 / 关闭 / 同步凭据销毁后，`cacheDir/sync` 下不得残留
 * 任何 KDBX 密文快照（`.cache` 工作副本与 `.basecache` 三方合并基准）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCacheEvictorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // 注意：此处不可命名为 cacheDir——Kotlin 会把 Context.getCacheDir() 合成为同名属性，
    // 对象表达式内的 `cacheDir` 会优先解析为自身合成属性，导致 getter 无限递归
    private lateinit var appCacheDir: File
    private lateinit var fakeContext: Context
    private lateinit var evictor: SyncCacheEvictor
    private lateinit var session: DatabaseSession

    @Before
    fun setUp() {
        appCacheDir = File(tempFolder.root, "cache").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "files").apply { mkdirs() }

        fakeContext = object : android.content.ContextWrapper(null) {
            override fun getCacheDir(): File = appCacheDir
            override fun getFilesDir(): File = filesDir
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs()
        }

        evictor = SyncCacheEvictor(fakeContext, DebugLogBuffer())
        session = DatabaseSession()
    }

    /** 模拟一次同步周期后的落盘产物：两份完整 KDBX 密文 + 版本/元数据 */
    private fun seedCache(): File {
        val syncDir = File(appCacheDir, SyncCache.CACHE_DIR_NAME)
        val cache = SyncCache(syncDir)
        cache.writeCache("/remote/vault.kdbx", ByteArray(2048) { it.toByte() })
        cache.writeBaseContent("/remote/vault.kdbx", ByteArray(2048) { (it + 7).toByte() })
        cache.updateBase("/remote/vault.kdbx", baseVersion = "seed", etag = "\"etag-seed\"")
        assertTrue("预置缓存必须非空", syncDir.listFiles()!!.isNotEmpty())
        return syncDir
    }

    private suspend fun openVault() {
        val result = session.create(
            file = File(fakeContext.filesDir, "evictor_vault.kdbx"),
            name = "EvictorVault",
            passwordChars = "EvictorMaster#2026".toCharArray(),
            useArgon2 = false
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `锁库后同步缓存目录为空`() = runTest {
        openVault()
        val syncDir = seedCache()
        session.addLockObserver(evictor)

        session.lock()

        assertEquals(
            "锁定后不得残留任何密文快照: ${syncDir.listFiles()?.toList()}",
            emptyList<File>(),
            syncDir.walkTopDown().filter { it.isFile }.toList()
        )
        assertEquals(DatabaseSession.SessionState.LOCKED, session.state.value)
    }

    @Test
    fun `关闭密码库后同步缓存目录为空`() = runTest {
        openVault()
        val syncDir = seedCache()
        session.addLockObserver(evictor)

        session.close()

        assertEquals(
            emptyList<File>(),
            syncDir.walkTopDown().filter { it.isFile }.toList()
        )
        assertEquals(DatabaseSession.SessionState.CLOSED, session.state.value)
    }

    @Test
    fun `未注册观察者时锁库不清理缓存（注册是显式契约）`() = runTest {
        openVault()
        val syncDir = seedCache()

        session.lock()

        assertTrue("未注册观察者时缓存保持原样", syncDir.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `同步凭据清空时连带销毁同步缓存`() {
        val syncDir = seedCache()
        val store = SyncCredentialsStore(fakeContext, null, DebugLogBuffer(), evictor)

        store.clear()

        assertEquals(
            emptyList<File>(),
            syncDir.walkTopDown().filter { it.isFile }.toList()
        )
    }

    @Test
    fun `缓存目录不存在时清理幂等返回成功`() {
        assertTrue(evictor.evictAll())
        assertTrue("重复清理必须幂等", evictor.evictAll())
    }

    /** 最小 SharedPreferences 桩：仅支撑 clear()/getString 路径，不引入 Robolectric */
    private fun fakePrefs(): SharedPreferences {
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "apply", "commit" -> null
                else -> proxy
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> args?.get(1)
                "getBoolean" -> args?.get(1) as? Boolean ?: false
                "getLong" -> args?.get(1) as? Long ?: 0L
                "contains" -> false
                else -> null
            }
        } as SharedPreferences
    }
}
