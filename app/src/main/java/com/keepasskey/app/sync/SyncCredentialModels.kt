package com.keepasskey.app.sync

/**
 * 同步凭据的值对象（`ISSUE-P2-465`：自 `SyncCredentialsStore.kt` 逐字搬出，
 * 使该文件在按库命名空间整改后仍留在规模闸门档内）。包名与类型全限定名不变，
 * 调用点零改动。
 */

/**
 * WebDAV 同步凭据模型。
 *
 * Wave 15 整改：[password] 以 [CharArray] 承载（借用语义：调用方用毕立即清零，
 * 绝不以 String 长期驻留）。
 */
class WebDavCredentials(
    val url: String,
    val username: String,
    val password: CharArray,
    val remotePath: String
)

/**
 * S3 兼容协议同步凭据模型。
 *
 * Wave 15 整改：[accessKey] / [secretKey] 以 [CharArray] 承载（借用语义：
 * 调用方用毕立即清零，绝不以 String 长期驻留）。
 */
class S3Credentials(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val accessKey: CharArray,
    val secretKey: CharArray,
    val objectKey: String,
    /** 寻址风格：false = virtual-host（AWS 等默认），true = path 风格（Cloudflare R2、IP 直连端点等） */
    val usePathStyle: Boolean = false
)
