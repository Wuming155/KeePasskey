package com.keepasskey.app.sync

/**
 * WebDAV 默认展示端点（坚果云）。
 *
 * 立规：设置页 / 打开远端库对话框的 URL 占位与「无已保存配置」时的预填值统一走本表，
 * 避免用户每次从 Nextcloud 示例文案开始重敲。
 *
 * 密码说明：坚果云必须使用网页版「安全设置」生成的 **WebDAV 专用密码**，不是登录密码。
 */
object WebDavDefaults {
    /** 坚果云 WebDAV 根地址（HTTPS 强制；本仓 PD-02 / Wave 14 口径不变）。 */
    const val NUTSTORE_URL: String = "https://dav.jianguoyun.com/dav/"

    /** 默认远程库文件名（相对端点；用户可改为 `<邮箱>/keepasskey.kdbx` 等形态）。 */
    const val DEFAULT_REMOTE_PATH: String = "keepasskey.kdbx"
}
