package com.keepasskey.app.passkey

/**
 * 仅服务于 [ByteJsonScanner] 的可增长字节缓冲（避免每字节一次数组拷贝）。
 *
 * **扩容即擦旧缓冲**：`copyOf` 会遗弃原数组，而原数组的前 `size` 字节正是已解码的载荷明文
 * （私钥 / PRF 的 Base64 文本）；只擦最终数组会把旧代次留给 GC（同族缺陷见
 * `WipableByteArrayOutputStream.ensureWipableCapacity`）。
 *
 * [probe] 让契约测试在**每个**缓冲代次分配时就持有引用，从而能直接断言旧代次也已清零；
 * 生产路径传 null。观察者只许保存引用，不得回写或清零。
 */
internal class ByteSink(private val probe: ByteJsonScanProbe? = null) {

    private var buf = ByteArray(INITIAL_CAPACITY).also { probe?.sinkBuffers?.add(it) }
    private var size = 0

    fun add(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    /** 按 UTF-8 写出一个码点（1~4 字节），供 `\uXXXX` 与代理对使用。 */
    fun appendCodePoint(cp: Int) {
        when {
            cp < 0x80 -> add(cp)
            cp < 0x800 -> {
                ensure(2)
                buf[size++] = (0xC0 or (cp shr 6)).toByte()
                buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            }
            cp < 0x10000 -> {
                ensure(3)
                buf[size++] = (0xE0 or (cp shr 12)).toByte()
                buf[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            }
            else -> {
                ensure(4)
                buf[size++] = (0xF0 or (cp shr 18)).toByte()
                buf[size++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                buf[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                buf[size++] = (0x80 or (cp and 0x3F)).toByte()
            }
        }
    }

    /** 交付独立副本（所有者为接收方）；本类保留的工作缓冲由 [wipe] 清零。 */
    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun wipe() {
        buf.fill(0)
        size = 0
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var cap = buf.size * 2
        while (cap < size + extra) cap *= 2
        val previous = buf
        buf = previous.copyOf(cap)
        // 旧代次已把 size 个明文字节复制进新缓冲，自身不再被任何字段引用 ⇒ 交出前清零
        previous.fill(0)
        probe?.sinkBuffers?.add(buf)
    }

    private companion object {
        /** 单个 JSON 字符串值的初始缓冲（超出后按倍增扩容，扩容路径同样擦旧代次） */
        const val INITIAL_CAPACITY = 64
    }
}
