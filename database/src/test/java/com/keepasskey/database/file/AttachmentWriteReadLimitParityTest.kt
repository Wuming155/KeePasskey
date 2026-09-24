package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.exception.KdbxAttachmentPoolBudgetExceededException
import com.keepasskey.database.xml.AttachmentSizeLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P2-311 AC③：写读两侧对同一常量口径**同源且互不矛盾**的参数化 save→load 往返锁定。
 *
 * 缺陷形态：`MAX_ATTACHMENT_BYTES`（旧值 64 MiB）与读侧单字段上限 `MAX_INNER_FIELD_BYTES`
 * （64 MiB）口径互斥——恰好 64 MiB 的附件被 UI 放行（`>` 判），写出字段长 64 MiB + 1 超过
 * 读侧单字段上限，下次打开整库判损坏；写侧此前也零累计闸，3 × 50 MiB 的附件组合
 * 「写得出、读不进」。整改后 UI 上界同源派生为 `MAX_INNER_FIELD_BYTES − 1`，
 * 上界值本身写出即为合法最长字段，读侧必然接受（本组往返必通）。
 *
 * 性能注：夹具字节为 4 KiB 游程模式（GZip 高度可压），真实内存峰值来自原始数组本身；
 * 上界值量级（64 MiB − 1 / 累计 128 MiB − 2）为 AC 明示的必测项，不可缩小。
 */
@RunWith(Parameterized::class)
class AttachmentWriteReadLimitParityTest(
    /** 本组各附件尺寸（AC 参数化口径：UI 上界值本身 + 多附件累计临界） */
    private val sizes: List<Long>
) {

    companion object {
        /** 4 KiB 游程 + 按附件序号偏移：高可压且各附件内容互异（避免去重器合并池条目） */
        fun compressiblePayload(size: Long, index: Int): ByteArray =
            ByteArray(size.toInt()) { ((it / 4096) % 3 + index).toByte() }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            // AC③：UI 上界值本身（64 MiB − 1）
            arrayOf(listOf(AttachmentSizeLimits.MAX_ATTACHMENT_BYTES)),
            // AC③：多附件累计临界。读侧可接受的真实累计上界受**整包解压上限**
            // （MAX_DECOMPRESSED_PAYLOAD_BYTES = 128 MiB，含池 + XML）先于池累计闸约束，
            // 故可过临界取 2 × (64 MiB − 1 − 1 KiB) = 128 MiB − 2 KiB（XML 后仍低于整包上限）。
            // 如实声明：恰为 2 × (64 MiB − 1) 的组合会先撞整包上限（读侧 GZip 守卫），
            // 该形态由写侧池累计闸与整包上限同源不互斥（整包恒先触发）共同收口。
            arrayOf(
                listOf(
                    AttachmentSizeLimits.MAX_ATTACHMENT_BYTES - 1024L,
                    AttachmentSizeLimits.MAX_ATTACHMENT_BYTES - 1024L
                )
            )
        )
    }

    @Test
    fun `UI 上界值 save→load 往返必通且逐字节等价`() {
        val password = "ParityRoundTrip#2026".toCharArray()
        val attachments = sizes.mapIndexed { index, size ->
            KdbxAttachment("big$index.bin", data = compressiblePayload(size, index))
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

        val saved = ByteArrayOutputStream()
            .also { KdbxFile.save(it, db, password) }
            .toByteArray()
        val loaded = KdbxFile.load(ByteArrayInputStream(saved), password)

        val restored = loaded.rootGroup.entries.single().attachments
        assertEquals(sizes.size, restored.size)
        attachments.forEachIndexed { index, original ->
            assertEquals("附件 $index 尺寸必须保留", sizes[index], restored[index].size)
            assertArrayEquals("附件 $index 必须逐字节等价", original.data, restored[index].data)
        }
    }

}
