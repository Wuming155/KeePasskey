package com.keepasskey.app.data.importer

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.data.repository.FakeVaultRepository
import com.keepasskey.app.data.repository.VaultRepository
import com.keepasskey.app.ui.model.StringsProvider
import com.keepasskey.app.ui.model.UiVaultEntry
import com.keepasskey.core.result.KdbxResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P2-354 AC④`：导入落库的**可量化进度回调**与**协程取消路径**。
 *
 * 控制器（`VaultImportController`）持 `android.net.Uri` / `Context`，纯 JVM 不可构造；
 * 取消通道的另一半——「取消沿管线传播并保持擦除纪律」——在落库编排器这一层可直测：
 *
 * 1. `persist(onProgress = ...)` 逐条回调 `(已完成数, 本批总数)`，终值等于批次总数；
 * 2. 落库期间取消（门闩挂起 -> `cancelAndJoin`）：取消原样上抛、批次敏感数组仍被清零
 *    （`persist` 的全路径兜底 `finally` 在取消下同样生效）。
 *
 * 口令一律为虚构假凭据（测试数据规约）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultImportProgressCancelTest {

    @Test
    fun `落库进度回调按条推进且终值等于批次总数`() = runTest {
        val repository = FakeVaultRepository()
        val importer = VaultImporter(repository, TEST_STRINGS, DebugLogBuffer())
        val entries = (1..3).map { index ->
            ImportedEntry(
                title = "导入进度条目$index",
                username = "user$index",
                password = "Fake-Pw-$index".toCharArray(),
                url = "",
                notes = "",
                groupPath = emptyList()
            )
        }
        val batch = newBatch(entries)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = importer.persist(batch, onProgress = { done, total -> progress += done to total })

        assertTrue("三枚条目全部落库应返回 Success", result is KdbxResult.Success)
        assertEquals(
            "进度必须逐条推进且总数恒为批次规模",
            listOf(1 to 3, 2 to 3, 3 to 3),
            progress
        )
        assertTrue(
            "落库返回后批次敏感数组必须已清零（擦除纪律）",
            entries.all { entry -> entry.password.all { it == ERASED_CHAR } }
        )
    }

    @Test
    fun `落库在途被取消时取消上抛且批次敏感数组仍被清零`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val fake = FakeVaultRepository()
        // 门闩语义：进入第一条保存后挂起，让取消落在「落库在途」窗口内；
        // 未挂起的后续条目同走本覆盖（gate 已挂起时不会到达）
        val repository = object : VaultRepository by fake {
            override suspend fun saveEntry(
                entry: UiVaultEntry,
                passwordChars: CharArray?,
                totpSecretChars: CharArray?,
                protectedFieldChars: Map<String, CharArray>
            ): KdbxResult<Unit> {
                entered.complete(Unit)
                gate.await()
                return fake.saveEntry(entry, passwordChars, totpSecretChars, protectedFieldChars)
            }
        }
        val importer = VaultImporter(repository, TEST_STRINGS, DebugLogBuffer())
        val entries = (1..2).map { index ->
            ImportedEntry(
                title = "取消路径条目$index",
                username = "user$index",
                password = "Fake-Pw-$index".toCharArray(),
                url = "",
                notes = "",
                groupPath = emptyList()
            )
        }
        val batch = newBatch(entries)

        val job = launch { importer.persist(batch) }
        // 确凿证据：取消发生在「第一条保存已进入」之后（否则协程可能尚未启动，finally 无从谈起）
        entered.await()

        job.cancelAndJoin()

        assertTrue("取消后作业必须处于 cancelled 态", job.isCancelled)
        assertTrue(
            "取消路径必须清零批次口令数组（persist 的全路径 finally 在取消下同样生效）",
            entries.all { entry -> entry.password.all { it == ERASED_CHAR } }
        )
    }

    private fun newBatch(entries: List<ImportedEntry>): ImportBatch = ImportBatch(
        report = ImportReport(source = ImportSource.KEEPASS_XML, parsed = entries.size, skipped = 0),
        entries = entries
    )

    private companion object {
        /** `ImportedEntry.clear()` 的擦除字符（空字符，不是 '0'） */
        const val ERASED_CHAR = '\u0000'

        val TEST_STRINGS = StringsProvider { _, _ -> "未命名条目" }
    }
}
