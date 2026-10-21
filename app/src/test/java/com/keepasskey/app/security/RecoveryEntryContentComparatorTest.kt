package com.keepasskey.app.security

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxCustomField
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxTimes
import com.keepasskey.core.security.BinarySource
import com.keepasskey.core.security.ProtectedString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryEntryContentComparatorTest {
    @Test fun `密码OTP和通行密钥字段各自变化均为修改`() {
        for (key in listOf(KdbxConstants.Fields.PASSWORD, KdbxConstants.Fields.OTP, "Passkey.PrivateKey")) {
            val original = entry().withField(key, ProtectedString("Synthetic-A", true))
            val changed = original.withField(key, ProtectedString("Synthetic-B", true))
            try { assertFalse(key, RecoveryEntryContentComparator().same(original, changed)) }
            finally { original.clearSensitiveData(); changed.clearSensitiveData() }
        }
    }

    @Test fun `受保护自定义字段和等长度历史内容变化均为修改`() {
        val original = entry().copy(customFields = listOf(KdbxCustomField("secret", ProtectedString("Fake-A", true))))
        val changed = original.copy(customFields = listOf(KdbxCustomField("secret", ProtectedString("Fake-B", true))))
        val historyA = entry().copy(history = listOf(original))
        val historyB = historyA.copy(history = listOf(changed))
        try {
            assertFalse(RecoveryEntryContentComparator().same(original, changed))
            assertFalse(RecoveryEntryContentComparator().same(historyA, historyB))
        } finally { historyA.clearSensitiveData(); historyB.clearSensitiveData() }
    }

    @Test fun `同数量同哈希落盘附件的不同字节仍为修改`() {
        val bytesA = byteArrayOf(0, 31)
        val bytesB = byteArrayOf(1, 0)
        assertTrue(bytesA.contentHashCode() == bytesB.contentHashCode())
        val left = entry().copy(attachments = listOf(attachment(bytesA)))
        val right = left.copy(attachments = listOf(attachment(bytesB)))
        try {
            assertFalse(RecoveryEntryContentComparator().same(left, right))
            assertArrayEquals(byteArrayOf(0, 31), bytesA)
            assertArrayEquals(byteArrayOf(1, 0), bytesB)
        } finally { left.clearSensitiveData(); right.clearSensitiveData(); bytesA.fill(0); bytesB.fill(0) }
    }

    @Test fun `无变化负对照忽略附件池索引但保全借用字节`() {
        val bytes = byteArrayOf(2, 4, 6)
        val left = entry().copy(attachments = listOf(KdbxAttachment("fixture", refIndex = 1, data = bytes)))
        val right = left.copy(attachments = listOf(KdbxAttachment("fixture", refIndex = 9, data = bytes.copyOf())))
        try {
            assertTrue(RecoveryEntryContentComparator().same(left, right))
            assertArrayEquals(byteArrayOf(2, 4, 6), bytes)
        } finally { left.clearSensitiveData(); right.clearSensitiveData() }
    }

    @Test fun `附件读取失败长度虚报和预算不足不得判相同`() {
        val left = entry().copy(attachments = listOf(attachment(byteArrayOf(1, 2, 3))))
        val truncated = left.copy(attachments = listOf(attachment(byteArrayOf(1), size = 3)))
        val broken = left.copy(attachments = listOf(attachment(byteArrayOf(1, 2, 3), fail = true)))
        try {
            assertThrows(IOException::class.java) { RecoveryEntryContentComparator().same(left, truncated) }
            assertThrows(IOException::class.java) { RecoveryEntryContentComparator().same(left, broken) }
            assertThrows(IOException::class.java) { RecoveryEntryContentComparator(5).same(left, left) }
        } finally { left.clearSensitiveData(); truncated.clearSensitiveData(); broken.clearSensitiveData() }
    }

    @Test fun `到期策略改变会产生差异使用计数和访问时间不产生内容差异`() {
        val left = entry()
        try {
            assertFalse(RecoveryEntryContentComparator().same(left, left.copy(times = left.times.copy(expires = true))))
            assertTrue(RecoveryEntryContentComparator().same(left, left.copy(times = left.times.copy(usageCount = 7, lastAccessTime = Instant.EPOCH))))
        } finally { left.clearSensitiveData() }
    }

    private fun entry() = KdbxEntry(
        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("Synthetic fixture")),
        times = KdbxTimes(creationTime = Instant.parse("2026-10-21T00:00:00Z"))
    )

    private fun attachment(bytes: ByteArray, size: Long = bytes.size.toLong(), fail: Boolean = false) =
        KdbxAttachment("fixture", source = object : BinarySource {
            override val size = size
            override fun load(): ByteArray = error("比较不得整份物化附件")
            override fun contentHash(): Int = bytes.contentHashCode()
            override fun openStream(): InputStream {
                if (fail) throw IOException("synthetic source failure")
                return ByteArrayInputStream(bytes)
            }
        })
}
