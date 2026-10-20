package com.keepasskey.app.data.importer

import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.ui.model.VaultGroup
import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.exception.KdbxSessionChangedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportSessionStopTest {
    @Test fun 首条提交后会话失效停止余下条目并分别计数() = runBlocking {
        var current = true
        val entries = (1..3).map { imported(it) }
        val run = ImportPersistRun(
            FakeVaultRepository(), GroupPathResolver(emptyList()) { KdbxResult.Success(Unit) },
            ImportConflictPolicy.CREATE_DUPLICATE, "未命名", ImportWarningCollector(),
            checkTarget = { if (!current) throw KdbxSessionChangedException() }
        )
        run.run(entries) { done, _ -> if (done == 1) current = false }
        assertEquals(1, run.imported)
        assertEquals(0, run.failed)
        assertEquals(2, run.unprocessed)
        assertTrue(run.sessionChanged)
        assertEquals(entries.size, run.imported + run.updated + run.failed + run.skipped + run.unprocessed)
    }

    @Test fun 分组创建的会话失效不能降级到根分组() = runBlocking {
        val resolver = GroupPathResolver(listOf(VaultGroup(id = "root", name = "Root"))) {
            KdbxResult.Failure(KdbxSessionChangedException())
        }
        try { resolver.resolve(listOf("Imported")); fail("会话失效必须停止") }
        catch (_: KdbxSessionChangedException) { }
    }

    @Test fun 已保存但尚未软删时切库明确报告回收站未完成() = runBlocking {
        val base = FakeVaultRepository()
        val repository = object : com.keepasskey.app.data.repository.VaultRepository by base {
            override suspend fun deleteEntry(id: String): KdbxResult<Unit> = KdbxResult.Failure(KdbxSessionChangedException())
        }
        val run = ImportPersistRun(repository, GroupPathResolver(emptyList()) { KdbxResult.Success(Unit) },
            ImportConflictPolicy.CREATE_DUPLICATE, "未命名", ImportWarningCollector())
        run.run(listOf(imported(1, deleted = true), imported(2)))
        assertTrue(run.sessionChanged)
        assertEquals(1, run.imported)
        assertEquals(1, run.recycleBinIncomplete)
        assertEquals(1, run.unprocessed)
        assertEquals(0, run.movedToRecycleBin)
    }

    private fun imported(index: Int, deleted: Boolean = false) = ImportedEntry(
        title = "Synthetic-$index", username = "user", password = "Fake-$index".toCharArray(),
        url = "", notes = "", groupPath = emptyList(), deleted = deleted
    )
}
