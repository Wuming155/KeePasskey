package com.keepasskey.app.ui.screens.settings.subscreens

import com.keepasskey.app.R
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.settings.CloudSyncProvider

/**
 * 同步表单「保存配置」的提交与成功后清理（`ISSUE-P2-651` / `ISSUE-P2-652`）。
 *
 * 从 `CloudSyncScreen` 的表单体抽出（该 Composable 已逼近行数档位）。语义与抽出前逐字一致：
 * - 每次提交都先取表单草稿的**独立副本**（[withSyncCredentialSubmissionCopy]），把副本所有权交给
 *   ViewModel 由它消费并擦除；表单草稿**不动**——失败后用户只需改端点即可重试，不必重新键入秘密；
 * - 仅**保存成功**时才清空本地草稿并提示「已保存」；失败保留草稿，反馈由调用方上浮；
 * - 成功提示与草稿清理必须同批发生，否则会出现「提示已保存但输入框里还是明文」的矛盾态。
 */
internal class SyncFormPersistence(
    private val provider: () -> CloudSyncProvider,
    private val webDav: WebDavFormAccess,
    private val s3: S3FormAccess,
    private val onSaved: (UiMessage) -> Unit,
    private val onFieldWiped: () -> Unit
) {
    /** 表单中与 WebDAV 保存相关的可变状态访问口（由 Composable 以 lambda 提供）。 */
    internal class WebDavFormAccess(
        val url: () -> String,
        val username: () -> String,
        val remotePath: () -> String,
        val password: () -> CharArray,
        val update: (url: String, username: String, password: CharArray, remotePath: String) -> Boolean,
        val clearPassword: (CharArray) -> Unit
    )

    /** 表单中与 S3 保存相关的可变状态访问口。 */
    internal class S3FormAccess(
        val endpoint: () -> String,
        val bucket: () -> String,
        val region: () -> String,
        val objectKey: () -> String,
        val usePathStyle: () -> Boolean,
        val accessKey: () -> CharArray,
        val secretKey: () -> CharArray,
        val update: (
            endpoint: String, bucket: String, region: String,
            accessKey: CharArray, secretKey: CharArray, objectKey: String, usePathStyle: Boolean
        ) -> Boolean,
        val clearSecrets: (CharArray, CharArray) -> Unit
    )

    /** 持久化当前表单；返回是否保存成功（失败时草稿原样保留）。 */
    fun persist(): Boolean =
        if (provider() == CloudSyncProvider.WEBDAV) persistWebDav() else persistS3()

    private fun persistWebDav(): Boolean {
        val password = webDav.password()
        val saved = withSyncCredentialSubmissionCopy(password) { submission ->
            webDav.update(webDav.url(), webDav.username(), submission, webDav.remotePath())
        }
        if (saved) {
            onSaved(UiMessage(R.string.sync_webdav_saved))
            webDav.clearPassword(password)
            onFieldWiped()
        }
        return saved
    }

    private fun persistS3(): Boolean {
        val accessKey = s3.accessKey()
        val secretKey = s3.secretKey()
        val saved = withSyncCredentialSubmissionCopy(accessKey) { accessSubmission ->
            withSyncCredentialSubmissionCopy(secretKey) { secretSubmission ->
                s3.update(
                    s3.endpoint(), s3.bucket(), s3.region(),
                    accessSubmission, secretSubmission, s3.objectKey(), s3.usePathStyle()
                )
            }
        }
        if (saved) {
            onSaved(UiMessage(R.string.sync_s3_saved))
            s3.clearSecrets(accessKey, secretKey)
            onFieldWiped()
        }
        return saved
    }
}
