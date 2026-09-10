package com.keepasskey.app.data.importer

/**
 * ISSUE-P3-19：导入框架的结构性异常分型。
 *
 * 说明（与 `ImportContracts.kt` 的关系）：该文件是跨批次共享的**只读契约**，框架侧不得改动其
 * 既有声明；本文件与之同包新增子类型，只做「扩展分型」，不改动任何既有签名与语义。
 *
 * 纪律：这里的 message 仅用于**诊断归类**（日志/单测断言），绝不进入 `KdbxResult.Failure.userMessage`，
 * 也不得携带任何条目字段明文；用户可见文案一律由 [ImportFailureReason] 映射到 `strings.xml`。
 */

/** 文本编码非法（非 UTF-8、带 UTF-16/32 BOM、或含 NUL 的伪文本）。 */
class ImportEncodingException(message: String) : ImportFormatException(message)

/** CSV 表头缺少必需列（`name` 或 `password`），无法安全映射。 */
class ImportRequiredColumnMissingException(message: String) : ImportFormatException(message)

/** 密码库处于锁定/不可写状态，导入被拒绝。 */
class ImportVaultLockedException(message: String) : Exception(message)

/** 落库阶段（写入内存树 + 落盘）失败，含「全部条目均写入失败」的整体失败语义。 */
class ImportPersistException(message: String) : Exception(message)
