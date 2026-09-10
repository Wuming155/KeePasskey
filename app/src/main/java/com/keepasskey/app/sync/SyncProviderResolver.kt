package com.keepasskey.app.sync

import com.keepasskey.app.data.logger.DebugLogBuffer
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider
import com.keepasskey.sync.network.SyncNetworkOptions
import com.keepasskey.sync.network.SyncTransferOptions
import com.keepasskey.sync.provider.SyncProvider
import com.keepasskey.sync.s3.S3SyncProvider
import com.keepasskey.sync.webdav.WebDavSyncProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云同步 Provider 构建与远端路径解析（ISSUE-P3-25 拆分自 `SyncCoordinator`，纯搬运）。
 *
 * 职责边界：只做「凭据存储 → Provider 实例 / 远端对象路径」的构建，不参与同步周期决策
 * （见 `SyncCycleRunner`）与冲突决策（见 `SyncConflictController`）。
 * 凭据借用-擦除契约与拆分前逐行一致：
 * - WebDAV：`passwordChars` 为借用语义，构造完成后由本方法即时 `fill('0')`；
 * - S3：AccessKey/SecretKey 以 clone 传入 Provider 持有，构造失败立即擦除 clone，
 *   周期结束由调用方对该 Provider 执行 `clearCredentials()`。
 */
@Singleton
class SyncProviderResolver @Inject constructor(
    private val syncCredentialsStore: SyncCredentialsStore,
    private val preferences: SyncPreferences,
    private val debugLog: DebugLogBuffer
) {

    /** ISSUE-P3-03 (43a)：偏好 → 传输层配置（依赖倒置；sync 模块不感知 app 偏好类型） */
    private fun transferOptions(): SyncTransferOptions {
        val settings = preferences.currentSettings()
        return SyncTransferOptions.fromPreferences(
            chunkedUploadEnabled = settings.webdavChunkedUpload,
            chunkSizeMb = settings.webdavChunkSizeMb
        )
    }

    fun resolveProvider(): SyncProvider? {
        return when (syncCredentialsStore.loadProvider()) {
            CloudSyncProvider.WEBDAV -> {
                val cfg = syncCredentialsStore.loadWebDavConfig() ?: return null
                if (cfg.url.isBlank()) return null
                try {
                    WebDavSyncProvider(
                        serverUrl = cfg.url,
                        username = cfg.username,
                        // Wave 15：cfg.password 已是 CharArray（借用语义），构造完成后由本方统一擦除
                        passwordChars = cfg.password,
                        // Wave 14 传输安全：TLS-only + 显式超时；证书固定已整体移除，
                        // 证书验证完全依赖系统默认 CA 链（客户端由 sync 模块工厂构建）
                        networkOptions = SyncNetworkOptions(),
                        // ISSUE-P3-03 (43a)：分块上传偏好经纯数据契约下传（依赖倒置）
                        transferOptions = transferOptions()
                    )
                } finally {
                    cfg.password.fill('0')
                }
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val cfg = syncCredentialsStore.loadS3Config() ?: return null
                if (cfg.endpoint.isBlank() || cfg.bucket.isBlank()) return null
                // ISSUE-P1-06 整改：凭据以 CharArray clone 传入 Provider（借用语义转移），
                // Provider 持有期间可多次签名复用，同步周期结束后由 runSyncCycle 调用
                // clearCredentials() 显式擦除——杜绝旧版 String(cfg.accessKey) 物化后
                // 与 Provider 同生命周期、结构性不可擦除的缺陷。
                val accessKeyClone = cfg.accessKey.clone()
                val secretKeyClone = cfg.secretKey.clone()
                try {
                    S3SyncProvider(
                        endpoint = cfg.endpoint,
                        bucketName = cfg.bucket,
                        region = cfg.region,
                        accessKeyId = accessKeyClone,
                        secretAccessKey = secretKeyClone,
                        usePathStyle = cfg.usePathStyle,
                        networkOptions = SyncNetworkOptions(),
                        // TASK-45（P2-14）：恢复上次持久化的服务端时钟偏移，启动即补偿；
                        // 同步期间每次响应携带 Date 头即经回调刷新落盘（跨进程保留）
                        initialClockOffsetMillis = syncCredentialsStore.loadS3ClockOffsetMillis(),
                        clockOffsetUpdater = { offset ->
                            try {
                                syncCredentialsStore.saveS3ClockOffsetMillis(offset)
                            } catch (e: Exception) {
                                // 持久化失败仅丢失跨进程记忆：本会话内存偏移仍即时生效，
                                // 下次同步按 fail-closed 重新学习，不阻断本次同步
                                debugLog.warn(SYNC_LOG_TAG, "S3 时钟偏移持久化失败: ${e.message}")
                            }
                        }
                    )
                } catch (e: Exception) {
                    // 构造失败（如端点校验拒绝）时擦除已 clone 的凭据副本，防泄漏
                    accessKeyClone.fill('0')
                    secretKeyClone.fill('0')
                    throw e
                } finally {
                    // 原始 cfg 数组由 loadS3Config 借用语义管理，此处擦除防止残留
                    cfg.accessKey.fill('0')
                    cfg.secretKey.fill('0')
                }
            }
        }
    }

    fun resolveRemotePath(defaultFileName: String): String {
        return when (syncCredentialsStore.loadProvider()) {
            CloudSyncProvider.WEBDAV -> {
                val cfg = syncCredentialsStore.loadWebDavConfig()
                val path = cfg?.remotePath?.trim()
                if (!path.isNullOrBlank()) {
                    if (path.startsWith("/")) path else "/$path"
                } else {
                    "/$defaultFileName"
                }
            }
            CloudSyncProvider.S3_COMPATIBLE -> {
                val cfg = syncCredentialsStore.loadS3Config()
                cfg?.objectKey?.trim()?.ifBlank { defaultFileName } ?: defaultFileName
            }
        }
    }
}
