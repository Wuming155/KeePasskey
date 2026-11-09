package com.keepasskey.app.autofill

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.security.KeystoreManager
import com.keepasskey.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次锁定态自动填充保存请求的受控临时载荷（`ENG-20261028-21`）。
 *
 * [passwordChars] 以 [CharArray] 承载，**借用语义**：取得方（落地 Activity）负责在用毕的
 * `finally` 中 `fill('0')` 擦除；本存储交付出明文后不再持有任何副本。
 */
class LockedSavePayload internal constructor(
    val username: String,
    val passwordChars: CharArray
) {
    /** 与同仓先例 [ExtractedSaveCredentials.toString] 同口径：只呈现长度，内容一律不物化。 */
    override fun toString(): String =
        "LockedSavePayload(username=<redacted len=${username.length}>, " +
            "password=<redacted len=${passwordChars.size}>)"
}

/**
 * 锁定态保存请求的**受控临时载荷载体**（`ENG-20261028-21`）。
 *
 * ## 要替换的形态
 * 整改前口令以明文 `String` extra 进入 `PendingIntent`：系统持有该请求句柄期间，本应用无法
 * 确定性清除这份副本，也没有该请求的有效期。本载体把口令留在**本应用可控**的存储里，交给
 * 系统的 `PendingIntent` 只剩一次性令牌与调用方归属（见 [LockedSaveRequestPendingIntent]）。
 *
 * ## 生命周期（四类终态都必须落实，缺一即视为未闭环）
 * - **成功**：[take] 取回载荷的同时同步删除记录（`commit()`），落盘成功后由
 *   [com.keepasskey.app.passkey.SaveRequestLedger] 登记令牌已消费；
 * - **取消**：落地 Activity 在未落盘状态下销毁时调用 [discard]，口令副本即刻消失；
 * - **超时**：记录携带封存时刻，超过 [TTL_MS] 后在任一读写点被就地清除；届时旧句柄被点开
 *   只会 fail-closed，不再交付口令；
 * - **进程死亡**：记录以 AndroidKeyStore 密钥封印后落在应用私有 `SharedPreferences`，
 *   新进程仍可在有效期内解封——用户新提交的凭据不因提供者进程被杀而丢失。
 *
 * ## 安全边界（如实声明，不作扩大解释）
 * - 落盘的是 AES-256-GCM **密文**，密钥别名 [PAYLOAD_KEY_ALIAS] 由 AndroidKeyStore 生成且
 *   不可导出（`getEncoded()` 恒为 null，设备侧用例断言）。解封需要本应用私有目录**与**在本
 *   应用进程内调用密钥两者兼备，与「明文随系统句柄长期驻留」不是同一暴露面。
 * - 封印密钥**不绑定用户认证**（`requireUserAuth = false`）：保存流程恰好发生在库锁定、用户
 *   尚未验证的时刻，要求生物识别会让落地页在解锁前根本读不到载荷。该取舍与同步凭据封印
 *   （`SyncCredentialSealer` / `ISSUE-P1-06`）同口径，暴露面由「TTL + 单次消费 + 终态删除」收窄。
 * - AAD 绑定请求令牌：把一条记录改挂到另一个令牌下会因认证标签失配而解封失败；失败一律按
 *   「无载荷」fail-closed，绝不解出一个不属于本请求的口令。
 * - 口令明文只在 [take] 之后短暂存在于 [CharArray]；本类不返回 `String`，也不把任何
 *   口令内容写入日志（仅异常类型）。
 *
 * `context` 为 null 时退化为进程内存语义（仅供纯 JVM 单测注入；生产 DI 恒有真实上下文）。
 */
@Singleton
class LockedSavePayloadStore @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val keystoreManager: KeystoreManager? = null
) {

    /** 仅测试注入：`(aad, plaintext) -> (iv, ciphertext)`；生产走 AndroidKeyStore。 */
    @VisibleForTesting
    internal var sealHook: ((ByteArray, ByteArray) -> Pair<ByteArray, ByteArray>)? =
        null

    /** 仅测试注入：`(aad, iv, ciphertext) -> plaintext`；生产走 AndroidKeyStore。 */
    @VisibleForTesting
    internal var openHook: ((ByteArray, ByteArray, ByteArray) -> ByteArray)? =
        null

    /** 仅测试注入的时钟；生产恒为 null ⇒ 取墙钟。 */
    @VisibleForTesting
    internal var clockHook: (() -> Long)? = null

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val memoryLock = Any()

    /** 无持久层时的内存回退（仅 JVM 单测路径；键与 prefs 同形态，便于同一套断言）。 */
    private val memoryRecords = LinkedHashMap<String, StoredRecord>()

    /**
     * 封存一条保存载荷。**调用方承担 [passwordChars] 的用毕擦除义务**（本方法不擦除借用数组）。
     *
     * @return true = 记录已同步落盘（`commit()`），可在有效期内被 [take] 解封
     */
    fun seal(requestToken: String, username: String, passwordChars: CharArray): Boolean {
        if (requestToken.isBlank() || passwordChars.isEmpty()) return false
        pruneExpired()
        val plaintext = encodePayload(username, passwordChars) ?: return false
        try {
            val sealed = sealBytes(aadOf(requestToken), plaintext) ?: run {
                AppLog.e(TAG, "锁定态保存载荷封存失败，未落任何记录")
                return false
            }
            writeRecord(recordKey(requestToken), sealed.first, sealed.second, nowMillis())
            // 超上限时按封存时刻丢弃最旧记录：被丢弃的请求只会 fail-closed，不影响较新的请求。
            dropOldestBeyondCapacity()
            return true
        } finally {
            plaintext.fill(0)
        }
    }

    /** 该令牌是否仍有未过期、未消费的载荷（不解封、不物化任何明文）。 */
    fun hasPayload(requestToken: String): Boolean {
        if (requestToken.isBlank()) return false
        pruneExpired()
        return readRecord(recordKey(requestToken)) != null
    }

    /** 当前在封记录数（终态断言与容量回归用；不含任何凭据内容）。 */
    fun pendingCount(): Int {
        pruneExpired()
        return recordTimestamps().size
    }

    /**
     * 解封并**同时删除**记录（单次消费）。
     *
     * @return 载荷；令牌无记录、已过期或解封失败均为 null（调用方按 fail-closed 处理，
     *         绝不退化为「空口令保存」）。解封失败时本方法**不删除**记录：瞬时 Keystore
     *         故障不应在存储层直接把用户新提交的凭据销毁，是否丢弃由消费方按其终态决定
     *         （锁定态保存落地页在取不到载荷时按终态 [discard]）。
     */
    fun take(requestToken: String): LockedSavePayload? {
        if (requestToken.isBlank()) return null
        pruneExpired()
        val key = recordKey(requestToken)
        val record = readRecord(key) ?: return null
        val plaintext = openBytes(aadOf(requestToken), record.iv, record.ciphertext) ?: run {
            AppLog.w(TAG, "锁定态保存载荷解封失败，按无载荷处理")
            return null
        }
        try {
            val payload = decodePayload(plaintext) ?: run {
                AppLog.w(TAG, "锁定态保存载荷内容不合法，按无载荷处理")
                return null
            }
            // 先删后交付：删除未确认（commit 失败）也只留下「可被台账单次消费拦住」的旧记录，
            // 不影响本次交付；先交付后删除则会在崩溃窗口里留下可重复解封的记录。
            removeRecord(key)
            return payload
        } finally {
            plaintext.fill(0)
        }
    }

    /** 丢弃一条载荷（取消 / 落地页在未落盘状态下销毁）。幂等。 */
    fun discard(requestToken: String) {
        if (requestToken.isBlank()) return
        removeRecord(recordKey(requestToken))
    }

    /** 清除全部在封载荷（用例收尾与终态收敛用）。 */
    fun discardAll() {
        recordTimestamps().keys.forEach { removeRecord(it) }
    }

    // ---------------------------------------------------------------- 记录读写

    private class StoredRecord(
        val iv: ByteArray,
        val ciphertext: ByteArray,
        val storedAt: Long
    )

    private fun readRecord(key: String): StoredRecord? {
        val p = prefs
        if (p == null) {
            synchronized(memoryLock) { return memoryRecords[key] }
        }
        val iv = p.getString(key + IV_SUFFIX, null) ?: return null
        val ciphertext = p.getString(key + CIPHER_SUFFIX, null) ?: return null
        val storedAt = p.getLong(key + TIME_SUFFIX, 0L)
        if (storedAt == 0L) return null
        return try {
            StoredRecord(
                iv = Base64.getDecoder().decode(iv),
                ciphertext = Base64.getDecoder().decode(ciphertext),
                storedAt = storedAt
            )
        } catch (t: Throwable) {
            // 记录损坏（外部写入 / 截断）按无载荷 fail-closed，不让异常穿透到落地页
            AppLog.w(TAG, "载荷记录损坏，按无载荷处理: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun writeRecord(key: String, iv: ByteArray, ciphertext: ByteArray, storedAt: Long) {
        val p = prefs
        if (p == null) {
            synchronized(memoryLock) {
                memoryRecords[key] = StoredRecord(iv.copyOf(), ciphertext.copyOf(), storedAt)
            }
            return
        }
        // 进程死亡恢复依赖这条记录**已同步落盘**，故一律 commit()，不用 apply() 的异步刷盘。
        p.edit()
            .putString(key + IV_SUFFIX, Base64.getEncoder().encodeToString(iv))
            .putString(key + CIPHER_SUFFIX, Base64.getEncoder().encodeToString(ciphertext))
            .putLong(key + TIME_SUFFIX, storedAt)
            .commit()
    }

    private fun removeRecord(key: String) {
        val p = prefs
        if (p == null) {
            synchronized(memoryLock) { memoryRecords.remove(key) }
            return
        }
        p.edit().remove(key + IV_SUFFIX).remove(key + CIPHER_SUFFIX).remove(key + TIME_SUFFIX).commit()
    }

    /** 记录键 → 封存时刻。内存与 prefs 两条路径共用同一返回形态（键均为不含后缀的 baseKey）。 */
    private fun recordTimestamps(): Map<String, Long> {
        val p = prefs
        if (p == null) {
            synchronized(memoryLock) {
                return memoryRecords.mapValues { it.value.storedAt }
            }
        }
        return p.all
            .filterKeys { it.endsWith(TIME_SUFFIX) }
            .mapNotNull { (key, value) ->
                (value as? Long)?.let { key.removeSuffix(TIME_SUFFIX) to it }
            }
            .toMap()
    }

    private fun pruneExpired() {
        val now = nowMillis()
        for ((key, storedAt) in recordTimestamps()) {
            if (now < storedAt || now - storedAt >= TTL_MS) removeRecord(key)
        }
    }

    private fun dropOldestBeyondCapacity() {
        val timestamps = recordTimestamps()
        val excess = timestamps.size - MAX_RECORDS
        if (excess <= 0) return
        timestamps.entries.sortedBy { it.value }.take(excess).forEach { removeRecord(it.key) }
    }

    // ---------------------------------------------------------------- 加解密

    private fun sealBytes(aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray>? = try {
        sealHook?.invoke(aad, plaintext) ?: run {
            val key = payloadKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            Pair(cipher.iv.copyOf(), cipher.doFinal(plaintext))
        }
    } catch (t: Throwable) {
        // 只记异常类型：封印失败不得把任何明文片段带进日志
        AppLog.w(TAG, "载荷封印调用失败: ${t.javaClass.simpleName}")
        null
    }

    private fun openBytes(aad: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        openHook?.invoke(aad, iv, ciphertext) ?: run {
            val key = payloadKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "载荷解封调用失败: ${t.javaClass.simpleName}")
        null
    }

    private fun payloadKey() = keystoreManager?.getOrCreateKey(
        PAYLOAD_KEY_ALIAS,
        requireUserAuth = false,
        invalidateOnBiometricEnrollment = false
    )

    // ---------------------------------------------------------------- 载荷编码

    /** `[int 用户名字节数][用户名 UTF-8][口令 UTF-8]`；口令数组按借用语义读取，不擦除。 */
    private fun encodePayload(username: String, passwordChars: CharArray): ByteArray? = try {
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val passwordBytes = passwordChars.toUtf8Bytes()
        try {
            val buffer = ByteArrayOutputStream(userBytes.size + passwordBytes.size + Int.SIZE_BYTES)
            DataOutputStream(buffer).use { out ->
                out.writeInt(userBytes.size)
                out.write(userBytes)
                out.write(passwordBytes)
            }
            buffer.toByteArray()
        } finally {
            userBytes.fill(0)
            passwordBytes.fill(0)
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "载荷编码失败: ${t.javaClass.simpleName}")
        null
    }

    private fun decodePayload(plaintext: ByteArray): LockedSavePayload? {
        val buffer = ByteBuffer.wrap(plaintext)
        if (buffer.remaining() < Int.SIZE_BYTES) return null
        val userLength = buffer.getInt()
        if (userLength < 0 || userLength > buffer.remaining()) return null
        val userBytes = ByteArray(userLength)
        buffer.get(userBytes)
        val username = String(userBytes, Charsets.UTF_8)
        userBytes.fill(0)
        val passwordBytes = ByteArray(buffer.remaining())
        buffer.get(passwordBytes)
        return try {
            LockedSavePayload(username, passwordBytes.toUtf8Chars())
        } finally {
            passwordBytes.fill(0)
        }
    }

    /** [CharArray] → UTF-8 字节（CharBuffer 直转，不经 `String`） */
    private fun CharArray.toUtf8Bytes(): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(this))
        return try {
            val bytes = ByteArray(bb.remaining())
            bb.get(bytes)
            bytes
        } finally {
            if (bb.hasArray()) bb.array().fill(0)
        }
    }

    /** UTF-8 字节 → [CharArray]（ByteBuffer 直解码，不经 `String`） */
    private fun ByteArray.toUtf8Chars(): CharArray {
        val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(this))
        return try {
            val chars = CharArray(cb.remaining())
            cb.get(chars)
            chars
        } finally {
            if (cb.hasArray()) cb.array().fill('0')
        }
    }

    /**
     * 记录键 = 令牌摘要。键可由本应用任意重建，但**不含令牌原文**，故读私有目录也拿不到
     * 「哪个请求对应哪条记录」的对应关系（解封仍需 AAD 中的令牌原文）。
     */
    private fun recordKey(requestToken: String): String =
        KEY_PREFIX + MessageDigest.getInstance("SHA-256")
            .digest(requestToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aadOf(requestToken: String): ByteArray =
        requestToken.toByteArray(Charsets.UTF_8)

    private fun nowMillis(): Long = clockHook?.invoke() ?: System.currentTimeMillis()

    internal companion object {
        private const val TAG = "LockedSavePayload"

        /** 记录所在的应用私有偏好文件 */
        internal const val PREFS_NAME = "autofill_locked_save_payloads"

        /** 口令封印密钥别名（AndroidKeyStore，不可导出，不绑定用户认证） */
        internal const val PAYLOAD_KEY_ALIAS = "com.keepasskey.autofill_locked_save_payload_key"

        /**
         * 载荷有效期。与 [com.keepasskey.app.passkey.SaveRequestLedger] 的 15 分钟单次消费窗口
         * **刻意取短**：台账仍在防重放而载荷已过期的请求只会 fail-closed；反向分叉
         * （载荷比台账活得久）才会留下可重复解封的窗口。
         */
        internal const val TTL_MS = 10 * 60 * 1000L

        /** 同时在封的请求上限：超出按封存时刻丢弃最旧记录，防止长期堆积。 */
        internal const val MAX_RECORDS = 8

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        const val KEY_PREFIX = "rec_"
        const val IV_SUFFIX = "_iv"
        const val CIPHER_SUFFIX = "_cipher"
        const val TIME_SUFFIX = "_at"
    }
}
