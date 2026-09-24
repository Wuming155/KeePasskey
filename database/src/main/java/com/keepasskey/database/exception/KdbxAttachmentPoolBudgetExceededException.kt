package com.keepasskey.database.exception

import java.io.IOException

/**
 * ISSUE-P2-311 AC②：保存前判得**二进制池累计字节**超过读侧同一单值
 * （[com.keepasskey.database.file.InnerHeader.MAX_BINARY_POOL_TOTAL_BYTES]）——
 * 照写必然产出「本设备写得出、读不进」的库（下次打开即被读侧累计闸判损坏），故保存 fail-closed。
 * 写侧类型化异常（定位同 [KdbxAttachmentSpillMissingException]）：库的既有内容完好，
 * 移除超限附件后重试保存即可。
 */
class KdbxAttachmentPoolBudgetExceededException(message: String) : IOException(message)
