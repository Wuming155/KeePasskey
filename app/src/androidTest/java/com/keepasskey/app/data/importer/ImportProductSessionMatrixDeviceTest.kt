package com.keepasskey.app.data.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.ui.screens.importer.ImportUiState
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportProductSessionMatrixDeviceTest {
    @Test fun 首条落盘后产品锁库停止剩余条目并报告分段() = runBlocking {
        fixture { f ->
            val bBefore = f.later.readBytes()
            f.start(); f.awaitFirst()
            val aCommitted = f.first.readBytes()
            f.real.lockDatabase()
            f.release.complete(Unit)
            assertStopped(f.outcome())
            assertArrayEquals(aCommitted, f.first.readBytes())
            assertArrayEquals(bBefore, f.later.readBytes())
            assertEquals(setOf("Old-1"), f.titles(f.first))
            assertTrue(f.titles(f.later).isEmpty())
        }
    }

    @Test fun 首条落盘后产品锁定选择解锁B不得错投() = runBlocking {
        fixture { f ->
            f.start(); f.awaitFirst()
            val aCommitted = f.first.readBytes()
            val bBefore = f.later.readBytes()
            f.real.lockDatabase()
            f.open(f.later)
            f.release.complete(Unit)
            assertStopped(f.outcome())
            assertEquals(f.later.absolutePath, f.session.currentPathIdentifier)
            assertArrayEquals(aCommitted, f.first.readBytes())
            assertArrayEquals(bBefore, f.later.readBytes())
            assertEquals(setOf("Old-1"), f.titles(f.first))
            assertTrue(f.titles(f.later).isEmpty())
        }
    }

    @Test fun 首条落盘后同路径锁定重开旧票据失效() = runBlocking {
        fixture { f ->
            f.start(); f.awaitFirst()
            val aCommitted = f.first.readBytes()
            val bBefore = f.later.readBytes()
            f.real.lockDatabase()
            f.open(f.first)
            f.release.complete(Unit)
            assertStopped(f.outcome())
            assertArrayEquals(aCommitted, f.first.readBytes())
            assertArrayEquals(bBefore, f.later.readBytes())
            assertEquals(setOf("Old-1"), f.titles(f.first))
        }
    }

    @Test fun 取消后立即启动新批次旧回调不得复活报告或继续写入() = runBlocking {
        fixture { f ->
            val bBefore = f.later.readBytes()
            f.start(); f.awaitFirst()
            val aCommitted = f.first.readBytes()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { f.controller.cancelImport() }
            assertTrue(f.controller.uiState.value is ImportUiState.Idle)
            assertArrayEquals(aCommitted, f.first.readBytes())
            f.start("New")
            f.release.complete(Unit)
            withTimeout(ImportProductDeviceFixture.TIMEOUT_MS) { f.exited.await() }
            val outcome = f.outcome()
            assertFalse(outcome.sessionChanged)
            assertEquals(2, outcome.imported)
            assertEquals(0, outcome.unprocessed)
            assertEquals(setOf("Old-1", "New-1", "New-2"), f.titles(f.first))
            assertArrayEquals(bBefore, f.later.readBytes())
        }
    }

    private fun assertStopped(outcome: ImportOutcome) {
        assertTrue(outcome.sessionChanged)
        assertEquals(2, outcome.parsed)
        assertEquals(1, outcome.imported)
        assertEquals(1, outcome.unprocessed)
        assertEquals(0, outcome.failed)
        assertEquals(0, outcome.recycleBinIncomplete)
    }

    private suspend fun fixture(block: suspend (ImportProductDeviceFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = ImportProductDeviceFixture(File(context.cacheDir, "import-product-${System.nanoTime()}"))
        try { fixture.prepare(); block(fixture) } finally { fixture.cleanup() }
    }
}
