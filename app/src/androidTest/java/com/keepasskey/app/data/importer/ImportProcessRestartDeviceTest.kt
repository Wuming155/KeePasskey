package com.keepasskey.app.data.importer

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.ui.screens.importer.ImportUiState
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 两阶段由宿主编排 force-stop；前置用例预期被杀，不应作为普通全类测试运行。 */
@RunWith(AndroidJUnit4::class)
class ImportProcessRestartDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.filesDir, "p2640-process-restart")

    @Test fun prepareAtFirstCommittedEntry(): Unit = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("p2640Phase") == "prepare") {
            "只允许宿主以 p2640Phase=prepare 启动死亡前置"
        }
        val fixture = ImportProductDeviceFixture(directory)
        fixture.prepare()
        fixture.start(); fixture.awaitFirst()
        File(directory, "A.before.bin").writeBytes(fixture.first.readBytes())
        File(directory, "B.before.bin").writeBytes(fixture.later.readBytes())
        assertEquals(setOf("Old-1"), fixture.titles(fixture.first))
        assertTrue(fixture.titles(fixture.later).isEmpty())
        File(directory, "GATE_REACHED").writeText(Process.myPid().toString())
        awaitCancellation()
    }

    @Test fun verifyInNewProcess() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("p2640Phase") == "verify") {
            "只允许宿主以 p2640Phase=verify 启动新进程核对"
        }
        val fixture = ImportProductDeviceFixture(directory)
        try {
            val oldPid = File(directory, "GATE_REACHED").readText().trim().toInt()
            assertNotEquals("必须是新的应用进程", oldPid, Process.myPid())
            assertTrue(fixture.session.state.value == com.keepasskey.database.session.DatabaseSession.SessionState.LOCKED)
            assertTrue(fixture.controller.uiState.value is ImportUiState.Idle)
            assertArrayEquals(File(directory, "A.before.bin").readBytes(), fixture.first.readBytes())
            assertArrayEquals(File(directory, "B.before.bin").readBytes(), fixture.later.readBytes())
            assertEquals(setOf("Old-1"), fixture.titles(fixture.first))
            assertTrue(fixture.titles(fixture.later).isEmpty())
            fixture.open(fixture.first)
            fixture.start("New")
            fixture.awaitFirst()
            fixture.release.complete(Unit)
            val outcome = fixture.outcome()
            assertEquals(2, outcome.imported)
            assertEquals(0, outcome.unprocessed)
            assertFalse(outcome.sessionChanged)
            assertEquals(setOf("Old-1", "New-1", "New-2"), fixture.titles(fixture.first))
            assertArrayEquals(File(directory, "B.before.bin").readBytes(), fixture.later.readBytes())
        } finally { fixture.cleanup() }
    }
}
