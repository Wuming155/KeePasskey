package com.keepasskey.app.data.childdb

import java.io.File

/**
 * 首版子库挂载**恒为只读**。
 *
 * 该常量是 [ChildDatabaseMount.readOnly] 的唯一取值来源，也是持久化解码时的强制回落值：
 * 存储层若出现 `false`（历史残留或被篡改）一律按只读重新装载，绝不允许由持久化数据开启写通道。
 */
const val CHILD_DATABASE_READ_ONLY: Boolean = true

/**
 * 子库来源与别名的校验边界（集中具名常量，禁止散落魔法值）。
 */
internal object ChildDatabaseLimits {
    /** 别名长度上限（设置页单行展示；超出即拒绝，避免截断歧义） */
    const val MAX_ALIAS_LENGTH = 64

    /** 来源 URI / 路径长度上限（Android 路径上限的保守取值） */
    const val MAX_SOURCE_LENGTH = 4096

    /** SAF / ContentProvider 来源前缀 */
    const val CONTENT_SCHEME = "content://"

    /** 本地来源可识别的扩展名（大小写不敏感） */
    const val KDBX_EXTENSION = ".kdbx"
}

/**
 * 子库来源类型。
 *
 * 对齐 keepass2android「子数据库」能力族（`docs/references/keepass2android-架构分析.md` §6.1 多库会话）
 * 与 `IFileStorage` 的协议前缀路由思路（§5.2）：本项目首版只接纳**本机可读**的两类来源，
 * 不接纳 `http(s)://` / `webdav://` 一类网络前缀——子库永不参与同步（见
 * [ChildDatabaseSessionManager] KDoc 的「同步交互不变量」）。
 */
enum class ChildDatabaseSourceKind {
    /** 系统 SAF / ContentProvider 提供的 `content://` 来源（含外部存储与云盘 App 的挂载点） */
    CONTENT_URI,

    /** 设备本地绝对路径（通常位于应用私有目录） */
    LOCAL_FILE
}

/**
 * 子库挂载记录（**全部字段非敏感**，可持久化）。
 *
 * 记录里**只有引用，没有秘密**：子库主密码与密钥文件字节一律不落盘（[credentialRefId] 仅是
 * 指向进程内凭据槽位的不透明标识，不含任何密钥材料）。挂载记录本身不写入根库 `.kdbx`，
 * 详见 [ChildDatabaseSessionManager] KDoc 的「挂载点抽象落地说明」。
 *
 * @param id 挂载身份（随机 UUID hex，稳定标识一条挂载记录）
 * @param alias 用户可见别名（去空白后 1..[ChildDatabaseLimits.MAX_ALIAS_LENGTH] 字符）
 * @param sourceUri `content://…` 或本地绝对路径
 * @param sourceKind 来源类型，由 [sourceUri] 解析得出并随记录持久化
 * @param credentialRefId 凭据引用标识：指向 [ChildDatabaseCredentialStore] 中的**进程内**凭据槽位，
 *   锁库 / 卸载 / 凭据被拒时随槽位一并清零；本身不是密钥，也不可反推出密钥
 * @param mountedAtEpochMillis 挂载时刻（仅用于展示排序）
 * @param readOnly 只读标记：首版恒为 [CHILD_DATABASE_READ_ONLY]
 */
data class ChildDatabaseMount(
    val id: String,
    val alias: String,
    val sourceUri: String,
    val sourceKind: ChildDatabaseSourceKind,
    val credentialRefId: String,
    val mountedAtEpochMillis: Long,
    val readOnly: Boolean = CHILD_DATABASE_READ_ONLY
)

/**
 * 子库条目的**只读投影**（非敏感展示字段）。
 *
 * 投影刻意不携带密码：条目密码字段留在解密树内，而解密树在投影生成后立即定点擦除
 * （见 [ChildReadOnlySession]）。因此本类型可以安全地驻留 `StateFlow` 并被根库界面消费，
 * 不存在把子库密码物化进 UI 状态流的路径。
 *
 * @param groupPath 相对子库根分组的路径（根分组直接子项为 `""`，子分组用 `/` 连接）
 */
data class ChildDatabaseEntryProjection(
    val mountId: String,
    val mountAlias: String,
    val entryUuid: String,
    val title: String,
    val username: String,
    val url: String,
    val notes: String,
    val groupPath: String,
    val tags: List<String>,
    val iconId: Int,
    val hasPassword: Boolean
)

/**
 * 一次成功打开的子库快照（只读投影 + 计数），供上层直接消费。
 */
data class ChildDatabaseSnapshot(
    val mountId: String,
    val mountAlias: String,
    val databaseName: String,
    val groupCount: Int,
    val entries: List<ChildDatabaseEntryProjection>,
    val openedAtEpochMillis: Long
)

/**
 * 单个挂载的运行时状态机（**非持久化**；进程重启后全部回落 [Closed]）。
 *
 * 流转：
 * ```
 * Closed ──open()──▶ Opening ──成功──▶ Opened
 *                       ├──密码/密钥文件错误──▶ CredentialRejected（错误凭据已即时清零）
 *                       ├──来源不可读────────▶ SourceUnavailable
 *                       └──损坏/版本不支持等──▶ Failed(reason)
 * 任意状态 ──根库锁定 / 卸载 / closeAll()──▶ Closed（凭据一并清零）
 * ```
 */
sealed interface ChildDatabaseMountState {

    /** 已登记但本进程内未打开（含：进程重启后尚未重开、锁库终止后、卸载前的初始态） */
    data object Closed : ChildDatabaseMountState

    /** 正在解密打开（KDF 与 XML 解析在 `Dispatchers.Default`） */
    data object Opening : ChildDatabaseMountState

    /** 已成功打开并完成条目投影 */
    data class Opened(val snapshot: ChildDatabaseSnapshot) : ChildDatabaseMountState

    /** 子库凭据被拒：主密码或密钥文件错误；错误凭据已即时清零，可重新提供 */
    data object CredentialRejected : ChildDatabaseMountState

    /** 来源不可读：文件不存在、URI 无权限或 IO 失败 */
    data object SourceUnavailable : ChildDatabaseMountState

    /** 其他失败（损坏、版本不支持、解析资源超限、未知异常） */
    data class Failed(val reason: ChildDatabaseFailureReason) : ChildDatabaseMountState
}

/**
 * 子库失败原因分型（**领域层报键、UI 层翻译**，对齐 `docs/references/keepass2android-架构分析.md`
 * §9.4 的 `UiStringKey` 文案键实践）。
 *
 * 本枚举不含任何用户文案：UI 层据取值映射到 `strings.xml` 条目，
 * 经由 [ChildDatabaseFailureReason.of] 从失败结果中取回分型。
 */
enum class ChildDatabaseFailureReason {

    /** 别名非法（空白 / 超长 / 含控制字符） */
    INVALID_ALIAS,

    /** 来源非法（既非 `content://` 亦非以 `.kdbx` 结尾的绝对路径，或含控制字符） */
    INVALID_SOURCE,

    /** 同一来源已挂载（避免对同一文件重复解密与重复计数） */
    DUPLICATE_MOUNT,

    /** 挂载记录不存在（已被卸载或从未登记） */
    MOUNT_NOT_FOUND,

    /** 凭据缺失：本进程内没有该挂载的凭据（锁库终止 / 进程重启后必须重新输入） */
    CREDENTIAL_MISSING,

    /** 凭据被拒：主密码或密钥文件错误；错误凭据已即时清零 */
    CREDENTIAL_REJECTED,

    /** 解锁失败次数达阈值进入退避锁定（ISSUE-P2-17：锁定期内不进入解密管线） */
    THROTTLED,

    /** 来源不可读：文件不存在、URI 无权限或 IO 失败 */
    SOURCE_UNAVAILABLE,

    /** KDBX 主版本不受支持（非 v4） */
    UNSUPPORTED_VERSION,

    /** KDBX 文件损坏或被篡改 */
    CORRUPT_FILE,

    /** 解析 / 读取过程 IO 异常 */
    IO_ERROR,

    /** 打开途中根库锁定，本次结果已作废（世代失效） */
    TERMINATED,

    /** 未知失败 */
    UNKNOWN;

    companion object {
        /** 追溯 `cause` 链的深度上限（防异常链环导致的死循环） */
        private const val MAX_CAUSE_DEPTH = 8

        /**
         * 从失败结果中取回分型：优先识别 [ChildDatabaseException]（含其 `cause` 链），
         * 其他异常一律归一为 [UNKNOWN]。UI 层据此映射文案，无需字符串匹配。
         */
        fun of(error: Throwable): ChildDatabaseFailureReason {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current is ChildDatabaseException) return current.reason
                current = current.cause
                depth++
            }
            return UNKNOWN
        }
    }
}

/**
 * 子库操作失败的领域异常。
 *
 * `message` 仅为分型名（英文枚举名，非敏感），用户文案一律由 UI 层按
 * [reason] 映射；`cause` 保留原始异常供诊断日志使用，**不得**上浮为 UI 文案
 * （对齐 `KdbxResult.Failure` 对裸异常消息的既有约束）。
 *
 * 取分型的推荐入口是 [ChildDatabaseFailureReason.of]（自动穿透 `cause` 链），
 * 直接读 [reason] 仅适用于异常实例就在手上的场景。
 */
class ChildDatabaseException(
    val reason: ChildDatabaseFailureReason,
    cause: Throwable? = null
) : Exception(reason.name, cause)

/**
 * 子库来源与别名的解析 / 归一化（纯函数，无 Android 依赖，可 JVM 直测）。
 */
internal object ChildDatabaseSourceValidator {

    /**
     * 归一化别名：去首尾空白后校验，非法返回 null。
     *
     * 拒绝控制字符（含 = `\u0001` / `\u0002` 分隔符）是**持久化编码的前提**：
     * 挂载注册表以分隔符拼接存储，别名与来源均为单行可打印文本，故记录不会被拆错。
     */
    fun normalizeAlias(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > ChildDatabaseLimits.MAX_ALIAS_LENGTH) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    /**
     * 解析来源类型：非法返回 null。
     *
     * 本地路径仅接纳**绝对路径 + `.kdbx` 扩展名**，避免把任意相对路径当作子库；
     * `content://` 不做扩展名约束（SAF 文档名的扩展名不保证保留）。
     */
    fun resolveKind(raw: String): ChildDatabaseSourceKind? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > ChildDatabaseLimits.MAX_SOURCE_LENGTH) return null
        if (trimmed.any { it.isISOControl() }) return null
        return when {
            trimmed.startsWith(ChildDatabaseLimits.CONTENT_SCHEME) &&
                trimmed.length > ChildDatabaseLimits.CONTENT_SCHEME.length ->
                ChildDatabaseSourceKind.CONTENT_URI

            File(trimmed).isAbsolute &&
                trimmed.endsWith(ChildDatabaseLimits.KDBX_EXTENSION, ignoreCase = true) ->
                ChildDatabaseSourceKind.LOCAL_FILE

            else -> null
        }
    }
}
