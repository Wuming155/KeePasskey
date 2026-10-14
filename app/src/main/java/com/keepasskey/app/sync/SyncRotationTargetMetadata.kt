package com.keepasskey.app.sync

import com.keepasskey.app.ui.screens.settings.CloudSyncProvider

/** 纯 metadata 快照解析；不读写偏好、不触碰封印凭据。 */
internal object SyncRotationTargetMetadata {
    fun capture(values: Map<String, *>, scope: String?, currentScope: String?, defaultName: String): String? {
        val anchors = listOf(SyncCredentialKeys.WEBDAV_URL, SyncCredentialKeys.S3_ENDPOINT)
        val hasScopedAnchor = anchors.any { values.containsKey(SyncCredentialKeys.scoped(it, scope)) }
        val useLegacy = !hasScopedAnchor && anchors.any(values::containsKey) && currentScope == scope
        val selectedScope = if (useLegacy) null else scope
        fun value(base: String) = values[SyncCredentialKeys.scoped(base, selectedScope)] as? String
        val provider = value(SyncCredentialKeys.PROVIDER)?.let {
            runCatching { CloudSyncProvider.valueOf(it) }.getOrNull()
        } ?: CloudSyncProvider.WEBDAV
        return when (provider) {
            CloudSyncProvider.WEBDAV -> {
                if (value(SyncCredentialKeys.WEBDAV_URL).isNullOrBlank()) return null
                value(SyncCredentialKeys.WEBDAV_REMOTE_PATH)?.trim()?.takeIf(String::isNotBlank)
                    ?.let { if (it.startsWith("/")) it else "/$it" } ?: "/$defaultName"
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                if (value(SyncCredentialKeys.S3_ENDPOINT).isNullOrBlank()) return null
                value(SyncCredentialKeys.S3_OBJECT_KEY)?.trim()?.takeIf(String::isNotBlank) ?: defaultName
            }
        }
    }
}
