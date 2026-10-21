package com.keepasskey.app.security

import com.keepasskey.core.model.KdbxAttachment
import com.keepasskey.core.model.KdbxEntry
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal class RecoveryEntryContentComparator(
    private val maxAttachmentBytes: Long = DEFAULT_ATTACHMENT_BUDGET
) {
    private var remainingAttachmentBytes = maxAttachmentBytes

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
            throw IOException("恢复附件比较超出预算")
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
                    if (first.read() != -1 || second.read() != -1) throw IOException("恢复附件长度不符")
                }
            }
            return true
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
        const val DEFAULT_ATTACHMENT_BUDGET = 256L * 1024 * 1024
    }
}
