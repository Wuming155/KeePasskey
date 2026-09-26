package com.keepasskey.app.sync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKeyFactory

/**
 * AndroidKeyStore 消费方 · **设备侧真机验证**（ISSUE-P1-06；ISSUE-P3-326 / P3-328 收窄）。
 *
 * ## 为什么必须真机
 *
 * 这里的安全主张落在「**AndroidKeyStore 的真实行为**」上，宿主 JVM 用假实现只能验证代码路径：
 *
 * 1. [SyncCredentialSealer] + [KeystoreManager]：云同步凭据的 AES-GCM 封印。ISSUE-P1-06 明示
 *    「不绑定用户认证」是有意取舍——该取舍**只有**在真实密钥规格（`KeyInfo`）上才可核验；
 *    而 GCM 认证标签的拒收行为取决于平台 Keystore 的实现。
 *
 * 机型差异只体现在**落位等级**（TEE / StrongBox / 软件），不体现在行为上：故本用例不断言
 * `securityLevel` 的具体取值（LineageOS 等 ROM 可能整体为 SOFTWARE），仅如实上报到日志。
 *
 * 收窄留痕（ISSUE-P3-326 / P3-328，2026-09-25 用户裁决）：原「防回滚状态 MAC」两例与
 * 「字段签名密钥」一例随对应生产层移除而删除（登记于批次 §2.4）。
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretGuardsDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 同步凭据封印在真机往返且密文与IV篡改必拒() {
        val sealer = SyncCredentialSealer(
            KeystoreManager(context, DebugLogBuffer()),
            DebugLogBuffer(),
            SyncCredentialsStore.SYNC_KEY_ALIAS
        )
        val password = "Seal-测试-口令!".toCharArray()
        val sealed = requireNotNull(
            sealer.encrypt(password, null)
        ) {
            "真机上封印失败（返回 null）＝ 云同步凭据整体无法保存；而密钥不要求用户认证的前提" +
                "正是锁屏态后台可用（ISSUE-P1-06 的既定取舍）"
        }
        val (ivBase64, cipherBase64) = sealed
        val restored = requireNotNull(sealer.decrypt(ivBase64, cipherBase64, null)) { "同一密钥必须能解封" }
        try {
            assertArrayEquals("封印往返必须还原原文", password, restored)

            val cipherBytes = Base64.getDecoder().decode(cipherBase64)
            val flippedCipher = cipherBytes.copyOf().apply { this[lastIndex] = (this[lastIndex] + 1).toByte() }
            assertNull(
                "GCM 认证标签被破坏必须整体拒绝，绝不允许返回错误明文",
                sealer.decrypt(ivBase64, Base64.getEncoder().encodeToString(flippedCipher), null)
            )
            val otherSeal = sealer.encrypt("x".toCharArray(), null)
            assertNull(
                "IV 与密文不匹配必须拒绝（fail-closed，不得退化为空串）",
                sealer.decrypt(otherSeal?.first, cipherBase64, null)
            )
            assertNull("空口令不得被封印（避免「已存凭据」的假象）", sealer.encrypt(CharArray(0), null))
        } finally {
            Arrays.fill(password, '\u0000')
            restored.fill('\u0000')
        }
    }

    @Test
    fun 封印密钥规格在真机可核验_不绑定用户认证() {
        // 先确保密钥已由生产路径生成
        val alias = SyncCredentialsStore.SYNC_KEY_ALIAS
        val key = KeystoreManager(context, DebugLogBuffer()).getOrCreateKey(alias, requireUserAuth = false)
        val info = SecretKeyFactory.getInstance(KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertFalse(
            "封印密钥若要求用户认证，锁屏期间无法解封凭据、后台同步整体瘫痪——ISSUE-P1-06 的取舍" +
                "必须在真实密钥规格上成立，而不只是写在 KDoc 里",
            info.isUserAuthenticationRequired
        )
        assertEquals("封印密钥长度应为 256 位", KEY_SIZE_BITS, info.keySize)
        println(
            "KEYSTORE-GUARD|alias=$alias securityLevel=${info.securityLevel} " +
                "authRequired=${info.isUserAuthenticationRequired} api=${Build.VERSION.SDK_INT}"
        )
        assertAliasNonExportable(alias)
    }

    /**
     * 断言别名密钥**不可导出**：AndroidKeyStore 句柄的 [javax.crypto.SecretKey.getEncoded]
     * 恒为 `null` 或直接抛异常（两种形态都属官方语义的「不可导出」）。宿主 JVM 的假实现
     * 会返回真实字节，因此这条断言在设备侧才有意义。
     */
    private fun assertAliasNonExportable(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        assertTrue("别名 $alias 的密钥必须真实落在 AndroidKeyStore", keyStore.containsAlias(alias))
        val entry = keyStore.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry
        val encoded = runCatching { requireNotNull(entry).secretKey.encoded }
        assertTrue(
            "密钥可导出＝拿到私有目录即可离线枚举签名（$alias，实际 ${encoded.getOrNull()?.size} 字节)",
            encoded.getOrNull() == null
        )
    }

    private companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM_AES = "AES"
        private const val KEY_SIZE_BITS = 256
    }
}
