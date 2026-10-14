package com.keepasskey.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.binary.FileBinaryStore
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.BinarySource
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.history.HistoryManager
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * ISSUE-P2-599：设备侧检查历史尺寸估算只读附件元数据的实际路径。
 * 记录历史估算区间的 elapsed / 主线程 CPU / 进程累计分配差值供设备复核；
 * allocation 差值还会包含同进程其他线程的分配。读数没有性能阈值，
 * 不代表真实用户 UI 操作的完整性能表现，也不等同于本操作精确分配量。
 */
@RunWith(AndroidJUnit4::class)
class HistoryAttachmentMetadataAndroidTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    @Test
    fun `历史尺寸估算仅取附件元数据且在Default执行`() = runBlocking<Unit> {
        val fixtureId = UUID.randomUUID().toString()
        val fixtureRoot = File(targetContext.cacheDir, "history-metadata-$fixtureId")
        val fixtureCache = File(fixtureRoot, "cache")
        val scopedContext = object : ContextWrapper(targetContext) {
            override fun getCacheDir(): File = fixtureCache
        }
        val store = FileBinaryStore(scopedContext)
        val session = DatabaseSession(store).apply { addLockObserver(store) }
        val password = "HistoryMetadata#2026".toCharArray()
        val payload = ByteArray(1_200_000) { (it % 251).toByte() }
        var databaseFile: File? = null
        val measurements = mutableListOf<Measurement>()
        var primaryFailure: Throwable? = null

        try {
            for (historyCount in listOf(8, 32, 64)) {
                databaseFile?.delete()
                val entries = (0 until historyCount).map {
                    KdbxEntry(
                        fields = mapOf(FIELD_KEY to ProtectedString(FIELD_VALUE, isProtected = false)),
                        attachments = listOf(KdbxAttachment(ATTACHMENT_NAME, data = payload.copyOf()))
                    )
                }
                val current = entries.last().copy(history = entries.dropLast(1).asReversed())
                val sourceDatabase = KdbxDatabase(
                    header = KdbxHeader.createDefault(useArgon2 = false),
                    rootGroup = KdbxGroup(name = "Root", entries = listOf(current))
                )
                try {
                    databaseFile = File(fixtureRoot, "history-$historyCount.kdbx").apply {
                        parentFile?.mkdirs()
                        FileOutputStream(this).use { output -> KdbxFile.save(output, sourceDatabase, password) }
                    }
                } finally {
                    // 序列化成功或失败都擦除本轮独立源树；已解析会话树与它没有数组别名。
                    sourceDatabase.clearSensitiveData()
                }

                val opened = session.open(databaseFile!!, password)
                assertTrue("synthetic database must open", opened is KdbxResult.Success)
                val openedCurrent = session.databaseFlow.value!!.rootGroup.entries.single()
                assertEquals(historyCount - 1, openedCurrent.history.size)
                val allOpenedEntries = listOf(openedCurrent) + openedCurrent.history
                assertEquals(historyCount, allOpenedEntries.size)
                val originalAttachments = allOpenedEntries.map { it.attachments.single() }
                originalAttachments.forEach { attachment ->
                    assertNotNull("large attachment must use a BinarySource", attachment.binarySource())
                    assertTrue(attachment.size >= MIN_ATTACHMENT_BYTES)
                }

                // 实际文件读回在计数装饰器之外；只验证数据仍可取回且字节未损坏。
                originalAttachments.forEach { attachment ->
                    val actual = attachment.data
                    try {
                        assertArrayEquals(payload, actual)
                    } finally {
                        actual.fill(0)
                    }
                }

                val counters = originalAttachments.map { CountingBinarySource(it.binarySource()!!) }
                val countedEntries = allOpenedEntries.mapIndexed { index, entry ->
                    entry.copy(attachments = listOf(KdbxAttachment(ATTACHMENT_NAME, source = counters[index])))
                }
                val countedCurrent = countedEntries.first().copy(history = countedEntries.drop(1))
                val fullPass = measureOnDefault(countedCurrent, Long.MAX_VALUE, counters)
                assertEquals(historyCount, fullPass.result.history.size)
                assertEquals(historyCount, counters.sumOf { it.sizeReads })
                assertTrue(counters.all { it.loadReads == 0 && it.openStreamReads == 0 && it.contentHashReads == 0 })
                assertTrue(counters.all { it.sizeThread !== Looper.getMainLooper().thread })
                fullPass.result.history.zip(counters).forEach { (entry, source) ->
                    assertSame(source, entry.attachments.single().binarySource())
                }
                assertAttachmentsReadable(originalAttachments, payload)
                measurements += fullPass.measurement.copy(historyCount = historyCount, budgetPass = false)

                if (historyCount == 8) {
                    counters.forEach { it.reset() }
                    val itemSize = MIN_ATTACHMENT_BYTES + ATTACHMENT_NAME.length + FIXED_ENTRY_OVERHEAD +
                        FIELD_KEY.length + FIELD_VALUE.length
                    val twoItemBudget = itemSize * 2L
                    val clipped = measureOnDefault(countedCurrent, twoItemBudget, counters)
                    assertEquals(2, clipped.result.history.size)
                    assertEquals(3, counters.sumOf { it.sizeReads })
                    assertEquals(listOf(1, 1, 1, 0, 0, 0, 0, 0), counters.map { it.sizeReads })
                    assertTrue(counters.all { it.loadReads == 0 && it.openStreamReads == 0 && it.contentHashReads == 0 })
                    clipped.result.history.zip(counters.take(2)).forEach { (entry, source) ->
                        assertSame(source, entry.attachments.single().binarySource())
                    }
                    assertAttachmentsReadable(originalAttachments, payload)
                    measurements += clipped.measurement.copy(historyCount = historyCount, budgetPass = true)
                }
            }

            val resultFile = File(targetContext.filesDir, "history-attachment-metadata-$fixtureId.json")
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(measurements.toJson())
            android.util.Log.i(TAG, "measurement_file=${resultFile.absolutePath}; ${measurements.toJson()}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            fun recordCleanupFailure(failure: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
            }
            try {
                session.close()
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
            try {
                databaseFile?.delete()
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
            try {
                payload.fill(0)
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
            try {
                password.fill('\u0000')
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
            try {
                store.clear()
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
            try {
                fixtureRoot.deleteRecursively()
            } catch (failure: Throwable) {
                recordCleanupFailure(failure)
            }
            cleanupFailure?.let { failure ->
                val original = primaryFailure
                if (original != null) original.addSuppressed(failure) else throw failure
            }
        }
    }

    private suspend fun measureOnDefault(
        currentEntry: KdbxEntry,
        maxSize: Long,
        counters: List<CountingBinarySource>
    ): PassResult = withContext(Dispatchers.Main) {
        counters.forEach { it.reset() }
        val mainThread = Looper.getMainLooper().thread
        val mainCpuBefore = Debug.threadCpuTimeNanos()
        val allocatedBefore = runtimeAllocatedBytes()
        val started = SystemClock.elapsedRealtimeNanos()
        val result = withContext(Dispatchers.Default) {
            HistoryManager.recordHistorySnapshot(
                currentEntry = currentEntry,
                newEntry = KdbxEntry(id = currentEntry.id),
                maxHistoryItems = HistoryManager.UNLIMITED,
                maxHistorySize = maxSize
            )
        }
        val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
        val mainCpuAfter = Debug.threadCpuTimeNanos()
        val allocatedAfter = runtimeAllocatedBytes()
        PassResult(
            result = result,
            measurement = Measurement(
                historyCount = result.history.size,
                budgetPass = false,
                elapsedRealtimeNanos = elapsedNanos,
                mainThreadCpuNanosDelta = mainCpuAfter - mainCpuBefore,
                processCumulativeAllocatedBytesBefore = allocatedBefore,
                processCumulativeAllocatedBytesAfter = allocatedAfter,
                processCumulativeAllocatedBytesDelta = allocatedBefore?.let { before -> allocatedAfter?.minus(before) },
                sizeReads = counters.sumOf { it.sizeReads },
                callerWasMainThread = Thread.currentThread() === mainThread,
                sizeRanOffMainThread = counters.all { it.sizeThread !== mainThread }
            )
        )
    }

    private fun runtimeAllocatedBytes(): Long? =
        Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()

    private fun assertAttachmentsReadable(attachments: List<KdbxAttachment>, expected: ByteArray) {
        // 只读底层生产来源，调用不经过计数装饰器，且不属于 HistoryManager 操作窗口。
        attachments.forEach { attachment ->
            val actual = attachment.data
            try {
                assertArrayEquals(expected, actual)
            } finally {
                actual.fill(0)
            }
        }
    }

    private data class PassResult(val result: KdbxEntry, val measurement: Measurement)

    private data class Measurement(
        val historyCount: Int,
        val budgetPass: Boolean,
        val elapsedRealtimeNanos: Long,
        val mainThreadCpuNanosDelta: Long,
        val processCumulativeAllocatedBytesBefore: Long?,
        val processCumulativeAllocatedBytesAfter: Long?,
        val processCumulativeAllocatedBytesDelta: Long?,
        val sizeReads: Int,
        val callerWasMainThread: Boolean,
        val sizeRanOffMainThread: Boolean
    )

    private class CountingBinarySource(private val delegate: BinarySource) : BinarySource {
        var sizeReads = 0
            private set
        var loadReads = 0
            private set
        var openStreamReads = 0
            private set
        var contentHashReads = 0
            private set
        var sizeThread: Thread? = null
            private set

        override val size: Long
            get() {
                sizeReads++
                sizeThread = Thread.currentThread()
                return delegate.size
            }

        override fun load(): ByteArray {
            loadReads++
            throw AssertionError("HistoryManager must not load attachment bytes")
        }

        override fun openStream(): InputStream {
            openStreamReads++
            throw AssertionError("HistoryManager must not open attachment streams")
        }

        override fun contentHash(): Int {
            contentHashReads++
            throw AssertionError("HistoryManager must not hash attachment bytes")
        }

        fun reset() {
            sizeReads = 0
            loadReads = 0
            openStreamReads = 0
            contentHashReads = 0
            sizeThread = null
        }
    }

    private fun List<Measurement>.toJson(): String = joinToString(
        prefix = "{\"note\":\"art.gc.bytes-allocated is a process cumulative delta, not exact operation allocations\",\"samples\":[",
        postfix = "]}\n",
        separator = ","
    ) { sample ->
        """{"historyCount":${sample.historyCount},"budgetPass":${sample.budgetPass},"elapsedRealtimeNanos":${sample.elapsedRealtimeNanos},"mainThreadCpuNanosDelta":${sample.mainThreadCpuNanosDelta},"processCumulativeAllocatedBytesBefore":${sample.processCumulativeAllocatedBytesBefore ?: "null"},"processCumulativeAllocatedBytesAfter":${sample.processCumulativeAllocatedBytesAfter ?: "null"},"processCumulativeAllocatedBytesDelta":${sample.processCumulativeAllocatedBytesDelta ?: "null"},"sizeReads":${sample.sizeReads},"callerWasMainThread":${sample.callerWasMainThread},"sizeRanOffMainThread":${sample.sizeRanOffMainThread}}"""
    }

    private companion object {
        const val TAG = "HistoryAttachmentMetadata"
        const val ATTACHMENT_NAME = "payload.bin"
        const val FIELD_KEY = "Title"
        const val FIELD_VALUE = "History"
        const val MIN_ATTACHMENT_BYTES = 1_200_000L
        const val FIXED_ENTRY_OVERHEAD = 12L
    }
}
