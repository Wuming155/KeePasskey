package com.keepasskey.app.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.keepasskey.app.data.logger.DebugLogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥文件的**应用私有目录收编副本**（ISSUE-P3-448，§411 实装）。
 *
 * 语义（用户裁决）：「导入密钥文件」= 把解锁时所选（记忆）的密钥文件内容复制进应用私有目录，
 * 此后解锁链**优先消费本副本**——不再依赖 SAF 持久化读授权（授权失效不再阻断解锁），
 * 对比「记住密钥文件位置」（仅记 SAF Uri）的增强收编。
 *
 * **加密存储口径**（AC① 裁决）：密钥文件字节即密钥材料，绝不明文落盘——以专用 Keystore
 * AES-256-GCM 密钥（[KEY_ALIAS]，`requireUserAuth = false`）整载荷加密后写入
 * `filesDir/keyfiles/<dbId>.kfc`。非认证密钥的安全取舍与 `SyncCredentialSealer` 同判据：
 * 副本必须在解锁页（未认证态）自动可用；写入为临时文件 + 原子改名（工程规则「原子写盘」）。
 *
 * 载荷布局（加密前明文）：`[2 字节显示名长度][显示名 UTF-8][密钥文件字节]`——
 * 显示名是非密钥元数据，随载荷一并加密可让「副本尚存而 Uri 记忆丢失」的孤儿副本
 * 仍能正确回显文件名。读写全程 `ByteArray`，调用方对 [load] 返回的字节承担清零义务。
 *
 * 清除时机（AC②）：解绑 / 换绑（`SettingsMasterKeyChangeController`）、偏好关闭、
 * 解锁成功但未使用密钥文件（`KeyFileSessionCoordinator`）、删除库（`VaultLifecycleCoordinator`）。
 */
@Singleton
class KeyFileVaultCopyStore @Inject constructor(
    // 允许为 null 仅用于单测注入；生产 DI 注入 @ApplicationContext
    @ApplicationContext private val context: Context?,
    // 允许为 null 仅用于单测手动构造；生产 DI 注入
    private val keystoreManager: KeystoreManager?,
    private val debugLog: DebugLogBuffer? = null
) {

    /**
     * 单测注入的落盘根目录覆盖（生产恒 null → `filesDir/keyfiles`）。
     * 不走构造参数：Hilt 不接受无绑定的 `File?` 依赖（MissingBinding）。
     */
    @VisibleForTesting
    internal var baseDirOverride: File? = null

    /** 收编副本（解密产物）：字节（调用方用毕清零）+ 非敏感显示名 */
    data class StoredCopy(val bytes: ByteArray, val displayName: String)

    /**
     * 测试注入的加密钩子（JVM 单测无 Keystore）：`明文 → (iv, 密文)`。
     * 生产恒 null → 走 Keystore 真实加解密（与 [SyncCredentialSealer] 的 customEncryptor 同范式）。
     */
    @VisibleForTesting
    internal var encryptHook: ((ByteArray) -> Pair<ByteArray, ByteArray>)? = null

    /** 测试注入的解密钩子：`(iv, 密文) → 明文`。生产恒 null。 */
    @VisibleForTesting
    internal var decryptHook: ((ByteArray, ByteArray) -> ByteArray)? = null

    private val baseDir: File?
        get() = baseDirOverride
            ?: context?.filesDir?.let { File(it, DIR_NAME) }

    /** 收编副本：加密写盘（原子改名）。失败显式留痕并返回 false（绝不静默当成功）。 */
    fun save(databaseId: String, bytes: ByteArray, displayName: String): Boolean {
        if (databaseId.isBlank() || bytes.isEmpty()) return false
        val dir = baseDir ?: return false
        val payload = withDisplayName(displayName, bytes)
        try {
            val (iv, ciphertext) = encryptHook?.invoke(payload) ?: sealWithKeystore(payload)
                ?: return false
            if (!dir.exists()) dir.mkdirs()
            val target = fileFor(dir, databaseId)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { os ->
                os.write(iv)
                os.write(ciphertext)
                os.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                debugLog?.warn(TAG, "副本原子改名失败")
                return false
            }
            return true
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本写入失败: ${e.javaClass.simpleName}")
            return false
        } finally {
            payload.fill(0)
        }
    }

    /** 读取并解密封印副本；缺失 / 损坏返回 null（损坏就地删除，避免反复失败）。 */
    fun load(databaseId: String): StoredCopy? {
        if (databaseId.isBlank()) return null
        val dir = baseDir ?: return null
        val target = fileFor(dir, databaseId)
        if (!target.exists()) return null
        return try {
            val raw = target.readBytes()
            if (raw.size <= IV_LENGTH_BYTES) return corrupt(target)
            val iv = raw.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = raw.copyOfRange(IV_LENGTH_BYTES, raw.size)
            val payload = decryptHook?.invoke(iv, ciphertext) ?: unsealWithKeystore(iv, ciphertext)
                ?: return corrupt(target)
            val copy = parsePayload(payload)
            payload.fill(0)
            copy
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本读取失败: ${e.javaClass.simpleName}")
            corrupt(target)
        }
    }

    /** 清除指定库的收编副本（幂等）。 */
    fun clear(databaseId: String) {
        if (databaseId.isBlank()) return
        val dir = baseDir ?: return
        try {
            fileFor(dir, databaseId).delete()
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本清除失败: ${e.javaClass.simpleName}")
        }
    }

    /** 清除全部收编副本（凭据全清 / 数据迁移等场景）。 */
    fun clearAll() {
        val dir = baseDir ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            debugLog?.warn(TAG, "副本全清失败: ${e.javaClass.simpleName}")
        }
    }

    // ===== 内部实现 =====

    /** Keystore 封印（requireUserAuth = false 的安全取舍声明见类 KDoc） */
    private fun sealWithKeystore(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        val km = keystoreManager ?: run {
            debugLog?.warn(TAG, "Keystore 通道缺失，副本未写入")
            return null
        }
        val key = km.getOrCreateKey(KEY_ALIAS, requireUserAuth = false)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Pair(cipher.iv, cipher.doFinal(payload))
    }

    private fun unsealWithKeystore(iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        val km = keystoreManager ?: return null
        val key = km.getOrCreateKey(KEY_ALIAS, requireUserAuth = false)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** 明文载荷组装：`[2 字节名字节长][显示名 UTF-8][密钥文件字节]` */
    private fun withDisplayName(displayName: String, bytes: ByteArray): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(USHORT_MAX)
        val payload = ByteArray(NAME_LEN_PREFIX + nameLen + bytes.size)
        payload[0] = ((nameLen shr 8) and 0xFF).toByte()
        payload[1] = (nameLen and 0xFF).toByte()
        nameBytes.copyInto(payload, NAME_LEN_PREFIX)
        bytes.copyInto(payload, NAME_LEN_PREFIX + nameLen)
        // nameBytes 是临时副本（toByteArray 产物），随 GC 回收；不留长驻引用
        return payload
    }

    private fun parsePayload(payload: ByteArray): StoredCopy? {
        if (payload.size < NAME_LEN_PREFIX) return null
        val nameLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        if (payload.size < NAME_LEN_PREFIX + nameLen) return null
        val displayName = String(payload, NAME_LEN_PREFIX, nameLen, Charsets.UTF_8)
        val bytes = payload.copyOfRange(NAME_LEN_PREFIX + nameLen, payload.size)
        return StoredCopy(bytes, displayName)
    }

    /** 损坏处置：就地删除（下次解锁回落 SAF 记忆通道），绝不反复失败 */
    private fun corrupt(target: File): StoredCopy? {
        debugLog?.warn(TAG, "副本损坏，就地删除")
        try {
            target.delete()
        } catch (_: Exception) {
        }
        return null
    }

    private fun fileFor(dir: File, databaseId: String): File = File(dir, "$databaseId$FILE_SUFFIX")

    private companion object {
        const val TAG = "KeyFileVaultCopy"
        const val DIR_NAME = "keyfiles"
        const val FILE_SUFFIX = ".kfc"
        const val KEY_ALIAS = "com.keepasskey.keyfile_copy_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
        const val NAME_LEN_PREFIX = 2
        const val USHORT_MAX = 0xFFFF
    }
}
