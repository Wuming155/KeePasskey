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
 *
 * ENG-20261028-30：预算按**总消费量**计费，`skip` 跳过的字节同样是解压输出的一部分，
 * 不得委派给底层流后免计费——否则消费方可用 skip 绕过整包上限。
 */
internal class SizeBoundedInputStream(
    delegate: InputStream,
    private val maxBytes: Long
) : FilterInputStream(delegate) {

    private var consumedBytes = 0L

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

    /**
     * 按实际跳过量计费（[InputStream.skip] 契约：非正值返回 0，EOF 返回 0 且不抛异常）。
     *
     * 请求量收窄到「用尽预算后再多 1 字节」：预算内的 skip 请求原样下发，恰好够越界的请求
     * 也能立即判越界，避免委派流先跳过远超预算的字节、事后才由计数发现。
     */
    override fun skip(n: Long): Long {
        if (n <= 0L) return 0L
        val request = minOf(n, maxBytes - consumedBytes + 1)
        val skipped = super.skip(request)
        if (skipped > 0L) {
            countAndCheck(skipped)
        }
        return skipped
    }

    private fun countAndCheck(n: Long) {
        consumedBytes += n
        if (consumedBytes > maxBytes) {
            throw KdbxCorruptFileException("解压输出超出尺寸安全上限（$maxBytes 字节），疑似 GZip 解压炸弹")
        }
    }
}
