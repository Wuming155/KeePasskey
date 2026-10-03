package com.keepasskey.app.sync

import android.content.Context
import android.content.SharedPreferences
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncRollbackGuard
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
 *
 * F-23 例外：防回滚高水位状态（`.rollback`）**不属**可丢弃缓存——它已迁至
 * `filesDir/<SyncRollbackGuard.STATE_DIR_NAME>`，且缓存销毁器不得删除该后缀的文件
 * （见 `F23 锁库清理缓存不得删除防回滚状态文件`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCacheEvictorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // 注意：此处不可命名为 cacheDir——Kotlin 会把 Context.getCacheDir() 合成为同名属性，
    // 对象表达式内的 `cacheDir` 会优先解析为自身合成属性，导致 getter 无限递归
    private lateinit var appCacheDir: File
    private lateinit var fakeContext: Context
    private lateinit var debugLog: DebugLogBuffer
    private lateinit var evictor: SyncCacheEvictor
    private lateinit var session: DatabaseSession

    /**
     * 目录内（**含任意层级子目录**）**真实存在**的文件（ISSUE-P3-142 + ISSUE-P3-463）。
     *
     * `File.listFiles()` 返回的是**目录索引条目**，与「磁盘上真有一个文件」并不等价：
     * 在 Windows/NTFS 上实测（见 `docs/records/SyncCache大写CACHE临时文件定位记录.md`）
     * 偶发返回**大写拼写、磁盘上并不存在**的「鬼影条目」（本仓实测过的形态：
     * `<HASH>.VERSION.tmp`——大写、**无 UUID**，任何代码路径都产不出该名）。
     * 仅以 `isFile` 判据时该鬼影仍可能被算成残留（约 3% 假阳性，见限界 §7），
     * 使「锁定后不得残留密文快照」这类断言偶发假阳性（ISSUE-P3-463 即此形态）。
     *
     * 本助手在实体判据之上再叠加**同路径短时有界重枚举**复核（15 ms × 4，限界 §7 实测
     * 在 380 例假阳性上 **0 命中**；§7 明书「刻意未采纳…**可按需启用**」，本条即其启用点）：
     * 只有**每一轮重枚举都仍判 isFile** 的名字才计为残留。
     * **不放宽判定**——磁盘上真存在的残留恒通过（它不会在 60 ms 内自行消失），
     * 被剔除的只是「索引说有、复枚举看不到」的宿主鬼影。
     *
     * **刻意保留递归**（`walkTopDown()`）：本用例原判据即为递归遍历，改为只看直接子项会**收窄覆盖**——
     * 缓存目录一旦出现嵌套子目录，其中的残留将不再被发现。故此处只把「索引条目」换成「实体存在 + 复枚举」
     * 这一层，**不动遍历深度**。口径与 `sync` 模块 `SyncCacheTest` 的实体判据同源（同一记录文档）。
     */
    private fun realFilesUnder(dir: File): List<File> {
        val pending = dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path }
            .toMutableSet()
        if (pending.isEmpty()) return emptyList()
        repeat(GHOST_RECHECK_ROUNDS) {
            Thread.sleep(GHOST_RECHECK_GAP_MS)
            val present = dir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(dir).path }
                .toSet()
            pending.retainAll(present)
            if (pending.isEmpty()) return emptyList()
        }
        return pending.map { File(dir, it) }
    }

    /** 目录索引条目名（含鬼影），仅用于失败信息——便于事后区分「鬼影」与「真残留」。 */
    private fun listedEntries(dir: File): List<String> =
        dir.listFiles().orEmpty().map { it.name }

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

        debugLog = DebugLogBuffer()
        evictor = SyncCacheEvictor(fakeContext, debugLog)
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
            "锁定后不得残留任何密文快照（索引条目=${listedEntries(syncDir)}）",
            emptyList<File>(),
            realFilesUnder(syncDir)
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
            realFilesUnder(syncDir)
        )
        assertEquals(DatabaseSession.SessionState.CLOSED, session.state.value)
    }

    @Test
    fun `F23 锁库清理缓存不得删除防回滚状态文件`() = runTest {
        // F-23 回归锁（真实锁库路径）：防回滚状态现已迁至 filesDir，缓存销毁器只清 cacheDir/sync
        // 的 KDBX 密文快照；即便目录内存在升级前遗留 / 误配的 `.rollback` 状态文件，
        // 锁库清理也必须保留它——整改前 SyncCache.clearAll() 会连同它一起删除，
        // 导致「用户锁定一次即状态归零」，云侧随即可以重放旧库。
        openVault()
        val syncDir = seedCache()
        val stateFile = File(
            syncDir,
            SyncCache.sha256Hex("/remote/vault.kdbx".toByteArray(Charsets.UTF_8)) +
                SyncRollbackGuard.SUFFIX_STATE
        ).apply { writeBytes("current=deadbeef\n".toByteArray(Charsets.UTF_8)) }

        session.addLockObserver(evictor)
        session.lock()

        assertTrue("防回滚状态必须跨锁定保留: ${stateFile.name}", stateFile.isFile)
        assertEquals(
            "除防回滚状态外不得残留密文快照（索引条目=${listedEntries(syncDir)}）",
            listOf(stateFile.name),
            realFilesUnder(syncDir).map { it.name }.sorted()
        )
        // 清点必须把防回滚状态排除（否则每次锁库都会误报「密文可能仍可恢复」）。
        // 本用例曾在全量跑中**偶发红**（观测 3 次全量 / 1 次失败，独立复跑 4 次全绿）。
        // 由于 `SyncCacheEvictor.evictAll()` 的 false 只可能来自「删除失败」或
        // 「`runCatching` 吞掉了 Throwable」，失败信息必须同时带上**销毁器自己的日志**
        // （它会记录被吞异常的类名）与**清理后的实际目录内容**，否则下次偶发红无法定位。
        val cleared = evictor.evictAll()
        assertTrue(
            "残留计数必须排除防回滚状态（它不是密文快照）；" +
                "清理后目录索引条目=${listedEntries(syncDir)}；" +
                "磁盘上真实存在的文件=${realFilesUnder(syncDir).map { it.name }}；" +
                "销毁器日志=${debugLog.snapshot()}",
            cleared
        )
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

    private companion object {
        /** ISSUE-P3-463：宿主鬼影的短时有界重枚举轮数与间隔（判据见 [realFilesUnder]，源自限界 §7） */
        const val GHOST_RECHECK_ROUNDS = 4
        const val GHOST_RECHECK_GAP_MS = 15L
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
