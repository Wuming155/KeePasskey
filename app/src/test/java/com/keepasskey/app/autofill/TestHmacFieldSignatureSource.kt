package com.keepasskey.app.autofill

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 纯 JVM 测试用字段签名密钥来源（ISSUE-P3-46）。
 *
 * 用固定密钥的 `HmacSHA256` 复现生产侧 [KeystoreHmacFieldSignatureSource] 的行为契约
 * （只出不入的 MAC、密钥不可用时返回 null），从而让签名 / 判定 / 仓库三层断言
 * 无需真机 Keystore 即可执行——这正是 [HmacFieldSignatureSource] 抽象的目的。
 */
internal fun testHmacFieldSignatureSource(
    key: ByteArray = ByteArray(32) { it.toByte() }
): HmacFieldSignatureSource = HmacFieldSignatureSource { document ->
    Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(key, "HmacSHA256")) }
        .doFinal(document)
}

/** 模拟「密钥不可用」的来源（fail-closed 路径断言用）。 */
internal val unavailableHmacFieldSignatureSource: HmacFieldSignatureSource =
    HmacFieldSignatureSource { null }
