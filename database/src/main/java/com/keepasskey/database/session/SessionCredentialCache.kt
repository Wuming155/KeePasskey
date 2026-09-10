package com.keepasskey.database.session

import java.util.Arrays

/**
 * 会话凭据缓存（ISSUE-P3-31 批次 D 结构拆分）。
 *
 * 承接拆分前 [DatabaseSession] 内的 `passwordCache` / `keyFileCache` / `credentialLock`：
 * 主密码与密钥文件因子以 `CharArray` / `ByteArray` 承载，读写均持同一把锁，
 * 显式清零点与拆分前逐处对齐。
 *
 * 敏感数据铁律：对外只暴露克隆副本（[useCredentials] / [passwordSnapshot] /
 * [keyFileSnapshot] / [exportKeyFileBytes]）；[currentPassword] / [currentKeyFile] 返回
 * 当前引用仅供会话期序列化复用（与拆分前把 `passwordCache` / `keyFileCache` 直接传给
 * `KdbxFile.save` 的行为一致）。
 */
internal class SessionCredentialCache {

    private var passwordCache: CharArray? = null
    private var keyFileCache: ByteArray? = null
    private val credentialLock = Any()

    /**
     * 在锁保护下获取当前缓存凭据的克隆副本并执行 [block]。
     *
     * 传入 [block] 的数组为克隆出的独立副本，调用方用毕必须显式清零。
     */
    fun <T> useCredentials(block: (CharArray?, ByteArray?) -> T): T = synchronized(credentialLock) {
        val pwdClone = passwordCache?.clone()
        val keyClone = keyFileCache?.clone()
        block(pwdClone, keyClone)
    }

    /** 当前主密码引用（供序列化复用，不克隆——与拆分前传递同一引用一致）。 */
    fun currentPassword(): CharArray? = passwordCache

    /** 当前密钥文件引用（供序列化复用，不克隆——与拆分前传递同一引用一致）。 */
    fun currentKeyFile(): ByteArray? = keyFileCache

    /** 主密码克隆快照（对应拆分前 `passwordCache?.clone()`，同样不持锁）。 */
    fun passwordSnapshot(): CharArray? = passwordCache?.clone()

    /** 密钥文件克隆快照（对应拆分前 `keyFileCache?.clone()`，同样不持锁）。 */
    fun keyFileSnapshot(): ByteArray? = keyFileCache?.clone()

    /** 缓存主密码：先清空全部旧缓存再克隆写入（对应拆分前 `cachePassword`）。 */
    fun cachePassword(passwordChars: CharArray?) = synchronized(credentialLock) {
        clearSensitiveCacheInternal()
        passwordCache = passwordChars?.clone()
    }

    /** 缓存密钥文件因子（非空时克隆写入）；不清空既有主密码缓存。 */
    fun cacheKeyFile(keyFileData: ByteArray?) {
        if (keyFileData == null) return
        synchronized(credentialLock) {
            keyFileCache = keyFileData.clone()
        }
    }

    /**
     * 轮换凭据（对应拆分前 `changeCredentials` 内的同步替换块）：先清零旧主密码，
     * 置入新主密码；仅当新密钥文件非空时清零并替换密钥文件缓存。
     */
    fun rotateCredentials(newPassword: CharArray?, newKeyFileData: ByteArray?) = synchronized(credentialLock) {
        passwordCache?.let { Arrays.fill(it, '0') }
        passwordCache = newPassword?.clone()
        if (newKeyFileData != null) {
            keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
            keyFileCache = newKeyFileData.clone()
        }
    }

    /** 轮换失败时回滚为旧凭据（对应拆分前 `changeCredentials` 的 catch 回滚块）。 */
    fun restoreCredentials(oldPassword: CharArray?, oldKeyFile: ByteArray?) = synchronized(credentialLock) {
        passwordCache?.let { Arrays.fill(it, '0') }
        passwordCache = oldPassword
        keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
        keyFileCache = oldKeyFile
    }

    /** 清零并置空全部凭据缓存（对应拆分前 `clearSensitiveCache`）。 */
    fun clear() = synchronized(credentialLock) {
        clearSensitiveCacheInternal()
    }

    /** 导出密钥文件原始字节克隆（对应拆分前 `exportKeyFileBytes`）。 */
    fun exportKeyFileBytes(): ByteArray? = synchronized(credentialLock) {
        keyFileCache?.clone()
    }

    private fun clearSensitiveCacheInternal() {
        passwordCache?.let { Arrays.fill(it, '0') }
        passwordCache = null
        keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
        keyFileCache = null
    }
}
