package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxError
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException

/** ISSUE-P1-593：同步回执在真实取消窗口决定采用；保留借用输入和自有缓冲的可观察引用。 */
class SessionCredentialRotationTest {
    private class Fixture : Closeable {
        val oldPassword = "old-test".toCharArray()
        val newPassword = "new-test".toCharArray()
        val core = SessionCore()
        val cache = SessionCredentialCache()
        val writer = SessionFileWriter { false }
        var preparedPassword: CharArray? = null
        var preparedBytes: ByteArray? = null
        var failSerialize = false
        init {
            cache.cachePassword(oldPassword)
            core.database.value = KdbxDatabase(header = KdbxHeader.createDefault(useArgon2 = false), rootGroup = KdbxGroup(name = "Test"))
            core.saveWriter = { }
        }
        fun transaction() = SessionCredentialRotation(core, cache, writer) { _, password, _ ->
            preparedPassword = password
            if (failSerialize) throw IOException("合成序列化失败")
            ByteArray(16) { 7 }.also { preparedBytes = it }
        }
        override fun close() {
            cache.clear()
            core.database.value?.clearSensitiveData()
            oldPassword.fill('0')
            newPassword.fill('0')
        }
    }

    @Test fun `提交前取消保留旧缓存并擦除候选`() = runBlocking {
        Fixture().use { f ->
            f.core.trackedSaveWriter = { _, _ -> throw CancellationException("写前取消") }
            var cancelled = false
            try { f.transaction().execute(f.newPassword, null) }
            catch (e: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertArrayEquals(f.oldPassword, f.cache.currentPassword())
            assertTrue(f.preparedPassword!!.all { it == '0' })
            assertTrue(f.preparedBytes!!.all { it == 0.toByte() })
            assertArrayEquals("new-test".toCharArray(), f.newPassword)
            assertFalse(f.core.readOnlyMode)
        }
    }

    @Test fun `实际提交后取消必须采用新缓存并传播已提交回执`() = runBlocking {
        Fixture().use { f ->
            f.core.trackedSaveWriter = { _, receipt ->
                receipt.writing()
                receipt.committed()
                currentCoroutineContext().cancel(CancellationException("替换后取消"))
                currentCoroutineContext().ensureActive()
            }
            var cancellation: CredentialRotationCancellationException? = null
            launch {
                try { f.transaction().execute(f.newPassword, null) }
                catch (e: CredentialRotationCancellationException) { cancellation = e }
            }.join()
            assertEquals(SessionWriteReceipt.Status.COMMITTED, cancellation?.writeStatus)
            assertArrayEquals(f.newPassword, f.cache.currentPassword())
            assertSame("采用无分配移交的候选数组", f.preparedPassword, f.cache.currentPassword())
            assertTrue(f.preparedBytes!!.all { it == 0.toByte() })
            assertFalse(f.core.credentialWriteUncertain.value)
        }
    }

    @Test fun `写入中取消标为不确定且禁止普通保存和继续轮换`() = runBlocking {
        Fixture().use { f ->
            f.core.trackedSaveWriter = { _, receipt ->
                receipt.writing()
                throw CancellationException("部分写入取消")
            }
            var cancellation: CredentialRotationCancellationException? = null
            try { f.transaction().execute(f.newPassword, null) }
            catch (e: CredentialRotationCancellationException) { cancellation = e }
            assertEquals(SessionWriteReceipt.Status.UNKNOWN, cancellation?.writeStatus)
            assertTrue(f.core.credentialWriteUncertain.value)
            assertTrue(f.core.readOnlyMode)
            assertArrayEquals(f.oldPassword, f.cache.currentPassword())
            assertTrue(f.preparedPassword!!.all { it == '0' })
            assertTrue(f.preparedBytes!!.all { it == 0.toByte() })
            assertTrue(SessionPersistence(Mutex(), f.core, f.cache, f.writer).save().isFailure)
            val result = f.transaction().execute(f.newPassword, null) as KdbxResult.Failure
            assertEquals(KdbxError.CREDENTIALS_WRITE_UNCERTAIN, result.code)
        }
    }

    @Test fun `可证未提交异常不进入只读且旧凭据不变`() = runBlocking {
        Fixture().use { f ->
            f.core.trackedSaveWriter = { _, receipt ->
                receipt.writing()
                receipt.notCommitted()
                throw IOException("临时写失败")
            }
            assertTrue(f.transaction().execute(f.newPassword, null).isFailure)
            assertArrayEquals(f.oldPassword, f.cache.currentPassword())
            assertFalse(f.core.readOnlyMode)
            assertTrue(f.preparedPassword!!.all { it == '0' })
        }
    }

    @Test fun `任意旧式writer失败不能假定原件未改变`() = runBlocking {
        Fixture().use { f ->
            f.core.saveWriter = { throw IOException("结果未知") }
            val result = f.transaction().execute(f.newPassword, null) as KdbxResult.Failure
            assertEquals(KdbxError.CREDENTIALS_WRITE_UNCERTAIN, result.code)
            assertTrue(f.core.readOnlyMode)
            assertArrayEquals(f.oldPassword, f.cache.currentPassword())
        }
    }

    @Test fun `派生失败清候选且不调用写入通道`() = runBlocking {
        Fixture().use { f ->
            f.failSerialize = true
            var writes = 0
            f.core.trackedSaveWriter = { _, _ -> writes++ }
            assertTrue(f.transaction().execute(f.newPassword, null).isFailure)
            assertEquals(0, writes)
            assertArrayEquals(f.oldPassword, f.cache.currentPassword())
            assertTrue(f.preparedPassword!!.all { it == '0' })
        }
    }

    @Test fun `提交证据不被后续IO异常回退`() = runBlocking {
        Fixture().use { f ->
            f.core.trackedSaveWriter = { _, receipt -> receipt.committed(); throw IOException("返回后失败") }
            assertTrue(f.transaction().execute(f.newPassword, null).isSuccess)
            assertArrayEquals(f.newPassword, f.cache.currentPassword())
        }
    }

    @Test fun `无因子候选在序列化之前拒绝`() = runBlocking {
        Fixture().use { f ->
            val result = f.transaction().execute(charArrayOf(), null) as KdbxResult.Failure
            assertEquals(KdbxError.CREDENTIALS_KEYFILE_ONLY_UNBIND, result.code)
            assertNull(f.preparedPassword)
            assertArrayEquals(f.oldPassword, f.cache.currentPassword())
        }
    }
}
