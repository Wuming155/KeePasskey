package com.keepasskey.app.autofill

/**
 * 字段签名密钥来源抽象（ISSUE-P3-46）。
 *
 * 把「用什么密钥给字段签名原文做 MAC」这一件事从 [AutofillFieldSignature] 的纯函数逻辑中
 * 抽离出来，使签名算法可被纯 JVM 单测以**注入测试密钥**的方式断言（无需真机 Keystore），
 * 生产侧则注入 [KeystoreHmacFieldSignatureSource]（Android Keystore 内不可导出密钥）。
 *
 * 契约：
 * - **绝不返回密钥材料**：实现只暴露 MAC 结果，调用方无法取得密钥本身；
 * - **fail-closed**：密钥不可用（未注入 / Keystore 异常 / 纯 JVM 环境）时返回 **null**，
 *   由 [AutofillFieldSignature.of] 转为 `null` 签名，最终由
 *   [AutofillFieldBlocklistStore.isBlocked] 按「视为已屏蔽」保守处理——宁可少填，不可误填。
 */
fun interface HmacFieldSignatureSource {

    /**
     * 计算 `HMAC-SHA256(key, document)`。
     *
     * @param document 待签名的规范化原文（UTF-8 序列）
     * @return 32 字节 MAC；密钥不可用时返回 null（调用方须 fail-closed）
     */
    fun hmacSha256(document: ByteArray): ByteArray?
}
