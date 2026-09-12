package com.keepasskey.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BinaryStorePolicy] 落盘阈值策略回归（ISSUE-P2-24）。
 */
class BinaryStorePolicyTest {

    @Test
    fun `超过阈值才落盘`() {
        val threshold = 1024L
        assertTrue(BinaryStorePolicy.shouldSpill(1025L, threshold))
        assertTrue(BinaryStorePolicy.shouldSpill(4096L, threshold))
    }

    @Test
    fun `等于阈值仍驻留内存`() {
        val threshold = 1024L
        assertFalse(BinaryStorePolicy.shouldSpill(1024L, threshold))
        assertFalse(BinaryStorePolicy.shouldSpill(0L, threshold))
    }

    @Test
    fun `默认阈值为 1 MiB`() {
        assertFalse(BinaryStorePolicy.shouldSpill(BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES))
        assertTrue(BinaryStorePolicy.shouldSpill(BinaryStorePolicy.DEFAULT_THRESHOLD_BYTES + 1))
    }
}
