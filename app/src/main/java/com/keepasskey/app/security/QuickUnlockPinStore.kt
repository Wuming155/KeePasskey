package com.keepasskey.app.security

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QuickUnlock PIN 真实校验与主凭据封印存储（H2 整改，对齐 KP2A QuickUnlock 思路）。
 *
 * 安全设计：
 * 1. PIN 校验因子：随机盐 + PBKDF2-HMAC-SHA256（120,000 次迭代）派生校验哈希，PIN 明文绝不落盘；
 * 2. 主凭据封印：主密码字节数组经 Android Keystore AES-256-GCM 硬件密钥加密后落盘——
 *    仅凭存储文件无法离线爆破（硬件密钥不可导出），PIN 校验器仅是前置门槛；
 * 3. 错误 PIN 在校验器比对阶段即被拒绝，主凭据永不解封；
 * 4. 解封成功后主密码以 CharArray 交付调用方，用毕由调用方显式清零。
 */
@Singleton
class QuickUnlockPinStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager? = null
) {
    // 供 JVM 单元测试注入模拟封印/解封（真实路径走 Keystore 硬件密钥）
    var customSealer: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null
    var customUnsealer: ((ByteArray, ByteArray) -> ByteArray)? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 是否已登记 PIN 校验因子（用户至少完成过一次 PIN 设置） */
    fun hasEnrollment(databaseId: String): Boolean =
        prefs.contains(verifierKey(databaseId)) && prefs.contains(saltKey(databaseId))

    /** 是否已封印主凭据（PIN 校验通过后可真正解封密码库） */
    fun hasBoundCredential(databaseId: String): Boolean =
        hasEnrollment(databaseId) && prefs.contains(credCipherKey(databaseId))

    /**
     * 登记 PIN 校验因子（首次使用 QuickUnlock 时调用）。
     * 仅登记校验器，不解锁密码库；主凭据须待一次完整主密码解锁成功后经 [bindCredential] 封印。
     */
    fun enrollPin(databaseId: String, pin: CharArray) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val verifier = derivePinVerifier(pin, salt)
        prefs.edit()
            .putString(saltKey(databaseId), Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(verifierKey(databaseId), Base64.encodeToString(verifier, Base64.NO_WRAP))
            .apply()
    }

    /**
     * 完整主密码解锁成功后，将主密码封印至 QuickUnlock 存储中（Keystore AES-256-GCM）。
     * 调用方须保证此前已通过 [enrollPin] 登记校验因子。
     */
    fun bindCredential(databaseId: String, masterPassword: CharArray) {
        require(hasEnrollment(databaseId)) { "QuickUnlock PIN 未登记，禁止封印主凭据" }
        val bytes = String(masterPassword).toByteArray(StandardCharsets.UTF_8)
        try {
            val (iv, cipherBytes) = seal(bytes)
            prefs.edit()
                .putString(credIvKey(databaseId), Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(credCipherKey(databaseId), Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
                .apply()
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * PIN 校验 + 主凭据解封。
     * 错误 PIN 返回 null；成功返回主密码 CharArray，调用方用毕必须显式清零。
     * 观察 2 整改：连续失败达 [MAX_FAILURES_BEFORE_LOCKOUT] 次后触发指数退避熔断
     * （4 位 PIN 空间仅 10⁴，无熔断时可被本地高频穷举），锁定期间直接返回 null。
     */
    fun unlockWithPin(databaseId: String, pin: CharArray): CharArray? {
        if (!hasBoundCredential(databaseId)) return null
        if (getRemainingLockoutMs(databaseId) > 0) return null
        val salt = runCatching {
            Base64.decode(prefs.getString(saltKey(databaseId), null), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        val expectedVerifier = runCatching {
            Base64.decode(prefs.getString(verifierKey(databaseId), null), Base64.NO_WRAP)
        }.getOrNull() ?: return null

        // 常量时间比较 PBKDF2 派生校验器，杜绝时序侧信道
        val candidate = derivePinVerifier(pin, salt)
        if (!MessageDigest.isEqual(expectedVerifier, candidate)) {
            recordPinFailure(databaseId)
            return null
        }
        clearPinFailures(databaseId)

        val iv = runCatching {
            Base64.decode(prefs.getString(credIvKey(databaseId), null), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        val cipherBytes = runCatching {
            Base64.decode(prefs.getString(credCipherKey(databaseId), null), Base64.NO_WRAP)
        }.getOrNull() ?: return null

        val plainBytes = try {
            unseal(iv, cipherBytes)
        } catch (_: Exception) {
            return null
        }
        return try {
            String(plainBytes, StandardCharsets.UTF_8).toCharArray()
        } finally {
            plainBytes.fill(0)
        }
    }

    /** 当前剩余锁定毫秒数（未锁定时为 0）。调用方可在校验前预检以给出可读的剩余等待提示。 */
    fun getRemainingLockoutMs(databaseId: String): Long {
        val until = prefs.getLong(lockUntilKey(databaseId), 0L)
        val remaining = until - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    /** 记录一次 PIN 校验失败；连续失败达阈值后按指数退避设定锁定截止时间 */
    private fun recordPinFailure(databaseId: String) {
        val failures = prefs.getInt(failCountKey(databaseId), 0) + 1
        val editor = prefs.edit().putInt(failCountKey(databaseId), failures)
        val lockMs = computeLockoutMs(failures)
        if (lockMs > 0) {
            editor.putLong(lockUntilKey(databaseId), System.currentTimeMillis() + lockMs)
        }
        editor.apply()
    }

    /** 校验成功后清零失败计数与锁定状态 */
    private fun clearPinFailures(databaseId: String) {
        prefs.edit()
            .remove(failCountKey(databaseId))
            .remove(lockUntilKey(databaseId))
            .apply()
    }

    /** 清除特定数据库的 PIN 与封印凭据 */
    fun clearCredential(databaseId: String) {
        prefs.edit()
            .remove(saltKey(databaseId))
            .remove(verifierKey(databaseId))
            .remove(credIvKey(databaseId))
            .remove(credCipherKey(databaseId))
            .remove(failCountKey(databaseId))
            .remove(lockUntilKey(databaseId))
            .apply()
    }

    /** 清除全部 QuickUnlock 数据 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** PBKDF2-HMAC-SHA256 派生 PIN 校验器（高迭代次数抑制设备本地爆破） */
    private fun derivePinVerifier(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, VERIFIER_BITS)
        return try {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun seal(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        customSealer?.let { return it(plaintext) }
        val km = keystoreManager ?: error("KeystoreManager 未注入")
        val cipher = km.initEncryptCipher(KeystoreManager.QUICK_UNLOCK_KEY_ALIAS)
        return Pair(cipher.iv, km.encryptData(cipher, plaintext))
    }

    private fun unseal(iv: ByteArray, cipherBytes: ByteArray): ByteArray {
        customUnsealer?.let { return it(iv, cipherBytes) }
        val km = keystoreManager ?: error("KeystoreManager 未注入")
        val cipher = km.initDecryptCipher(iv, KeystoreManager.QUICK_UNLOCK_KEY_ALIAS)
        return km.decryptData(cipher, cipherBytes)
    }

    private fun saltKey(databaseId: String) = "${databaseId}_pin_salt"
    private fun verifierKey(databaseId: String) = "${databaseId}_pin_verifier"
    private fun credIvKey(databaseId: String) = "${databaseId}_cred_iv"
    private fun credCipherKey(databaseId: String) = "${databaseId}_cred_cipher"
    private fun failCountKey(databaseId: String) = "${databaseId}_pin_fail_count"
    private fun lockUntilKey(databaseId: String) = "${databaseId}_pin_lock_until"

    companion object {
        private const val PREFS_NAME = "com.keepasskey.quick_unlock_pin"
        private const val SALT_LENGTH_BYTES = 16
        private const val PBKDF2_ITERATIONS = 120_000
        private const val VERIFIER_BITS = 256

        /** 触发熔断前允许的连续失败次数 */
        private const val MAX_FAILURES_BEFORE_LOCKOUT = 5

        /** 首次熔断时长（毫秒），此后按指数退避翻倍 */
        private const val BASE_LOCKOUT_MS = 30_000L

        /** 熔断时长上限（毫秒） */
        private const val MAX_LOCKOUT_MS = 15 * 60_000L

        /**
         * 依连续失败次数计算熔断时长：未达阈值返回 0；达到阈值后按指数退避
         * （30s → 1min → 2min → …），上限 15 分钟；移位封顶防溢出。
         */
        fun computeLockoutMs(consecutiveFailures: Int): Long {
            if (consecutiveFailures < MAX_FAILURES_BEFORE_LOCKOUT) return 0L
            val shift = (consecutiveFailures - MAX_FAILURES_BEFORE_LOCKOUT).coerceAtMost(10)
            return (BASE_LOCKOUT_MS shl shift).coerceAtMost(MAX_LOCKOUT_MS)
        }
    }
}
