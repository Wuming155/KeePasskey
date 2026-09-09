package com.keepasskey.app.data.breach

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.core.security.ProtectedString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 泄露比对协调器回归测试（TASK-47）。
 *
 * 覆盖：命中判定、同前缀聚合去重（减少外联次数）、空密码跳过、失败向上传播（不静默回落）。
 *
 * 口令 "password" 的 SHA-1 前缀为 `5BAA6`（公开常量，非真实凭据）。
 */
class BreachCheckCoordinatorTest {

    private class FakeRangeClient(
        private val breachedSuffixes: Set<String> = emptySet(),
        private val failure: String? = null
    ) : BreachRangeClient {
        val queriedPrefixes = mutableListOf<String>()

        override suspend fun queryRange(prefix: String): Set<String> {
            queriedPrefixes.add(prefix)
            if (failure != null) throw BreachCheckException(failure)
            return breachedSuffixes
        }
    }

    private companion object {
        const val BREACHED_SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8"

        fun entry(id: Byte, password: String): KdbxEntry = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { id }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("Site $id", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString(password, isProtected = true)
            )
        )
    }

    @Test
    fun `命中泄露库的条目被标记为 BREACHED`() = runBlocking {
        val client = FakeRangeClient(breachedSuffixes = setOf(BREACHED_SUFFIX))
        val breached = entry(1, "password")
        val safe = entry(2, "UnLeaked-Strong-Pass#2026!")

        val outcome = BreachCheckCoordinator(client).check(listOf(breached, safe))

        assertEquals(BreachCheckStatus.BREACHED, outcome.status)
        assertEquals(1, outcome.breachedCount)
        assertTrue(outcome.breachedEntryIds.contains(breached.id.toHexString()))
        assertEquals(2, client.queriedPrefixes.size)
    }

    @Test
    fun `相同密码只产生一次前缀查询（聚合去重减少外联）`() = runBlocking {
        val client = FakeRangeClient(breachedSuffixes = setOf(BREACHED_SUFFIX))

        val outcome = BreachCheckCoordinator(client).check(
            listOf(entry(1, "password"), entry(2, "password"), entry(3, "password"))
        )

        assertEquals(3, outcome.breachedCount)
        assertEquals(1, client.queriedPrefixes.size)
        assertEquals("5BAA6", client.queriedPrefixes.single())
    }

    @Test
    fun `未命中任何泄露记录时返回 CLEAN 且计数为 0`() = runBlocking {
        val client = FakeRangeClient(breachedSuffixes = emptySet())

        val outcome = BreachCheckCoordinator(client).check(listOf(entry(1, "password")))

        assertEquals(BreachCheckStatus.CLEAN, outcome.status)
        assertEquals(0, outcome.breachedCount)
    }

    @Test
    fun `无密码条目不产生任何网络查询`() = runBlocking {
        val client = FakeRangeClient()
        val emptyPassword = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 9 }),
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString("No password", isProtected = false),
                KdbxConstants.Fields.PASSWORD to ProtectedString("", isProtected = true)
            )
        )
        val noPasswordField = KdbxEntry(
            id = KdbxUuid(ByteArray(16) { 10 }),
            fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Note only", false))
        )

        val outcome = BreachCheckCoordinator(client).check(listOf(emptyPassword, noPasswordField))

        assertEquals(BreachCheckStatus.CLEAN, outcome.status)
        assertEquals(0, client.queriedPrefixes.size)
    }

    @Test
    fun `查询失败向上传播，绝不静默回落为未泄露`() {
        val client = FakeRangeClient(failure = "模拟网络不可达")

        val error = assertThrows(BreachCheckException::class.java) {
            runBlocking { BreachCheckCoordinator(client).check(listOf(entry(1, "password"))) }
        }
        assertTrue(error.message!!.contains("模拟网络不可达"))
    }
}
