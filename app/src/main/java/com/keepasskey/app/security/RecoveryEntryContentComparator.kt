package com.keepasskey.app.security

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * 恢复预览中附件逐字节比较的生产预算（两侧合计 256 MiB）。
 *
 * 顶层常量而非类内 `private companion`：协调器要用同一值初始化自己的可调预算，
 * 两处各写一份迟早漂移。
 */
internal const val RECOVERY_ATTACHMENT_BUDGET_BYTES: Long = 256L * 1024 * 1024

internal class RecoveryEntryContentComparator(
    private val maxAttachmentBytes: Long = RECOVERY_ATTACHMENT_BUDGET_BYTES
) {
    private var remainingAttachmentBytes = maxAttachmentBytes

    /**
     * 附件逐字节比较无法完成（预算耗尽 / 读取失败 / 长度虚报）。
     *
     * 独立异常类型而非裸 `IOException`：调用方据此与「凭据不匹配」分型上报，
     * 用户才不会拿着「换凭据」的提示去徒劳重试（`ISSUE-P2-638`）。
     */
    class AttachmentComparisonUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun same(left: KdbxEntry, right: KdbxEntry): Boolean =
        left.fieldsContentEquals(right) &&
            left.customFields.size == right.customFields.size &&
            left.customFields.zip(right.customFields).all { (a, b) ->
                a.key == b.key && a.isProtected == b.isProtected && a.value.contentEquals(b.value)
            } &&
            attachmentsEqual(left.attachments, right.attachments) &&
            left.history.size == right.history.size &&
            left.history.zip(right.history).all { (a, b) -> same(a, b) } &&
            left.tags == right.tags && left.autoType == right.autoType &&
            left.backgroundColor == right.backgroundColor && left.foregroundColor == right.foregroundColor &&
            left.overrideUrl == right.overrideUrl && left.qualityCheck == right.qualityCheck &&
            left.customData == right.customData && left.parentGroupId == right.parentGroupId &&
            left.previousParentGroup == right.previousParentGroup &&
            left.iconId == right.iconId && left.customIconId == right.customIconId &&
            left.times.expiryTime == right.times.expiryTime && left.times.expires == right.times.expires

    private fun attachmentsEqual(left: List<KdbxAttachment>, right: List<KdbxAttachment>): Boolean =
        left.size == right.size && left.zip(right).all { (a, b) ->
            if (a.name != b.name || a.isProtected != b.isProtected || a.size != b.size) false
            else sameAttachmentBytes(a, b)
        }

    private fun sameAttachmentBytes(left: KdbxAttachment, right: KdbxAttachment): Boolean {
        val size = left.size
        if (size < 0 || size > remainingAttachmentBytes / SIDES) {
            throw AttachmentComparisonUnavailable("恢复附件比较超出预算")
        }
        remainingAttachmentBytes -= size * SIDES
        val a = ByteArray(BUFFER_BYTES)
        val b = ByteArray(BUFFER_BYTES)
        try {
            left.openStream().use { first ->
                right.openStream().use { second ->
                    var remaining = size
                    while (remaining > 0) {
                        val count = minOf(remaining, BUFFER_BYTES.toLong()).toInt()
                        readExactly(first, a, count)
                        readExactly(second, b, count)
                        for (index in 0 until count) if (a[index] != b[index]) return false
                        remaining -= count
                    }
                    if (first.read() != -1 || second.read() != -1) {
                        throw AttachmentComparisonUnavailable("恢复附件长度不符")
                    }
                }
            }
            return true
        } catch (unavailable: AttachmentComparisonUnavailable) {
            throw unavailable
        } catch (failure: IOException) {
            // 读取失败与「预算不足」同属「无法确认差异」，统一为可识别类型
            throw AttachmentComparisonUnavailable("恢复附件读取失败", failure)
        } finally {
            a.fill(0)
            b.fill(0)
        }
    }

    private fun readExactly(stream: InputStream, buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val read = stream.read(buffer, offset, count - offset)
            if (read < 0) throw EOFException("恢复附件内容截断")
            if (read == 0) {
                val value = stream.read()
                if (value < 0) throw EOFException("恢复附件内容截断")
                buffer[offset++] = value.toByte()
            } else offset += read
        }
    }

    private companion object {
        const val BUFFER_BYTES = 8192
        const val SIDES = 2
    }
}
