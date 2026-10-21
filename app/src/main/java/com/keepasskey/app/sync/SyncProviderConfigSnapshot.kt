package com.keepasskey.app.sync

import com.keepasskey.app.ui.screens.settings.CloudSyncProvider

/**
 * 保存时捕获的不可变同步配置回执。凭据仅以 Store 中已封印的 IV / 密文保存；
 * 生产 Provider 每次使用时再由 Store 解封独立数组并由调用方清理。
 */
internal class SyncProviderConfigSnapshot internal constructor(
    val provider: CloudSyncProvider,
    val revision: String,
    internal val scope: String?,
    internal val webDavUrl: String? = null,
    internal val webDavUsername: String? = null,
    internal val webDavRemotePath: String? = null,
    internal val webDavPasswordIv: String? = null,
    internal val webDavPasswordCipher: String? = null,
    internal val s3Endpoint: String? = null,
    internal val s3Bucket: String? = null,
    internal val s3Region: String? = null,
    internal val s3ObjectKey: String? = null,
    internal val s3UsePathStyle: Boolean = false,
    internal val s3AccessKeyIv: String? = null,
    internal val s3AccessKeyCipher: String? = null,
    internal val s3SecretKeyIv: String? = null,
    internal val s3SecretKeyCipher: String? = null,
    internal val s3ClockOffsetMillis: Long = 0L
)

