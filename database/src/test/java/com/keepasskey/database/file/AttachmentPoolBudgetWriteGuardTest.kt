package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.exception.KdbxAttachmentPoolBudgetExceededException
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P2-311 AC②：写侧累计闸——池累计超过读侧同一单值
 * （`MAX_BINARY_POOL_TOTAL_BYTES` = 134217728）的附件组合，必须在保存期被类型化异常拦截，
 * 而非产出「本设备写得出、读不进」的库（读侧累计闸只在解析期生效，整改前写侧零累计闸）。
 */
class AttachmentPoolBudgetWriteGuardTest {

    @Test
    fun `池累计超上限的附件组合在保存期被类型化异常拦截`() {
        val password = "ParityPoolBudget#2026".toCharArray()
        // 3 × 50 MiB = 157286400 > MAX_BINARY_POOL_TOTAL_BYTES（134217728）
        val sizes = listOf(50L * 1024 * 1024, 50L * 1024 * 1024, 50L * 1024 * 1024)
        val attachments = sizes.mapIndexed { index, size ->
            KdbxAttachment("big$index.bin", data = AttachmentWriteReadLimitParityTest.compressiblePayload(size, index))
        }
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(
                name = "Root",
                entries = listOf(
                    KdbxEntry(
                        fields = mapOf(KdbxConstants.Fields.TITLE to ProtectedString("E1", isProtected = false)),
                        attachments = attachments
                    )
                )
            )
        )

        val thrown = assertThrows(KdbxAttachmentPoolBudgetExceededException::class.java) {
            KdbxFile.save(ByteArrayOutputStream(), db, password)
        }
        assertTrue(
            "异常必须给出累计值与上限: ${thrown.message}",
            thrown.message!!.contains("157286400") && thrown.message!!.contains("134217728")
        )
    }
}
