package com.keepasskey.app.security

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [UnlockThrottleIntegrity] 的内存等价实现，仅供 JVM 单元测试使用（ISSUE-P3-54 / ISSUE-P2-45）。
 *
 * 以固定密钥复刻 [AndroidKeystoreUnlockThrottleIntegrity] 的 HMAC 语义；**存在性标记以内存集合
 * 模拟 Keystore 条目**——关键在于它与被保护的 SharedPreferences 互不隶属，因此
 * 「删除三个 prefs 键」不会连带抹掉标记，正是 ISSUE-P2-45 要断言的语义。
 * 生产环境使用 AndroidKeyStore 实现，本类不得绑定生产 DI。
 */
class FakeUnlockThrottleIntegrity : UnlockThrottleIntegrity {

    private val key = SecretKeySpec("test-throttle-integrity-key".toByteArray(), "HmacSHA256")

    private val markers = mutableSetOf<String>()

    /** 测试辅助：模拟 AndroidKeyStore 不可用（标记查询抛异常、MAC 计算失败） */
    var keystoreBroken: Boolean = false

    override fun mac(databaseId: String, record: UnlockThrottleRecord): ByteArray? {
        if (keystoreBroken) return null
        return Mac.getInstance("HmacSHA256").apply { init(key) }
            .doFinal(UnlockThrottleMacPayload.encode(databaseId, record))
    }

    override fun verify(databaseId: String, record: UnlockThrottleRecord, mac: ByteArray?): Boolean {
        if (mac == null) return false
        val expected = mac(databaseId, record) ?: return false
        return MessageDigest.isEqual(expected, mac)
    }

    override fun ensureExistenceMarker(databaseId: String): Boolean {
        if (keystoreBroken) return false
        markers += databaseId
        return true
    }

    override fun existenceMarkerPresent(databaseId: String): Boolean {
        if (keystoreBroken) throw IllegalStateException("AndroidKeyStore 不可用（测试注入）")
        return databaseId in markers
    }

    /** 测试辅助：不经过 [ensureExistenceMarker] 直接置位，用于构造「标记已在案」的前置状态 */
    fun seedMarker(databaseId: String) {
        markers += databaseId
    }

    /** 测试辅助：直接撤销标记，用于构造「老版本安装」状态（仅有 MAC 密钥、无标记条目） */
    fun clearMarker(databaseId: String) {
        markers -= databaseId
    }
}
