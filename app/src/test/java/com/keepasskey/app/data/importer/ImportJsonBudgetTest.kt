package com.keepasskey.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ImportJson` 解析预算单测（ISSUE-P2-618）。
 *
 * 覆盖两道预算闸门：**节点总数**（对象 / 数组 / 字符串 / 数值 / 字面量，对象键计入）与
 * **单容器成员数**。以注入的小上限直测解析器本体，逐项锁定「超限抛
 * [ImportLimitExceededException]、恰为上限可通过、归类为 [ImportFailureReason.LIMIT_EXCEEDED]」；
 * 生产预算在 Bitwarden / 1PUX 两个导入器用例中另有端到端锁定。
 */
class ImportJsonBudgetTest {

    @Test
    fun `节点总数超出注入预算时抛上限异常`() {
        // [1,2,3,4,5] 共 6 个节点：数组本体 + 5 个数值
        val error = parseLimitFailure("[1,2,3,4,5]", ImportJson.Limits(maxNodes = 5))

        assertTrue(error is ImportLimitExceededException)
    }

    @Test
    fun `节点数恰为预算上界时可解析`() {
        val value = ImportJson.parse("[1,2,3,4,5]", ImportJson.Limits(maxNodes = 6))

        assertEquals(5, (value as List<*>).size)
    }

    @Test
    fun `对象键计入节点预算`() {
        // `{"a":1}` 共 3 个节点：对象本体 + 键 + 数值
        assertTrue(
            parseLimitFailure("{\"a\":1}", ImportJson.Limits(maxNodes = 2)) is ImportLimitExceededException
        )
        val value = ImportJson.parse("{\"a\":1}", ImportJson.Limits(maxNodes = 3))
        assertEquals(1L, (value as Map<*, *>)["a"])
    }

    @Test
    fun `数组容器成员数超出注入预算时抛上限异常`() {
        val error = parseLimitFailure("[1,2,3,4,5]", ImportJson.Limits(maxContainerMembers = 4))

        assertTrue(error is ImportLimitExceededException)
    }

    @Test
    fun `数组容器成员数恰为预算上界时可解析`() {
        val value = ImportJson.parse("[1,2,3,4]", ImportJson.Limits(maxContainerMembers = 4))

        assertEquals(4, (value as List<*>).size)
    }

    @Test
    fun `对象容器成员数超出注入预算时抛上限异常`() {
        val error = parseLimitFailure("{\"a\":1,\"b\":2,\"c\":3}", ImportJson.Limits(maxContainerMembers = 2))

        assertTrue(error is ImportLimitExceededException)
    }

    @Test
    fun `上限异常归类为 LIMIT_EXCEEDED`() {
        val error = parseLimitFailure("[1,2,3,4,5]", ImportJson.Limits(maxNodes = 5))

        assertEquals(ImportFailureReason.LIMIT_EXCEEDED, ImportFailureReason.classify(error))
    }

    /** 断言解析抛出超限异常；超限前成功返回（含语法非法）一律判用例失败。 */
    private fun parseLimitFailure(text: String, limits: ImportJson.Limits): ImportLimitExceededException {
        try {
            ImportJson.parse(text, limits)
        } catch (error: ImportLimitExceededException) {
            return error
        }
        throw AssertionError("期望超限抛 ImportLimitExceededException，但解析未超限")
    }
}