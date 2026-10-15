package com.keepasskey.app.sync

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
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
}
