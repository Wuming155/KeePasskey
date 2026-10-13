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
     * 传入 [block] 的数组为克隆出的独立副本，**本函数在 [block] 返回前（含抛异常路径）
     * 负责将其清零**（ISSUE-P2-312）——清零不再依赖调用方自觉，与
     * `ChildDatabaseCredentialStore.useCredentials` 的强契约同口径。调用方若需在该副本上
     * 再做一次内层克隆（如交给 `KdbxFile.save` / `load` 的入参），内层副本仍由调用方自行清零。
     */
    fun <T> useCredentials(block: (CharArray?, ByteArray?) -> T): T = synchronized(credentialLock) {
        val pwdClone = passwordCache?.clone()
        val keyClone = keyFileCache?.clone()
        try {
            block(pwdClone, keyClone)
        } finally {
            pwdClone?.let { Arrays.fill(it, '0') }
            keyClone?.let { Arrays.fill(it, 0.toByte()) }
        }
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
     * 精确采用两个新因子：null 表示移除对应因子，不表示保留旧缓存。
     * 调用方数组只读借用；先准备完整的独立副本，再擦除旧缓存并发布新缓存。
     */
    fun rotateCredentials(newPassword: CharArray?, newKeyFileData: ByteArray?) = synchronized(credentialLock) {
        replaceCredentialsInternal(newPassword, newKeyFileData)
    }

    /**
     * 轮换失败时克隆恢复旧凭据；快照仍归事务所有，事务须在 finally 擦除。
     * 不接管入参数组，避免事务擦快照时同时破坏已恢复的活动缓存。
     */
    fun restoreCredentials(oldPassword: CharArray?, oldKeyFile: ByteArray?) = synchronized(credentialLock) {
        replaceCredentialsInternal(oldPassword, oldKeyFile)
    }

    /**
     * 提交后的无分配发布：接管事务预先创建的独占副本，旧缓存当场擦除。
     * 入参数组的所有权转移到缓存；事务此后不得读取或清零，必须撤销自己的清理责任。
     * 仅限凭据轮换的提交收尾，禁止传入借用数组或当前缓存的别名。
     */
    fun adoptOwnedCredentials(password: CharArray?, keyFile: ByteArray?) = synchronized(credentialLock) {
        clearSensitiveCacheInternal()
        passwordCache = password
        keyFileCache = keyFile
    }

    /** 清零并置空全部凭据缓存（对应拆分前 `clearSensitiveCache`）。 */
    fun clear() = synchronized(credentialLock) {
        clearSensitiveCacheInternal()
    }

    /** 导出密钥文件原始字节克隆（对应拆分前 `exportKeyFileBytes`）。 */
    fun exportKeyFileBytes(): ByteArray? = synchronized(credentialLock) {
        keyFileCache?.clone()
    }

    /** 仅在 credentialLock 内调用；副本准备失败时保留当前缓存并擦除准备中的副本。 */
    private fun replaceCredentialsInternal(password: CharArray?, keyFile: ByteArray?) {
        val passwordCopy = password?.clone()
        var keyFileCopy: ByteArray? = null
        var adopted = false
        try {
            keyFileCopy = keyFile?.clone()
            clearSensitiveCacheInternal()
            passwordCache = passwordCopy
            keyFileCache = keyFileCopy
            adopted = true
        } finally {
            if (!adopted) {
                passwordCopy?.fill('0')
                keyFileCopy?.fill(0)
            }
        }
    }

    private fun clearSensitiveCacheInternal() {
        passwordCache?.let { Arrays.fill(it, '0') }
        passwordCache = null
        keyFileCache?.let { Arrays.fill(it, 0.toByte()) }
        keyFileCache = null
    }
}
