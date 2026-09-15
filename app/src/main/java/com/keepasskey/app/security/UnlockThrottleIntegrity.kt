package com.keepasskey.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 解锁节流记录 MAC 载荷编码（ISSUE-P3-54，纯函数，JVM 可测）。
 *
 * 载荷仅含 `databaseId` + 失败计数 + 锁定截止时间戳，**不含任何主密码明文**；
 * 使用 `\u0000` 分隔避免字段边界歧义（长度前缀的简化等价形式）。
 */
object UnlockThrottleMacPayload {
    private const val FIELD_SEPARATOR = '\u0000'

    fun encode(databaseId: String, record: UnlockThrottleRecord): ByteArray =
        "$databaseId$FIELD_SEPARATOR${record.failureCount}$FIELD_SEPARATOR${record.lockoutUntilEpochMs}"
            .toByteArray(Charsets.UTF_8)
}

/**
 * 节流记录「存在性标记」的 AndroidKeyStore 别名推导（ISSUE-P2-45，纯函数，JVM 可测）。
 *
 * 别名按 `databaseId` 的 SHA-256 前 16 字节派生：既保证同一库恒定映射到同一条目，
 * 又把任意形态的 `databaseId`（UUID / 路径 / URI）压成定长、字符集安全的别名。
 * **前缀与 [AndroidKeystoreUnlockThrottleIntegrity] 的 MAC 密钥别名不同**——老版本安装
 * （仅有 MAC 密钥、无本标记）不会被误判为「记录被删除」。
 */
object UnlockThrottleExistenceMarkerAlias {

    private const val PREFIX = "com.keepasskey.unlock_throttle_seen_"

    fun of(databaseId: String): String = PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(databaseId.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * 解锁节流记录完整性校验抽象（ISSUE-P3-54 / ISSUE-P2-45）。
 *
 * 生产环境使用 [AndroidKeystoreUnlockThrottleIntegrity]（AndroidKeyStore 内不可导出的 HMAC 密钥）；
 * JVM 单测注入固定密钥的假实现。记录被删除 / 篡改导致 MAC 校验失败时，由
 * [UnlockThrottleManager.gate] fail-closed 处理。
 *
 * ISSUE-P2-45：MAC 只能保护**在案**记录的内容，无法证明「记录本应存在」——攻击者把三个键
 * 一并删除后，MAC 路径无从校验（等价于全新安装）。故本接口追加**存在性标记**：
 * 标记落在 AndroidKeyStore 内（非 `shared_prefs` 文件），文件级删除无法移除，
 * 由此把「从未有过记录」与「记录被删除」区分开。
 */
interface UnlockThrottleIntegrity {
    /** 计算记录 MAC；密钥不可用时返回 null（调用方按 fail-closed 处理） */
    fun mac(databaseId: String, record: UnlockThrottleRecord): ByteArray?

    /** 校验记录 MAC；密钥不可用 / MAC 缺失 / 不匹配一律 false */
    fun verify(databaseId: String, record: UnlockThrottleRecord, mac: ByteArray?): Boolean

    /**
     * 确保 `databaseId` 的**存在性标记**已在 AndroidKeyStore 内建立（幂等）。
     *
     * @return 标记在本次调用后确实存在则为 true；Keystore 不可用等异常情形返回 false
     *         （此时「删键复位」维度退化为标记引入前的行为，**不做静默放行承诺**，
     *         记录内容维度的 MAC 校验不受影响）。
     */
    fun ensureExistenceMarker(databaseId: String): Boolean

    /**
     * `databaseId` 的存在性标记是否在案。
     *
     * 语义为「本机曾为这个库写入过节流记录」。实现须对异常 fail-closed 友好：
     * 返回 false 只能表示**确认不存在**，不得把异常吞成「不存在」（否则异常即旁路）。
     * 故实现异常时抛出让调用方按 fail-closed 处理。
     */
    fun existenceMarkerPresent(databaseId: String): Boolean
}

/**
 * [UnlockThrottleIntegrity] 的 AndroidKeystore 实现（ISSUE-P3-54）。
 *
 * 采用应用级 HMAC-SHA256 密钥（别名 [KEY_ALIAS]），**不绑定用户认证**——解锁前闸门
 * [UnlockThrottleManager.gate] 必须在锁屏态亦可用。密钥在 AndroidKeyStore 内生成、不可导出，
 * root 之外的普通文件级删除 / 篡改节流记录会因 MAC 不匹配触发 fail-closed 锁定。
 *
 * ISSUE-P2-45：追加**每库一条**的存在性标记条目（别名见 [UnlockThrottleExistenceMarkerAlias]）——
 * MAC 只能证明「在案记录未被改动」，标记才能证明「记录本应存在」，两者合起来封住
 * 「删掉三个 SharedPreferences 键即复位计数」的旁路。
 *
 * 威胁模型边界：具备任意代码执行 / Hook 进程能力的攻击者本可绕过应用层逻辑；此外
 * **「清除应用数据」会同时移除 Keystore 条目与本标记**（等同全新安装，且会一并清空库列表），
 * 不属本类覆盖的「文件级删除」面。本类属纵深防御卫生层。
 */
@Singleton
class AndroidKeystoreUnlockThrottleIntegrity @Inject constructor() : UnlockThrottleIntegrity {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KeystoreManager.ANDROID_KEY_STORE).apply { load(null) }
    }

    override fun mac(databaseId: String, record: UnlockThrottleRecord): ByteArray? = try {
        // ISSUE-P0-10：**不得**向 Mac.getInstance 传 "AndroidKeyStore" provider——AndroidKeyStore
        // 只提供 KeyStore / KeyGenerator 等，**不注册 Mac.HmacSHA256 服务**，实机上必然抛
        // NoSuchAlgorithmException（Redmi 4X / Android 17 实测：`no such algorithm: HmacSHA256
        // for provider AndroidKeyStore`）。官方 KeyGenParameterSpec 的 HMAC 样例同样是
        // `Mac.getInstance("HmacSHA256")` + Keystore 密钥。密钥仍由 AndroidKeyStore 持有与运算。
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(getOrCreateKey())
        mac.doFinal(UnlockThrottleMacPayload.encode(databaseId, record))
    } catch (_: Throwable) {
        null
    }

    override fun verify(databaseId: String, record: UnlockThrottleRecord, mac: ByteArray?): Boolean {
        if (mac == null) return false
        val expected = mac(databaseId, record) ?: return false
        return MessageDigest.isEqual(expected, mac)
    }

    /**
     * ISSUE-P2-45：为 `databaseId` 建立存在性标记（AndroidKeyStore 内一条 HmacSHA256 密钥条目）。
     *
     * 标记只是一条「曾经写过记录」的证据，不参与任何密码学运算；之所以用 Keystore 条目而非
     * SharedPreferences 键，正是为了让**文件级删除**（改动或删除 `shared_prefs` 目录下的
     * XML 文件）无法把它一并抹掉——那正是「删键复位」旁路的实施面。
     */
    override fun ensureExistenceMarker(databaseId: String): Boolean = try {
        getOrCreateMarker(UnlockThrottleExistenceMarkerAlias.of(databaseId))
        true
    } catch (_: Throwable) {
        false
    }

    override fun existenceMarkerPresent(databaseId: String): Boolean =
        keyStore.containsAlias(UnlockThrottleExistenceMarkerAlias.of(databaseId))

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(HMAC_ALGORITHM, KeystoreManager.ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun getOrCreateMarker(alias: String) {
        if (keyStore.containsAlias(alias)) return
        val generator = KeyGenerator.getInstance(HMAC_ALGORITHM, KeystoreManager.ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).build()
        )
        generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "com.keepasskey.unlock_throttle_integrity"
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
