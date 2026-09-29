package com.keepasskey.app.sync

/**
 * ISSUE-P3-395：远端目录浏览的凭据解析（表单优先 + 已保存凭据回退）。
 *
 * 立规缘由（2026-10-30 真机手测）：「测试连接」走 [com.keepasskey.app.sync.SyncCredentialsStore]
 * **已保存**凭据，而「浏览远端目录」原先只吃设置页表单内存态。保存成功后表单密码数组被
 * Wave 15 借用语义清零，于是测连成功、浏览却以空密码 401。
 *
 * 口径：
 * - 表单密码非空 ⇒ **表单优先**（支持尚未保存的新配置）；
 * - 表单为空 ⇒ 回退已保存密码副本（与「测试连接」同源）；
 * - 两侧皆空 ⇒ 返回空数组，由 Provider 上浮鉴权失败（不伪装成功）。
 *
 * 返回值一律是**调用方可清零的副本**（表单路径的入参本身即为 UI 侧 copyOf）；
 * 本函数不读写 Keystore，纯函数便于宿主单测。
 */
internal object RemoteBrowseCredentials {

    /**
     * @param formPassword 浏览入口收到的表单凭据（可能是 UI `copyOf` 产物）
     * @param savedPassword [SyncCredentialsStore] 解密后的已保存凭据（可为 null）
     * @return 应交给 Provider 的凭据数组；两侧皆空时为 `CharArray(0)`
     */
    fun resolveBrowsePassword(
        formPassword: CharArray,
        savedPassword: CharArray?
    ): CharArray {
        if (formPassword.isNotEmpty()) return formPassword
        val saved = savedPassword ?: return CharArray(0)
        return if (saved.isNotEmpty()) saved else CharArray(0)
    }

    /**
     * @param formAccessKey / [formSecretKey] 表单密钥
     * @param savedAccessKey / [savedSecretKey] 已保存密钥
     * @return (accessKey, secretKey)；任一侧表单有值则该侧优先
     */
    fun resolveS3BrowseKeys(
        formAccessKey: CharArray,
        formSecretKey: CharArray,
        savedAccessKey: CharArray?,
        savedSecretKey: CharArray?
    ): Pair<CharArray, CharArray> {
        return resolveBrowsePassword(formAccessKey, savedAccessKey) to
            resolveBrowsePassword(formSecretKey, savedSecretKey)
    }
}
