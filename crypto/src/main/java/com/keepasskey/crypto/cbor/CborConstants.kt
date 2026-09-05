package com.keepasskey.crypto.cbor

/**
 * CBOR (RFC 8949 / RFC 7049) 编解码常量与头部标识定义。
 * 遵循无魔法数字原则，集中管理 Major Type 与 Additional Information。
 */
object CborConstants {
    // 8 种主要数据类型 (Major Types, 占用首字节高 3 位: bit 7..5)
    const val MAJOR_UNSIGNED_INT: Int = 0   // 无符号整数 (0x00 .. 0x17 / 0x18..0x1B)
    const val MAJOR_NEGATIVE_INT: Int = 1   // 负整数 (-1 - n, 0x20 .. 0x37 / 0x38..0x3B)
    const val MAJOR_BYTE_STRING: Int = 2    // 字节数组 (0x40 .. 0x57 / 0x58..0x5B)
    const val MAJOR_TEXT_STRING: Int = 3    // UTF-8 字符串 (0x60 .. 0x77 / 0x78..0x7B)
    const val MAJOR_ARRAY: Int = 4          // 数组 (0x80 .. 0x97 / 0x98..0x9B)
    const val MAJOR_MAP: Int = 5            // 映射表 (0xA0 .. 0xB7 / 0xB8..0xBB)
    const val MAJOR_TAG: Int = 6            // 语义标签 (0xC0 .. 0xD7 / 0xD8..0xDB)
    const val MAJOR_SIMPLE: Int = 7         // 简单值与浮点数 (0xE0 .. 0xF7 / 0xF8..0xFB)

    // Additional Information (占用首字节低 5 位: bit 4..0)
    const val AI_DIRECT_MAX: Long = 23L     // 直接编码在低 5 位的最大无符号值
    const val AI_ONE_BYTE: Int = 24         // 后续紧跟 1 字节附加参数 (uint8)
    const val AI_TWO_BYTES: Int = 25        // 后续紧跟 2 字节大端附加参数 (uint16)
    const val AI_FOUR_BYTES: Int = 26       // 后续紧跟 4 字节大端附加参数 (uint32)
    const val AI_EIGHT_BYTES: Int = 27      // 后续紧跟 8 字节大端附加参数 (uint64)

    // 数值范围门槛
    const val MASK_ONE_BYTE: Long = 0xFFL
    const val MASK_TWO_BYTES: Long = 0xFFFFL
    const val MASK_FOUR_BYTES: Long = 0xFFFF_FFFFL

    // 常用 Simple Values
    const val SIMPLE_FALSE: Int = 20
    const val SIMPLE_TRUE: Int = 21
    const val SIMPLE_NULL: Int = 22
}
