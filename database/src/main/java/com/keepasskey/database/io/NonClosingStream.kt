package com.keepasskey.database.io

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 屏蔽 close 的输入流包装。
 * KDBX 读写管线采用流式级联（GZip/加密/HMAC 块流），级联 close 会一路传导到底层流；
 * KdbxFile 的契约是不关闭调用方传入的原始流，因此用本包装隔断传导。
 */
class NonClosingInputStream(delegate: InputStream) : FilterInputStream(delegate) {

    override fun close() {
        // 故意不关闭底层流：调用方流的生命周期由调用方管理
    }
}

/**
 * 屏蔽 close 的输出流包装（语义同 [NonClosingInputStream]）。
 */
class NonClosingOutputStream(delegate: OutputStream) : FilterOutputStream(delegate) {

    // FilterOutputStream.close() 会先 flush 再关底层流，重写为仅冲刷
    override fun write(data: ByteArray, off: Int, len: Int) {
        out.write(data, off, len)
    }

    override fun close() {
        try {
            flush()
        } catch (_: java.io.IOException) {
            // 底层已关闭时冲刷失败不影响收尾
        }
    }
}
