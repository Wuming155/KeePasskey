package com.keepasskey.app.passkey

/**
 * 扫码载荷分流（`ISSUE-P3-337` 口径 1）：顶栏与编辑页两条入口共用同一取景对话框
 * （`com.keepasskey.app.ui.screens.edit.TotpScanDialog`，相机与相册两通路同解码器），
 * 解出的原文既可能是 `otpauth://` URI，也可能是 FIDO CXF 的 JSON 载荷
 * ⇒ **在进入任何解析器之前**做一次纯函数判定，据此只走一条处理链。
 *
 * **禁止回退式猜测**（不得「先按 TOTP 解，失败再按通行密钥解」）：两个解析器都刻意保留
 * 了宽容面（`TotpKeyUriParser` 兼容裸 Base32 文本、CXF 侧忽略未知成员），回退顺序一旦出错，
 * 结果就是**把任意文本当口令种子落库**或**错拒一把合法凭据**——`ISSUE-P3-332` 为前者立过
 * 前缀强校验。故判据只依赖载荷**首部形态**，不做内容语义推断，三条路径互斥。
 *
 * 判据（跳过前导空白后看首个非空白字符）：
 * - `otpauth:` 起始（忽略大小写）→ [ScanPayloadKind.Totp]
 * - `{` 或 `[` 起始 → [ScanPayloadKind.Passkey]（CXF 的裸 `Passkey` 对象 / 凭据数组 /
 *   完整文档三种形态都以这两种字符起始）
 * - 其余 → [ScanPayloadKind.Unknown]（调用方须如实提示、不落库、不回显内容）
 *
 * ⚠️ `Totp` 只表示「归 TOTP 链处理」，**不是**「该 URI 合法」：TOTP 分支自己的
 * `otpauth://` 强校验（[com.keepasskey.app.ui.screens.vault.VaultListActionController]）
 * 仍是最终判据，故 `otpauth:` 之后缺 `//` 之类畸形形态会由该分支如实拒绝。
 *
 * 入参取 `CharArray` 而非 `ByteArray`（条目原文写作 `classify(bytes)`，此处为实现取向调整）：
 * 判据只用 ASCII 前缀，字符级与字节级判定**逐值等价**（非 ASCII 的 UTF-8 首字节 `≥ 0xC2`，
 * 不可能命中任何前缀），而字符级不必为「注定被拒的任意二维码」额外物化一份 UTF-8 缓冲；
 * 真正需要字节通道的是 CXF 解析器本身，转换发生在选定 `Passkey` 分支之后（同 TOTP 现链口径）。
 */
object ScanPayloadClassifier {

    /** 判定扫码原文的载荷类别；空载荷 / 纯空白一律 [ScanPayloadKind.Unknown]。 */
    fun classify(decoded: CharArray): ScanPayloadKind {
        var start = 0
        while (start < decoded.size && decoded[start].isWhitespace()) start++
        if (start == decoded.size) return ScanPayloadKind.Unknown
        return when (decoded[start]) {
            '{', '[' -> ScanPayloadKind.Passkey
            else -> if (hasOtpauthPrefix(decoded, start)) ScanPayloadKind.Totp else ScanPayloadKind.Unknown
        }
    }

    /** 跳过前导空白后的位置 [start] 起是否为 `otpauth:`（逐字符忽略大小写）。 */
    private fun hasOtpauthPrefix(chars: CharArray, start: Int): Boolean {
        if (chars.size - start < OTPAUTH_PREFIX.length) return false
        return OTPAUTH_PREFIX.indices.all { chars[start + it].lowercaseChar() == OTPAUTH_PREFIX[it] }
    }

    /** 分流用的方案前缀（比 TOTP 分支自身的 `otpauth://` 弱一档，理由见类 KDoc） */
    private const val OTPAUTH_PREFIX = "otpauth:"
}

/** [ScanPayloadClassifier.classify] 的三值判定结果（互斥，无回退顺序）。 */
enum class ScanPayloadKind {
    /** 交既有 TOTP 链处理（`otpauth:` URI）。 */
    Totp,

    /** 交 CXF 解析链处理（JSON 对象或数组形态）。 */
    Passkey,

    /** 两者都不是：如实提示、不落库、不回显内容。 */
    Unknown
}
