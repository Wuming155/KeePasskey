package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.database.session.DatabaseSession
import com.keepasskey.sync.engine.SyncCache
import com.keepasskey.sync.engine.SyncEngine
import com.keepasskey.sync.model.RemoteFileMetadata
import com.keepasskey.sync.model.SyncException
import com.keepasskey.sync.provider.CredentialReleasingProvider
import com.keepasskey.sync.provider.SyncProvider
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Provider 凭据的**持有 / 释放周期**（ENG-20261028-42）。
 *
 * 锁定的行为契约：Provider 持有可擦口令副本，认证头按请求现算；代表「确定性失效」的
 * 生命周期事件必须真正切断后续认证，而不是只清掉一个引用。覆盖四条路径：
 * 1. 会话终止（`SyncSessionState.clear`，锁定 / 关库 / 换库）——释放且不影响其余语义；
 * 2. 新周期替换引擎（`replaceSyncEngine`）——旧引擎无人留存即释放，被待决冲突留存则保留；
 * 3. 未实现可选能力的 Provider——注入释放器时仍释放（S3 形态）；两者都没有时无操作；
 * 4. 借用来的测试 Provider——任何路径都不释放（所有权不属本会话）。
 *
 * 用真实的 [SyncEngine] + 假 Provider 驱动，断言的是「口令数组被清零且后续取头失败」
 * 这一可观察结果，而非调用次数。
 */
class SyncSessionCredentialReleaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `会话终止释放凭据且后续取头失败`() {
        val provider = ReleasableFakeProvider("fake-pass-session".toCharArray())
        val state = SyncSessionState()
        state.replaceSyncEngine(engineFor(provider))

        assertEquals("fake-pass-session", provider.currentCredentials())

        state.clear()

        assertNull("会话终止后不再持有引擎", state.lastSyncEngine)
        assertNull("会话终止后不再持有口令副本", provider.retainedPassword())
        assertThrows(
            "失效后取头必须 fail-closed 而不是以空口令降级",
            SyncException.AuthenticationError::class.java
        ) { provider.currentCredentials() }
    }

    @Test
    fun `会话终止的其余清理语义不变`() {
        val provider = ReleasableFakeProvider("fake-pass-cleared".toCharArray())
        val state = SyncSessionState()
        state.replaceSyncEngine(engineFor(provider))
        state.lastRemoteEtag = "etag-a"
        state.probeBaselineTargetIdentity = "identity-a"

        state.clear()

        assertEquals("ETag 基线随会话清空", "", state.lastRemoteEtag)
        assertNull("探测目标身份随会话清空", state.probeBaselineTargetIdentity)
        assertNull("缓存视图随会话清空", state.lastSyncCache)
    }

    @Test
    fun `新周期替换引擎时释放不再被留存的旧引擎凭据`() {
        val first = ReleasableFakeProvider("fake-pass-first".toCharArray())
        val second = ReleasableFakeProvider("fake-pass-second".toCharArray())
        val state = SyncSessionState()
        state.replaceSyncEngine(engineFor(first))

        state.replaceSyncEngine(engineFor(second), isRetained = { false })

        assertNull("旧引擎不再被留存时必须释放其口令副本", first.retainedPassword())
        assertEquals("新引擎成为会话锚点", "fake-pass-second", second.currentCredentials())
    }

    @Test
    fun `被待决冲突留存的旧引擎凭据保留到留存方收口`() {
        val first = ReleasableFakeProvider("fake-pass-retained".toCharArray())
        val second = ReleasableFakeProvider("fake-pass-next".toCharArray())
        val state = SyncSessionState()
        val retained = engineFor(first)
        state.replaceSyncEngine(retained)

        state.replaceSyncEngine(engineFor(second), isRetained = { it === retained })

        assertEquals(
            "被留存的引擎仍会被冲突裁决复用，凭据不得提前失效",
            "fake-pass-retained",
            first.currentCredentials()
        )

        // 留存方（冲突会话）丢弃该引擎后显式收口，凭据才失效
        retained.releaseProviderCredentials()
        assertNull("留存方收口后凭据确定性清零", first.retainedPassword())
    }

    @Test
    fun `未实现可选能力的Provider经注入释放器仍被会话终止释放`() {
        // ENG-20261028-42：S3 这类尚未迁移到 CredentialReleasingProvider 的协议，生产由
        // SyncEngine 注入的工厂释放器统一收口——会话层凭据失效必须对它同样生效。
        val state = SyncSessionState()
        var released = 0
        state.replaceSyncEngine(
            engineForWithReleaser(PlainFakeProvider()) { released++ }
        )
        state.lastRemoteEtag = "etag-plain"

        state.clear()

        assertEquals("注入释放器必须在会话终止时被调用一次", 1, released)
        assertNull("引擎引用照常释放", state.lastSyncEngine)
        assertEquals("其余会话语义不变", "", state.lastRemoteEtag)
    }

    @Test
    fun `未实现可选能力且未注入释放器时无操作`() {
        // provider 无关性：无注入释放器且 Provider 无该能力 ⇒ 会话层无操作、不抛异常
        val state = SyncSessionState()
        state.replaceSyncEngine(engineFor(PlainFakeProvider()))
        state.lastRemoteEtag = "etag-plain"

        state.clear()

        assertNull("引擎引用照常释放", state.lastSyncEngine)
        assertEquals("其余会话语义不变", "", state.lastRemoteEtag)
    }

    @Test
    fun `借用来的测试Provider不被会话终止释放`() {
        val borrowed = ReleasableFakeProvider("fake-pass-borrowed".toCharArray())
        val state = SyncSessionState()
        state.testSyncProvider = borrowed
        state.replaceSyncEngine(engineFor(borrowed))

        state.clear()

        assertEquals(
            "测试注入的 Provider 归测试所有，借用的会话不得释放它",
            "fake-pass-borrowed",
            borrowed.currentCredentials()
        )
    }

    // ---------------- 待决冲突会话（第二个持有者）

    @Test
    fun `待决冲突会话丢弃时释放该引擎凭据`() {
        // 冲突待决期的引擎会被用户稍后裁决复用：周期作用域退出时不释放（见 resolver 的留存判定），
        // 由本例的丢弃点收口。若不释放，口令副本会一直驻留到会话结束。
        val provider = ReleasableFakeProvider("fake-pass-pending".toCharArray())
        val engine = engineFor(provider)
        val controller = conflictControllerFor(SyncSessionState())
        controller.pendingRemoteEngine = engine

        assertTrue("前提：待决引擎确实被该会话留存", controller.retainsProvider(provider))

        controller.clearPendingConflictSession()

        assertNull("丢弃待决会话后不再持有引擎", controller.pendingRemoteEngine)
        assertNull("丢弃待决会话必须确定性清零口令副本", provider.retainedPassword())
    }

    @Test
    fun `裁决重入丢弃待决会话时保留仍要继续使用的引擎凭据`() {
        // 裁决上传 412 的重入路径：待决**会话**被丢弃，但同一引擎紧接着要继续上传。
        // 提前失效会让紧随其后的重入上传以鉴权失败告终（回归护栏）。
        val provider = ReleasableFakeProvider("fake-pass-reentry".toCharArray())
        val engine = engineFor(provider)
        val controller = conflictControllerFor(SyncSessionState())
        controller.pendingRemoteEngine = engine

        controller.clearPendingConflictSession(keepEngine = engine)

        assertEquals(
            "重入路径必须保留引擎凭据",
            "fake-pass-reentry",
            provider.currentCredentials()
        )
    }

    @Test
    fun `配置撤销释放待决引擎凭据但保留冲突清单`() {
        // 配置撤销（退出同步 / 换服务器）后，待决冲突列表可以继续展示，但旧口令不得再可用。
        val provider = ReleasableFakeProvider("fake-pass-revoked".toCharArray())
        val engine = engineFor(provider)
        val controller = conflictControllerFor(SyncSessionState())
        controller.pendingRemoteEngine = engine

        controller.releasePendingEngineCredentials()

        assertNull("撤销后旧口令副本必须清零", provider.retainedPassword())
        assertTrue("待决引擎引用仍在（冲突清单未被丢弃）", controller.pendingRemoteEngine === engine)
    }

    private fun engineFor(provider: SyncProvider): SyncEngine =
        SyncEngine(
            provider,
            SyncCache(File(tempFolder.root, "cache-${engineSequence.incrementAndGet()}").apply { mkdirs() })
        )

    /** 生产形态：引擎带「协议无关释放器」（`SyncProviderFactory.releaseOwned` 的等价物）。 */
    private fun engineForWithReleaser(
        provider: SyncProvider,
        releaser: (SyncProvider) -> Unit
    ): SyncEngine =
        SyncEngine(
            provider,
            SyncCache(File(tempFolder.root, "cache-${engineSequence.incrementAndGet()}").apply { mkdirs() }),
            credentialReleaser = releaser
        )

    private fun conflictControllerFor(state: SyncSessionState): SyncConflictController {
        val databaseSession = DatabaseSession()
        return SyncConflictController(
            databaseSession = databaseSession,
            codec = SyncDatabaseCodec(databaseSession, DebugLogBuffer()),
            strings = StringsProvider { _, _ -> "" },
            session = state,
            debugLog = DebugLogBuffer()
        )
    }

    private val engineSequence = java.util.concurrent.atomic.AtomicInteger()

    /** 持有可擦口令副本、按请求现算认证头的假 Provider（口径同 WebDavAuthCredentials）。 */
    private class ReleasableFakeProvider(passwordChars: CharArray) : SyncProvider, CredentialReleasingProvider {
        private var password: CharArray? = passwordChars.copyOf()

        /** 刻意返回**同一数组**（不复制）：测试要观察它是否被确定性清零。 */
        fun retainedPassword(): CharArray? = password

        /** 取头：已失效即抛类型化鉴权失败（fail-closed，不以空口令降级）。 */
        fun currentCredentials(): String {
            val owned = password ?: throw SyncException.AuthenticationError("凭据已失效")
            return owned.concatToString()
        }

        override fun releaseCredentials() {
            password?.fill('0')
            password = null
        }

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.failure(SyncException.FileNotFound(remotePath))

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(SyncException.FileNotFound(remotePath))

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> = Result.success("etag")

        override suspend fun delete(remotePath: String): Result<Unit> = Result.success(Unit)
    }

    /** 未实现 [CredentialReleasingProvider] 的 Provider（会话层必须对它无操作）。 */
    private class PlainFakeProvider : SyncProvider {
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override suspend fun getMetadata(remotePath: String): Result<RemoteFileMetadata> =
            Result.failure(SyncException.FileNotFound(remotePath))

        override suspend fun download(remotePath: String, sink: OutputStream): Result<Unit> =
            Result.failure(SyncException.FileNotFound(remotePath))

        override suspend fun upload(
            remotePath: String,
            data: ByteArray,
            expectedEtag: String?
        ): Result<String> = Result.success("etag")

        override suspend fun delete(remotePath: String): Result<Unit> = Result.success(Unit)
    }
}
