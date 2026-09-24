package com.keepasskey.database.exception

import java.io.IOException

/**
 * ISSUE-P2-310：保存前校验发现**落盘附件**的字节量与解析期登记不一致（典型为附件
 * `cacheDir` 落盘缓存被系统回收 / 提前清理，存储按 fail-open 契约返回空流）。
 *
 * 这是**写侧**类型化异常：此刻保存必须整体失败（fail-closed），绝不允许产出
 * 「字段头声明 N 字节、实际写出 0 字节」的自相矛盾内层头——那会使整库下次打开判
 * [KdbxCorruptFileException]，且默认 `.bak` 仅一代、关备份即不可恢复。
 * 与读侧 [KdbxCorruptFileException]（文件本身损坏）语义相对：本异常代表**本地环境**
 * 丢失了附件字节，库内容（XML 与其余二进制）完好——附件缓存恢复（重新解锁重建）后
 * 重试保存，或重新添加该附件即可。
 */
class KdbxAttachmentSpillMissingException(message: String) : IOException(message)
