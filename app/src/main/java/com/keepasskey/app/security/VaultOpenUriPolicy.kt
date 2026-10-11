package com.keepasskey.app.security

import java.io.File

/**
 * 「打开方式」外部打开入口（`OpenVaultEntryActivity`）与库解锁链的 URI 准入策略（`ISSUE-P3-575`）。
 *
 * ## 为什么是纯函数
 *
 * `ISSUE-P3-575` 的核心判据面（scheme 白名单 / 显示名形态 / 已登记路径形态）此前混在
 * `Intent` / `Uri` / `ContentResolver` 之间，宿主 JVM 上 `Uri.parse` 不可用，判据因此**无法被单测锁定**
 * ——外部评审实测「代码连 `file://` 与 `content://` 都不区分」，而三处 safeNote 与白名单理由却声称
 * 「仅接受 content/file scheme」。把判定抽成只吃 `String` 的纯函数后，攻击者 scheme / 相对路径 /
 * 伪装后缀三类用例可在**纯 JVM**逐条断言（见 `VaultOpenUriPolicyTest`），口径不再依赖真机。
 *
 * ## 三条判据（各自 fail-closed）
 *
 * 1. [acceptInboundUri]：入站 URI **只接受 `content://`**。`file://` 是本条的攻击面形态——
 *    任意普通应用无需 `FLAG_GRANT` 即可显式 Intent 送达，且 `File("file:/…")` 会被当**相对路径**
 *    解析；`http(s)://` / 裸相对路径 / 空 scheme 同样拒绝。收敛到 `file://` 白名单需要证明
 *    「可预期来源形态」，本轮未证明，故取最稳妥的**一律拒绝**（AGENTS.md §3 防御性安全）。
 * 2. [acceptDisplayName]：显示名必须是「末段为纯文件名」的 `.kdbx`——非空、不含 `/` `\` 路径分量、
 *    以 `.kdbx` 结尾（大小写不敏感）。带目录分量的显示名（`sub/evil.kdbx`、`..\x.kdbx`）一并拒绝，
 *    `evil.kdbx` 之类的伪装后缀由密码学层兜底（非 KDBX 内容登记后无法解锁）。
 * 3. [isUnlockableStoredPath]：已登记库路径只接受 `content://` 或**裸绝对路径**（平台的 `File.isAbsolute`，
 *    即 Android / Linux 的 `/…` 与 Windows 宿主的 `C:\…`）。含 scheme 前缀（如 `file:/…`）或相对路径
 *    一律视为不可解锁——`VaultLifecycleCoordinator` 在 `File(activeDb.path)` 之前据此 fail-closed，
 *    堵住「相对解析 → 文件缺失 → 新建空库」分支。判据取 `File.isAbsolute` 而非硬编「以 `/` 开头」：
 *    后者在 Windows 宿主 JVM 上会把合法的绝对路径误判成相对（本批首轮全量单测实测的假红，见批次 §490.4）。
 *
 * 说明：`content://` 匹配与仓内既有判据同口径（`VaultLifecycleCoordinator` / `VaultDatabaseCatalog`
 * 均按大小写敏感前缀判定，Android 侧 URI scheme 恒小写，故不引入 `ignoreCase` 分歧）。
 */
object VaultOpenUriPolicy {

    /** 唯一被接受的入站 scheme 前缀（`ISSUE-P3-575`） */
    const val CONTENT_SCHEME_PREFIX = "content://"

    /** 准入裁决结果 */
    sealed interface Decision {
        /** 放行 */
        data object Accept : Decision

        /** 拒绝；[reason] 是判据面枚举，供日志与测试断言，**不含外部输入原文**（避免日志注入） */
        class Reject(val reason: Reason) : Decision
    }

    /** 拒绝原因（判据面） */
    enum class Reason {
        /** URI 为空 / 空白 */
        EMPTY_URI,

        /** scheme 不在白名单（`file://` / `http(s)://` / 相对路径 / 空 scheme） */
        UNSUPPORTED_SCHEME,

        /** 显示名为空 / 空白 */
        EMPTY_NAME,

        /** 显示名含目录分量（`/` 或 `\`） */
        PATH_SHAPED_NAME,

        /** 显示名不以 `.kdbx` 结尾 */
        NOT_KDBX_NAME
    }

    /**
     * 入站 URI 准入（攻击者 scheme 面）：**只接受 `content://`**。
     * 入参取 `Uri.toString()` / `Intent` 携带的字符串形态——纯 `String` 判定，宿主 JVM 可测。
     */
    fun acceptInboundUri(uriString: String?): Decision = when {
        uriString.isNullOrBlank() -> Decision.Reject(Reason.EMPTY_URI)
        uriString.startsWith(CONTENT_SCHEME_PREFIX) -> Decision.Accept
        else -> Decision.Reject(Reason.UNSUPPORTED_SCHEME)
    }

    /**
     * 显示名准入（伪装后缀面）：末段纯文件名 + `.kdbx` 后缀。
     * `evil.kdbx` 这类「名字对、内容非 KDBX」由密码学层 fail-closed 兜底，本判据只锁**形态**。
     */
    fun acceptDisplayName(name: String?): Decision = when {
        name.isNullOrBlank() -> Decision.Reject(Reason.EMPTY_NAME)
        name.contains('/') || name.contains('\\') -> Decision.Reject(Reason.PATH_SHAPED_NAME)
        !name.endsWith(".kdbx", ignoreCase = true) -> Decision.Reject(Reason.NOT_KDBX_NAME)
        else -> Decision.Accept
    }

    /** 路径是否为 `content://` 形态 */
    fun isContentUri(path: String?): Boolean =
        path?.startsWith(CONTENT_SCHEME_PREFIX) == true

    /**
     * 已登记库路径是否**可解锁**：`content://` 或**裸绝对路径**（平台绝对，`File.isAbsolute`）。
     * 其余（含 `file:/…` 等 scheme 前缀、相对路径、空串）一律不可解锁（fail-closed）——它们会被
     * `File(path)` 当相对路径解析，落进「文件缺失即新建空库」分支。
     */
    fun isUnlockableStoredPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return isContentUri(path) || File(path).isAbsolute
    }
}
