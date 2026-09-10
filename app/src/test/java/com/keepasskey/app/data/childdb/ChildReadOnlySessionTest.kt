package com.keepasskey.app.data.childdb

import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * 只读子库会话单测（设计要点 2 / 3 的核心证据）。
 *
 * 语料由生产写入管线 [com.keepasskey.database.file.KdbxFile.save] 现场加密，
 * 本用例断言的是**真实解密链路的产物**：数据库名、分组数、条目数、分组路径、
 * 用户名 / URL / 备注 / 标签、以及「是否有密码」布尔事实。
 * 同时覆盖错误密码、来源不可读、损坏文件、无凭据不试探、终止清零、
 * 以及「解密途中被终止 → 结果作废」的世代竞态。
 */
class ChildReadOnlySessionTest {

    private val credentials = ChildDatabaseCredentialStore()

    private val password = "child-master-pw".toCharArray()

    private fun realVaultSource(): FakeChildDatabaseStreamSource =
        FakeChildDatabaseStreamSource(payload = ChildDatabaseFixtures.kdbxBytes(password))

    private fun session(
        source: ChildDatabaseStreamSource,
        mount: ChildDatabaseMount = testMount()
    ): ChildReadOnlySession = ChildReadOnlySession(mount, source, credentials)

    private fun reasonOf(result: KdbxResult<*>): ChildDatabaseFailureReason =
        ChildDatabaseFailureReason.of((result as KdbxResult.Failure).error)

    @Test
    fun `以真实凭据解密并投影出全部条目`() = runTest {
        val mount = testMount(alias = "工作子库")
        val session = session(realVaultSource(), mount)

        val result = session.open(password, null)

        assertTrue("真实 KDBX 必须能解密成功", result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals(ChildDatabaseFixtures.DATABASE_NAME, snapshot.databaseName)
        assertEquals(mount.id, snapshot.mountId)
        assertEquals("工作子库", snapshot.mountAlias)
        assertEquals(ChildDatabaseFixtures.GROUP_COUNT, snapshot.groupCount)
        assertEquals(ChildDatabaseFixtures.ENTRY_COUNT, snapshot.entries.size)
        assertTrue(snapshot.openedAtEpochMillis > 0L)

        val rootEntry = snapshot.entries.first { it.title == ChildDatabaseFixtures.ROOT_ENTRY_TITLE }
        assertEquals("根分组下的直接条目路径为空串", "", rootEntry.groupPath)
        assertEquals("user-${ChildDatabaseFixtures.ROOT_ENTRY_TITLE}", rootEntry.username)
        assertEquals("https://example.com/${ChildDatabaseFixtures.ROOT_ENTRY_TITLE}", rootEntry.url)
        assertTrue(rootEntry.hasPassword)
        assertTrue(rootEntry.tags.contains(ChildDatabaseFixtures.ENTRY_TAG))

        val subEntry = snapshot.entries.first { it.title == ChildDatabaseFixtures.SUB_ENTRY_TITLE }
        assertEquals(ChildDatabaseFixtures.SUB_GROUP_NAME, subEntry.groupPath)
        assertTrue(subEntry.hasPassword)

        val noPasswordEntry = snapshot.entries
            .first { it.title == ChildDatabaseFixtures.ROOT_ENTRY_WITHOUT_PASSWORD_TITLE }
        assertFalse("无密码条目必须如实标记为 false", noPasswordEntry.hasPassword)

        assertNoPasswordLeak(snapshot)
        assertEquals(mount.id, session.mountId)
        assertTrue("凭据在会话存续期内可用", credentials.hasCredentials(mount.credentialRefId))
        assertEquals(ChildDatabaseMountState.Opened(snapshot), session.state.value)
    }

    @Test
    fun `密码错误时判为凭据被拒且错误凭据被即时清零`() = runTest {
        val mount = testMount()
        val session = session(realVaultSource(), mount)

        val result = session.open("wrong-pw".toCharArray(), null)

        assertTrue(result.isFailure)
        assertEquals(ChildDatabaseFailureReason.CREDENTIAL_REJECTED, reasonOf(result))
        assertEquals(ChildDatabaseMountState.CredentialRejected, session.state.value)
        assertFalse("错误凭据绝不保留", credentials.hasCredentials(mount.credentialRefId))
        assertNull(session.currentSnapshot())
    }

    @Test
    fun `凭据被拒后可重新提供正确凭据打开`() = runTest {
        val mount = testMount()
        val session = session(realVaultSource(), mount)

        session.open("wrong-pw".toCharArray(), null)
        val retry = session.open(password, null)

        assertTrue(retry.isSuccess)
        assertEquals(ChildDatabaseMountState.Opened(retry.getOrThrow()), session.state.value)
        assertTrue(credentials.hasCredentials(mount.credentialRefId))
    }

    @Test
    fun `来源不可读时判为来源不可用`() = runTest {
        val source = FakeChildDatabaseStreamSource(failure = FileNotFoundException("测试：文件缺失"))
        val session = session(source)

        val result = session.open(password, null)

        assertEquals(ChildDatabaseFailureReason.SOURCE_UNAVAILABLE, reasonOf(result))
        assertEquals(ChildDatabaseMountState.SourceUnavailable, session.state.value)
    }

    @Test
    fun `损坏文件判为损坏而不是凭据错误`() = runTest {
        val source = FakeChildDatabaseStreamSource(payload = ByteArray(64) { JUNK_BYTE })
        val session = session(source)

        val result = session.open(password, null)

        assertEquals(ChildDatabaseFailureReason.CORRUPT_FILE, reasonOf(result))
        assertEquals(
            ChildDatabaseMountState.Failed(ChildDatabaseFailureReason.CORRUPT_FILE),
            session.state.value
        )
    }

    @Test
    fun `无凭据时如实报缺且不用空复合密钥试探`() = runTest {
        val source = realVaultSource()
        val session = session(source)

        val result = session.openWithStoredCredentials()

        assertEquals(ChildDatabaseFailureReason.CREDENTIAL_MISSING, reasonOf(result))
        assertEquals(ChildDatabaseMountState.Closed, session.state.value)
        assertEquals("不得读取来源（否则会得到误导性的凭据被拒）", 0, source.openCount)
    }

    @Test
    fun `终止会话丢弃投影并清零凭据且幂等`() = runTest {
        val mount = testMount()
        val session = session(realVaultSource(), mount)
        assertTrue(session.open(password, null).isSuccess)
        assertTrue(credentials.hasCredentials(mount.credentialRefId))

        session.terminate()

        assertEquals(ChildDatabaseMountState.Closed, session.state.value)
        assertNull(session.currentSnapshot())
        assertFalse("独立凭据通道必须随终止清零", credentials.hasCredentials(mount.credentialRefId))

        session.terminate()
        assertEquals(ChildDatabaseMountState.Closed, session.state.value)
    }

    @Test
    fun `解密途中被终止时本次结果作废`() = runTest {
        val mount = testMount()
        val source = realVaultSource()
        val session = session(source, mount)
        // 在来源被读取（解密即将开始）的瞬间终止会话，确定性地复现锁定竞态
        source.onOpen = { session.terminate() }

        val result = session.open(password, null)

        assertEquals(ChildDatabaseFailureReason.TERMINATED, reasonOf(result))
        assertEquals(ChildDatabaseMountState.Closed, session.state.value)
        assertNull("过期结果不得装进会话", session.currentSnapshot())
        assertFalse(credentials.hasCredentials(mount.credentialRefId))
    }

    @Test
    fun `重复打开会以最新凭据重新解密`() = runTest {
        val source = realVaultSource()
        val session = session(source)

        assertTrue(session.open(password, null).isSuccess)
        assertTrue(session.open(password, null).isSuccess)

        assertEquals(2, source.openCount)
        val snapshot = requireNotNull(session.currentSnapshot()) { "重复打开后仍应有投影" }
        assertEquals(ChildDatabaseFixtures.ENTRY_COUNT, snapshot.entries.size)
    }

    /** 投影必须只有非敏感展示字段：任何一条目的任何文本字段都不得出现密码明文 */
    private fun assertNoPasswordLeak(snapshot: ChildDatabaseSnapshot) {
        val secrets = listOf(
            ChildDatabaseFixtures.ROOT_ENTRY_PASSWORD,
            ChildDatabaseFixtures.SUB_ENTRY_PASSWORD
        )
        snapshot.entries.forEach { entry ->
            val exposed = listOf(
                entry.title, entry.username, entry.url, entry.notes, entry.groupPath, entry.entryUuid
            ) + entry.tags
            secrets.forEach { secret ->
                assertFalse(
                    "投影不得携带密码明文（条目 ${entry.title}）",
                    exposed.any { it.contains(secret) }
                )
            }
        }
    }

    private companion object {
        /** 非法 KDBX 载荷填充字节（签名不匹配 → 判为损坏） */
        const val JUNK_BYTE: Byte = 0x5A
    }
}
