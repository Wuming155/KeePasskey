package com.keepasskey.app.sync

import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncCheckpointIoDeviceTest {

    @Test
    fun `checkpoint digest called from Main does not read the database file on Main`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseFile = File(context.cacheDir, "checkpoint-io-device.kdbx")
        val session = DatabaseSession()
        val password = "Fake#CheckpointIo".toCharArray()
        val violations = Collections.synchronizedList(
            mutableListOf<android.os.strictmode.Violation>()
        )
        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        var scope: CoroutineScope? = null

        try {
            val created = runBlocking {
                session.create(databaseFile, "Checkpoint IO", password, useArgon2 = false)
            }
            assertTrue("test KDBX must be created", created is KdbxResult.Success)
            val active = requireNotNull(session.databaseFlow.value)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val originalPolicy = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(originalPolicy)
                        .detectDiskReads()
                        .penaltyListener(Executor { it.run() }) { violations += it }
                        .build()
                )
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { mainScope ->
                    mainScope.launch {
                        try {
                            assertTrue(
                                "active file digest should be available",
                                session.writeIntents.digestStableFileFor(active) != null
                            )
                        } catch (t: Throwable) {
                            failure = t
                        } finally {
                            StrictMode.setThreadPolicy(originalPolicy)
                            completed.countDown()
                        }
                    }
                }
            }

            assertTrue("digest coroutine should complete", completed.await(10, TimeUnit.SECONDS))
            failure?.let { throw AssertionError("checkpoint digest failed", it) }
            assertTrue(
                "checkpoint summary must perform file reads on its IO dispatcher; Main violations=$violations",
                violations.isEmpty()
            )
        } finally {
            scope?.cancel()
            runBlocking { session.close() }
            password.fill('0')
            databaseFile.delete()
        }
    }

    @Test
    fun largeDatabaseDigestStaysOffMainWhileQueueRemainsResponsive() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseFile = File(context.cacheDir, "checkpoint-large-io-device.kdbx")
        val session = DatabaseSession()
        val password = "Fake#CheckpointLargeIo".toCharArray()
        val payload = ByteArray(8 * 1024 * 1024).also { Random(626).nextBytes(it) }
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "Checkpoint Large IO",
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(
                            KdbxConstants.Fields.TITLE to ProtectedString("synthetic-large-entry", false)
                        ),
                        attachments = listOf(KdbxAttachment("synthetic.bin", data = payload))
                    )
                )
            )
        )

        var scope: CoroutineScope? = null
        try {
            databaseFile.outputStream().use { KdbxFile.save(it, database, password) }
            database.clearSensitiveData()
            payload.fill(0)
            val databaseBytes = databaseFile.length()
            assertTrue("synthetic KDBX must exercise a multi-megabyte digest", databaseBytes >= 8 * 1024 * 1024)

            val opened = session.open(databaseFile, password)
            assertTrue("large synthetic KDBX must open", opened is KdbxResult.Success)
            val active = requireNotNull(session.databaseFlow.value)

            val violations = Collections.synchronizedList(
                mutableListOf<android.os.strictmode.Violation>()
            )
            val completed = CountDownLatch(1)
            var failure: Throwable? = null
            var digestDurationMs = -1L
            var mainQueueDelayMs = -1L
            var mainQueueServicedBeforeDigestCompleted = false
            var firstFrameDelayMs: Long? = null

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val originalPolicy = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder(originalPolicy)
                        .detectDiskReads()
                        .penaltyListener(Executor { it.run() }) { violations += it }
                        .build()
                )
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { mainScope ->
                    mainScope.launch {
                        try {
                            var digestCompleted = false
                            val mainQueueProbe = CompletableDeferred<Long>()
                            val frameProbe = CompletableDeferred<Long>()
                            val startedAt = SystemClock.elapsedRealtime()
                            val frameStartedAt = System.nanoTime()
                            Handler(Looper.getMainLooper()).post {
                                mainQueueServicedBeforeDigestCompleted = !digestCompleted
                                mainQueueProbe.complete(SystemClock.elapsedRealtime() - startedAt)
                            }
                            Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
                                frameProbe.complete((frameTimeNanos - frameStartedAt) / 1_000_000)
                            }

                            val digestStartedAt = SystemClock.elapsedRealtime()
                            val revision = session.writeIntents.digestStableFileFor(active)
                            digestDurationMs = SystemClock.elapsedRealtime() - digestStartedAt
                            digestCompleted = true
                            mainQueueDelayMs = mainQueueProbe.await()
                            firstFrameDelayMs = withTimeoutOrNull(1_000L) { frameProbe.await() }

                            assertTrue("large file digest should return a stable receipt", revision != null)
                            assertEquals("SHA-256 receipt length", 64, revision!!.sha256.length)
                        } catch (t: Throwable) {
                            failure = t
                        } finally {
                            StrictMode.setThreadPolicy(originalPolicy)
                            completed.countDown()
                        }
                    }
                }
            }

            assertTrue("large digest coroutine should complete", completed.await(15, TimeUnit.SECONDS))
            failure?.let { throw AssertionError("large checkpoint digest failed", it) }
            assertTrue(
                "large checkpoint hashing must not read the file on Main; violations=$violations",
                violations.isEmpty()
            )
            assertTrue(
                "Main queue callback should run while the file digest is in IO",
                mainQueueServicedBeforeDigestCompleted
            )
            assertTrue("vsync frame callback should be serviced", firstFrameDelayMs != null)
            assertTrue(
                "first vsync callback should arrive before the off-main digest finishes",
                firstFrameDelayMs!! < digestDurationMs
            )
            System.out.println(
                "P2-626 device sample: bytes=$databaseBytes digest_ms=$digestDurationMs " +
                    "main_queue_callback_ms=$mainQueueDelayMs first_frame_ms=$firstFrameDelayMs"
            )
        } finally {
            scope?.cancel()
            runBlocking { session.close() }
            password.fill('0')
            payload.fill(0)
            databaseFile.delete()
        }
    }
}
