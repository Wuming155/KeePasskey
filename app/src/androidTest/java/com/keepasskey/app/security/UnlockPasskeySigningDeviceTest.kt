package com.keepasskey.app.security

import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 解锁断言私钥的**设备侧（instrumented）**回归：真实 AndroidKeyStore 下
 * 「生成 → 断言签名 → 验证」必须端到端可用（`ISSUE-P1-09` 撤回项）。
 *
 * ## 为什么必须有这一层
 *
 * 该缺陷在宿主侧表现为**全绿**：断言验证逻辑（challenge / clientDataJSON / rpIdHash /
 * signCount）全是纯 JVM 逻辑，用软件 EC 密钥对驱动恒通过；真正失效的是**密钥形态在真实
 * Keystore 上的可授权性**——旧实现把断言私钥生成为「绑定强生物识别 + 30 秒时间窗」，
 * 而该形态要求「不得传 `CryptoObject` 且须允许回退到非生物识别凭据」
 * （官方指南 `identity/sign-in/biometric-auth` §「Authenticate using either biometric or
 * lock screen credentials」），与本流程「一次认证 + 单 `CryptoObject`（封印密钥）」
 * 互斥 ⇒ 签名恒抛 `UserNotAuthenticatedException` ⇒ 断言门控 fail-closed 拒绝**每一次**
 * 快速解锁（用户可见「快速解锁凭据校验未通过，请使用主密码解锁后重新登记」）。
 * 宿主 JVM 无 AndroidKeyStore，**不可能**覆盖该面（`AGENTS.md` §5）。
 *
 * ## 覆盖与不覆盖（如实声明）
 *
 * - **覆盖**：真实 Keystore 生成的断言私钥 `KeyInfo.isUserAuthenticationRequired == false`；
 *   **无任何生物识别授权**时 `Signature.initSign` + `sign()` 成功；[UnlockPasskeyManager]
 *   的 `enroll → assertUnlock → verifyAndCommit` 全链在真实 Keystore 上通过（含 signCount 提交后
 *   的第二次断言）；登记记录经真实硬件 HMAC 往返且记录公钥真能验证断言签名。
 * - **不覆盖**：`BiometricPrompt` 交互本身（需真实手势 / 已录入生物识别）。本用例刻意
 *   不依赖生物识别，故在**未录入指纹**的模拟器上同样可稳定判别「签名是否可用」。
 */
@RunWith(AndroidJUnit4::class)
class UnlockPasskeySigningDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val keystoreManager = KeystoreManager(context, null)

    private val passkeyManager = UnlockPasskeyManager(InMemoryUnlockPasskeyStore(), keystoreManager)

    /** 测试专用库标识（与生产库 id 不冲突；[UnlockPasskeyManager] 按库派生密钥别名） */
    private val dbId = "__device_test_unlock_passkey__"

    @After
    fun tearDown() {
        passkeyManager.clear(dbId)
    }

    @Test
    fun `真实Keystore下断言签名与验证端到端通过`() {
        assertTrue("登记（删别名 → 重建 → 写登记记录）必须成功", passkeyManager.enroll(dbId))

        // 首次断言：登记刚完成，私钥与登记记录必然一致 ⇒ 验证必须通过
        assertTrue(
            "首次断言必须通过验证（登记记录里的公钥须与当前私钥一致；" +
                "失败即「断言路径重建了私钥 ⇒ 公钥与登记记录脱钩」形态，正是 ISSUE-P1-242）",
            assertAndVerify()
        )
        // signCount 已提交：第二次断言必须同样可用且仍通过严格单调校验
        assertTrue("signCount 提交后第二次断言必须仍通过（严格单调）", assertAndVerify())
    }

    @Test
    fun `断言私钥不要求用户认证且生成不依赖生物识别录入`() {
        val alias = KeystoreManager.BIOMETRIC_KEY_ALIAS + "_passkey_" + dbId
        keystoreManager.deleteKey(alias)
        try {
            val pair = keystoreManager.getOrCreateUnlockPasskeyPair(alias)
            assertNotNull("断言密钥对应可生成（不依赖已录入的生物识别）", pair)

            val keyInfo = KeyFactory.getInstance(pair!!.private.algorithm, KeystoreManager.ANDROID_KEY_STORE)
                .getKeySpec(pair.private, KeyInfo::class.java) as KeyInfo
            assertFalse(
                "断言私钥不得绑定用户认证：绑定后在快速解锁流程下恒不可授权（详见 UnlockPasskeyKeyPolicy）",
                keyInfo.isUserAuthenticationRequired
            )

            val payload = ByteArray(UnlockPasskeyManager.AUTHENTICATOR_DATA_LENGTH)
            val signature = Signature.getInstance("SHA256withECDSA").apply {
                initSign(pair.private)
                update(payload)
            }.sign()
            assertTrue(
                "无任何用户认证授权时签名必须成功（真实 Keystore 实证）",
                Signature.getInstance("SHA256withECDSA").apply {
                    initVerify(pair.public)
                    update(payload)
                }.verify(signature)
            )
        } finally {
            keystoreManager.deleteKey(alias)
        }
    }

    @Test
    fun `登记记录经真实硬件HMAC往返且记录公钥即签名者公钥`() {
        val storage = BiometricCredentialStorage(context, keystoreManager)
        // 本用例必须走**真实**登记存储（含硬件 HMAC 封存），故另建一个以之为存储的管理器
        val manager = UnlockPasskeyManager(storage, keystoreManager)
        try {
            assertTrue("登记必须成功", manager.enroll(dbId))
            val record = storage.getUnlockPasskey(dbId)
            assertNotNull(
                "登记记录必须能经真实 AndroidKeyStore HMAC 校验读回（null = 记录被删或 MAC 校验未通过）",
                record
            )

            val challenge = manager.newChallenge()
            val gate = manager.assertUnlock(dbId, challenge)
            val assertion = (gate as? UnlockPasskeyGate.AssertionReady)?.assertion
            assertNotNull("无生物识别授权时断言签名必须可用（gate=$gate）", assertion)
            val ready = requireNotNull(assertion) { "断言不可用（gate=$gate）" }

            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(record!!.publicKeyB64)))
            val verified = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(ready.authenticatorData)
                update(MessageDigest.getInstance("SHA-256").digest(ready.clientDataJSON))
            }.verify(ready.signature)
            assertTrue(
                "登记记录里的公钥必须能验证本次断言签名（防「记录与私钥脱钩」形态）",
                verified
            )
        } finally {
            storage.clearUnlockPasskey(dbId)
            manager.clear(dbId)
        }
    }

    /** 生成一次断言并验证；返回是否通过（失败细节由断言消息给出） */
    private fun assertAndVerify(): Boolean {
        val challenge = passkeyManager.newChallenge()
        val gate = passkeyManager.assertUnlock(dbId, challenge)
        val assertion = (gate as? UnlockPasskeyGate.AssertionReady)?.assertion
        assertNotNull(
            "无生物识别授权时断言签名也必须可用：gate=$gate。" +
                "返回 SigningFailed 即说明私钥形态要求用户认证（旧实现的 UserNotAuthenticatedException 形态），" +
                "此时每一次快速解锁都会被 fail-closed 拒绝。",
            assertion
        )
        return passkeyManager.verifyAndCommit(dbId, assertion!!, challenge)
    }
}

/**
 * 设备侧内存登记记录实现：宿主源集的 `FakeUnlockPasskeyStore` 不在 `androidTest` 可见，
 * 故此处复刻同一语义（本用例只关心签名与验证链路，不关心记录存储形态）。
 */
private class InMemoryUnlockPasskeyStore : UnlockPasskeyStore {

    private val records = mutableMapOf<String, UnlockPasskeyRecord>()

    override fun saveUnlockPasskey(
        databaseId: String,
        publicKeyB64: String,
        credentialIdB64: String,
        signCount: Int
    ) {
        records[databaseId] = UnlockPasskeyRecord(publicKeyB64, credentialIdB64, signCount)
    }

    override fun getUnlockPasskey(databaseId: String): UnlockPasskeyRecord? = records[databaseId]

    override fun commitSignCount(databaseId: String, newSignCount: Int) {
        records[databaseId] = records[databaseId]?.copy(signCount = newSignCount) ?: return
    }

    override fun clearUnlockPasskey(databaseId: String) {
        records.remove(databaseId)
    }
}
