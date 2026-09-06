package com.keepasskey.database.file

import com.keepasskey.database.exception.KdbxCorruptFileException
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 解压输出尺寸护栏（Wave 12 解析炸弹防线）。
 *
 * 恶意 .kdbx 可携带高压缩比 payload（如数 MB 密文解压出数 GB 明文）造成内存/时间耗尽；
 * 本流包装在 [GZIPInputStream]（或未压缩载荷）之后，对解压输出做累计字节计数，
 * 超出 [maxBytes] 立即抛出 [KdbxCorruptFileException] 中止解析。
 */
internal class SizeBoundedInputStream(
    delegate: InputStream,
    private val maxBytes: Long
) : FilterInputStream(delegate) {

    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            countAndCheck(1L)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            countAndCheck(n.toLong())
        }
        return n
    }

    private fun countAndCheck(n: Long) {
        bytesRead += n
        if (bytesRead > maxBytes) {
            throw KdbxCorruptFileException("解压输出超出尺寸安全上限（$maxBytes 字节），疑似 GZip 解压炸弹")
        }
    }
}
