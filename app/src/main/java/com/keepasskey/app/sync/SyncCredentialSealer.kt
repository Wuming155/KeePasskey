package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.security.KeystoreManager
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 同步凭据的封印 / 解除封印（ISSUE-P3-29：自 `SyncCredentialsStore.kt` 拆出，纯结构性拆分）。
 *
 * Wave 15 整改：凭据读写全链路以 [CharArray] 承载（CharBuffer 直转 UTF-8 字节，全程不经 String），
 * 借用语义由调用方承担用毕清零义务。原实现逐字迁移，行为零变更。
 *
 * 测试注入的加解密钩子由调用方（[SyncCredentialsStore]）以参数传入，故本类不持有可写钩子，
 * 同时也保留了单测替换封印算法的能力。
 */
internal class SyncCredentialSealer(
    private val keystoreManager: KeystoreManager?,
    private val debugLog: DebugLogBuffer?,
    private val keyAlias: String
) {

    /**
     * Wave 15 整改：封印输入以 [CharArray] 承载，经 CharBuffer 直转 UTF-8 字节
     * （对齐 Wave 11 H1 手法），明文字节在封印完成后立即擦除，全程不经 String。
     *
     * ISSUE-P1-06 安全取舍声明（requireUserAuth = false）：
     * 同步凭据封印密钥不绑定用户认证——这是**有意为之的架构决策**：
     * - **必要性**：后台周期同步（WorkManager）与冷启动自动同步需在设备锁屏态执行，
     *   若密钥要求 per-operation 生物认证，则锁屏期间无法解封凭据，同步功能彻底瘫痪；
     * - **风险**：进程内任意代码路径（含被注入的恶意线程）可无认证解封凭据；
     * - **缓解措施**：
     *   1. 凭据解封后以 CharArray 承载，借用语义要求调用方用毕立即 fill('0') 擦除；
     *   2. S3 凭据在同步周期结束后经 `S3SyncProvider.clearCredentials` 显式清零；
     *   3. SigV4 派生链（signingKey/kSecret/kDate/kRegion/kService）全程 finally 擦除；
     *   4. UI 层（CloudSyncScreen ZeroKnowledgeCard）向用户明示此安全取舍；
     * - **替代方案评估**：改为 requireUserAuth=true + 短时授权窗口（如 30s）会导致
     *   后台同步频繁弹出 BiometricPrompt，用户体验不可接受；当前方案在「可用性」与
     *   「安全性」间取得平衡，凭据暴露面已从「String 不可变驻留」收窄至「CharArray 可控生命周期」。
     */
    fun encrypt(
        chars: CharArray,
        customEncryptor: ((ByteArray) -> Pair<ByteArray, ByteArray>)?
    ): Pair<String, String>? {
        if (chars.isEmpty()) return null
        val bytes = chars.toUtf8Bytes()
        return try {
            val (iv, cipherBytes) = customEncryptor?.invoke(bytes) ?: run {
                val km = keystoreManager ?: return null
                // ISSUE-P1-06：requireUserAuth=false 为有意决策（见方法 KDoc 安全取舍声明）
                val key = km.getOrCreateKey(keyAlias, requireUserAuth = false)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                Pair(cipher.iv, cipher.doFinal(bytes))
            }
            Pair(
                Base64.getEncoder().encodeToString(iv),
                Base64.getEncoder().encodeToString(cipherBytes)
            )
        } catch (e: Exception) {
            // P2 整改：原实现 `catch (_: Exception) { null }` 全静默吞掉异常。
            // 密钥失效（用户增删指纹触发 setInvalidatedByBiometricEnrollment）或 Keystore 暂不可用时，
            // 封印失败会一路静默退化，运行期完全无从感知。现显式落调试日志——注意只记异常类型，绝不写明文。
            debugLog?.error(TAG, "同步凭据封印失败，凭据未写入: ${e.javaClass.simpleName}")
            null
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Wave 15 整改：解除封印以 [CharArray] 承载（UTF-8 直解码，全程不经 String），
     * 中间字节用毕立即擦除。调用方对返回数组承担用毕清零义务（借用语义）。
     */
    fun decrypt(
        ivBase64: String?,
        cipherBase64: String?,
        customDecryptor: ((ByteArray, ByteArray) -> ByteArray)?
    ): CharArray? {
        if (ivBase64.isNullOrBlank() || cipherBase64.isNullOrBlank()) return null
        var decryptedBytes: ByteArray? = null
        return try {
            val iv = Base64.getDecoder().decode(ivBase64)
            val cipherBytes = Base64.getDecoder().decode(cipherBase64)
            decryptedBytes = customDecryptor?.invoke(iv, cipherBytes) ?: run {
                val km = keystoreManager ?: return null
                val key = km.getOrCreateKey(keyAlias, requireUserAuth = false)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                cipher.doFinal(cipherBytes)
            }
            decryptedBytes.toUtf8Chars()
        } catch (e: Exception) {
            // P2 整改：同上，解除封印失败不得静默。返回 null 由调用方按 fail-closed 处理，
            // 绝不退化为空字符串被上层误判为「用户主动清空了密码」。
            debugLog?.warn(TAG, "同步凭据解除封印失败: ${e.javaClass.simpleName}")
            null
        } finally {
            decryptedBytes?.fill(0)
        }
    }

    /** CharArray → UTF-8 字节（CharBuffer 直转，缓冲区底层副本尽力擦除，不经 String） */
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

    /** UTF-8 字节 → CharArray（ByteBuffer 直解码，缓冲区底层副本尽力擦除，不经 String） */
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

    private companion object {
        const val TAG = "SyncCredentialSealer"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
