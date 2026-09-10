package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁通行密钥门控单测（ISSUE-P1-09）：
 * 「记录被删 / 未登记 → 拒绝快速解锁」的 fail-closed 语义——原兼容通道
 * （未登记即静默放行并后台补登记，删除 3 个 key 即一步绕过反克隆断言）已移除。
 * 硬件签名路径属 Instrumented 范畴，JVM 侧以门控结果分类断言。
 */
class UnlockPasskeyManagerGateTest {

    private fun manager(store: FakeUnlockPasskeyStore): UnlockPasskeyManager =
        UnlockPasskeyManager(store, KeystoreManager(context = null))

    private fun challenge(): ByteArray = ByteArray(UnlockPasskeyManager.CHALLENGE_LENGTH)

    @Test
    fun `登记记录被删后断言拒绝返回 NotEnrolled`() {
        val store = FakeUnlockPasskeyStore()
        store.saveUnlockPasskey("db_personal", "pub", "cred", 3)
        val passkeyManager = manager(store)

        // 模拟攻击者删除登记记录（原实现此路径静默放行 + 后台补登记）
        store.clearUnlockPasskey("db_personal")

        assertEquals(UnlockPasskeyGate.NotEnrolled, passkeyManager.assertUnlock("db_personal", challenge()))
    }

    @Test
    fun `未登记库断言拒绝返回 NotEnrolled`() {
        val passkeyManager = manager(FakeUnlockPasskeyStore())
        assertEquals(UnlockPasskeyGate.NotEnrolled, passkeyManager.assertUnlock("db_other", challenge()))
    }

    @Test
    fun `记录缺失时 verifyAndCommit 一律拒绝`() {
        val passkeyManager = manager(FakeUnlockPasskeyStore())
        val assertion = UnlockPasskeyAssertion(
            authenticatorData = UnlockPasskeyManager.buildAuthenticatorData(UnlockPasskeyManager.RP_ID, 1),
            signature = ByteArray(8),
            newSignCount = 1,
            clientDataJSON = UnlockPasskeyManager.buildClientDataJson(challenge())
        )
        assertFalse(passkeyManager.verifyAndCommit("db_personal", assertion, challenge()))
    }

    @Test
    fun `记录存在但硬件签名不可用时 SigningFailed（fail-closed）`() {
        val store = FakeUnlockPasskeyStore()
        store.saveUnlockPasskey("db_personal", "pub", "cred", 3)
        val passkeyManager = manager(store)
        // JVM 无 AndroidKeyStore：KeystoreManager 返回 null → 不得回退为放行
        assertEquals(UnlockPasskeyGate.SigningFailed, passkeyManager.assertUnlock("db_personal", challenge()))
    }

    @Test
    fun `challenge 长度非法时拒绝签名`() {
        val store = FakeUnlockPasskeyStore()
        store.saveUnlockPasskey("db_personal", "pub", "cred", 0)
        val passkeyManager = manager(store)
        assertEquals(UnlockPasskeyGate.SigningFailed, passkeyManager.assertUnlock("db_personal", ByteArray(8)))
    }

    @Test
    fun `newChallenge 为 32 字节随机值`() {
        val passkeyManager = manager(FakeUnlockPasskeyStore())
        val a = passkeyManager.newChallenge()
        val b = passkeyManager.newChallenge()
        assertEquals(UnlockPasskeyManager.CHALLENGE_LENGTH, a.size)
        assertFalse("两次 challenge 不得相同（一次性随机语义）", a.contentEquals(b))
    }

    @Test
    fun `enroll 写入 signCount 起始为 0 的登记记录`() {
        val store = FakeUnlockPasskeyStore()
        val passkeyManager = UnlockPasskeyManager(store, KeystoreManager(context = null))
        // JVM 无 AndroidKeyStore：enroll 在密钥生成处失败，但不得写入任何记录
        assertFalse(passkeyManager.enroll("db_personal"))
        assertEquals(null, store.getUnlockPasskey("db_personal"))
    }
}
