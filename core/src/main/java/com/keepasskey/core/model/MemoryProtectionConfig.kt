package com.keepasskey.core.model

/**
 * KDBX 数据库内存保护配置（对应 Meta.MemoryProtection）。
 *
 * 官方语义（KeePass 2.61.1 `KdbxFile.Read.cs:246-248`，注释 "Reset memory protection settings
 * (to always use reasonable defaults)"）：库文件装载收尾会把本配置**整对象重置为默认值**，
 * 文件里写的组合一律不采用。这是防止恶意/畸形文件改变本机内存保护策略的安全基线
 * （例如把 Password 的驻留加密整体关掉），因此解析产物中的本配置恒为默认组合。
 *
 * ## 两条互不相干的独立口径（ISSUE-P2-64，改本类或写侧前必读）
 *
 * 本配置**只**影响「写出标志」，**不**决定「内存密封」——把两者混为一谈是本节要消除的误解：
 *
 * 1. **内存密封**（会话内的驻留保护）由**字段自身的** `ProtectedString.isProtected` 决定：
 *    读侧取自 XML 的 `Protected` 属性（`isProtectedAttribute` 精确匹配 `"True"`），
 *    写入侧对非口令字段硬编码 `false`。本配置**不参与**该判定——库级
 *    `protectUserName = true` **不会**让内存中的 `UserName` 变成密封态，反之亦然。
 *    故「Title / UserName / URL / Notes 在内存中是未密封明文」是既定的**有意口径**，
 *    与 `ProtectedString` 驻留加密同属纵深防御层（持密钥者仍可在读取瞬间获得明文，
 *    见 `AGENTS.md` §6）。
 * 2. **写出标志**（产物里的 `Protected` 属性）由 [isProtectEnabledFor] 经
 *    `KdbxXmlEntrySerializer.resolveProtectedFlag` **无条件覆盖**标准五字段
 *    （官方 `KdbxFile.Write.cs:844-853` 的 `=` 而非 `|=` 语义）；非标准 / 自定义字段
 *    保留 per-value。
 *
 * **AC② 裁决：不采纳「库级开启即内存密封」（ISSUE-P2-64）**，理由三条：① 官方并不把本配置
 * 当作内存密封开关——装载收尾整对象重置即说明官方只把它当「写出标志的归一化输入」；
 * ② 采纳会让本仓产物语义与官方客户端分叉（`AGENTS.md` §3.3：官方实现为格式裁决者）；
 * ③ 内存密封的真实开关是 per-field 的 `ProtectedString.isProtected`，已有独立的读写、
 * 序列化与清零契约，无需再由库级配置二次驱动。**反向结论同样重要**：本配置被恶意库改坏
 * **不会**削弱内存密封，因为读侧恒定重置为默认值。
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
