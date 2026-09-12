package com.keepasskey.core.model

/**
 * KDBX 数据库内存保护配置（对应 Meta.MemoryProtection）。
 * 分别控制各标准字段在内存中是否受保护。
 *
 * 官方语义（KeePass 2.61.1 `KdbxFile.Read.cs:246-248`，注释 "Reset memory protection settings
 * (to always use reasonable defaults)"）：库文件装载收尾会把本配置**整对象重置为默认值**，
 * 文件里写的组合一律不采用。这是防止恶意/畸形文件改变本机内存保护策略的安全基线
 * （例如把 Password 的驻留加密整体关掉），因此解析产物中的本配置恒为默认组合。
 */
data class MemoryProtectionConfig(
    val protectTitle: Boolean = false,
    val protectUserName: Boolean = false,
    val protectPassword: Boolean = true,
    val protectUrl: Boolean = false,
    val protectNotes: Boolean = false
) {

    /**
     * 按官方标准字段名查询该字段是否启用内存保护。
     *
     * @param fieldName 官方标准字段名，取值与 [KdbxConstants.Fields] 一致：
     *   `Title` → [protectTitle]、`UserName` → [protectUserName]、`Password` → [protectPassword]、
     *   `URL` → [protectUrl]、`Notes` → [protectNotes]。
     * @return 命中标准字段时返回对应开关；其它字段（含自定义字段、大小写不一致的写法）一律返回
     *   `false`（不保护），与官方 `PwEntry.Strings` 仅对标准字段应用库级保护的语义一致。
     */
    fun isProtectEnabledFor(fieldName: String): Boolean {
        return when (fieldName) {
            KdbxConstants.Fields.TITLE -> protectTitle
            KdbxConstants.Fields.USER_NAME -> protectUserName
            KdbxConstants.Fields.PASSWORD -> protectPassword
            KdbxConstants.Fields.URL -> protectUrl
            KdbxConstants.Fields.NOTES -> protectNotes
            else -> false
        }
    }
}
