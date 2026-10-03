package com.keepasskey.app.ui.screens.unlock

/**
 * 已记忆的密钥文件元数据（ISSUE-P3-04）。
 *
 * 仅承载**非密钥元数据**：SAF Uri 与文档显示名。密钥文件字节与派生密钥
 * 绝不进入本类型、UiState、日志或异常消息。
 */
data class RememberedKeyFile(val uri: String, val displayName: String)

/**
 * 密钥文件读取结果（ISSUE-P3-04）：分型承载失败原因，禁止静默失败——
 * 「读不到」（提供方拒绝/流异常/空文件）与「内容错」（由数据库层
 * `KdbxCorruptFileException` / `KdbxInvalidCredentialsException` 表达）语义可分。
 *
 * ISSUE-P3-435：不设文件大小上限（对齐 KeePass 官方 / kp2a / KeePassXC——整文件读入，
 * 最多拒空文件）；密钥文件内容即密钥材料本身，任何截断都会产出错误密钥。
 */
sealed interface KeyFileReadResult {

    /**
     * 读取成功。[bytes] 所有权移交调用方，调用方用毕**必须**显式 `fill(0)` 清零；
     * [displayName] 为 SAF 文档显示名（非密钥内容，可进入 UiState）。
     */
    class Success(val bytes: ByteArray, val displayName: String) : KeyFileReadResult

    /** 空文件（0 字节）——不可能是合法密钥文件 */
    data object Empty : KeyFileReadResult

    /** 流不可打开 / 读取异常 / 提供方拒绝访问 / 无可用上下文 */
    data object Unreadable : KeyFileReadResult
}

/**
 * 密钥文件访问契约（ISSUE-P3-04）。
 *
 * 定义在解锁特性侧而非 `di` 包，因为生产实现 [SafKeyFileAccess] 属 SAIF（SAF）
 * 会话内资源；ViewModel 依赖本抽象，单测注入内存假实现即可覆盖
 * 「偏好开关 / 授权失效降级 / 记忆与清除」全部决策路径。
 */
interface KeyFileAccess {

    /**
     * 「记住上次成功解锁使用的密钥文件」偏好是否开启。
     * 读取失败或无持久化层时按**关闭**处理（fail-closed：宁可少记，不越权留存）。
     */
    suspend fun isRememberEnabled(): Boolean

    /** 读取密钥文件原始字节（IO 线程内完成；失败返回分型结果，绝不抛出） */
    suspend fun read(uri: String): KeyFileReadResult

    /**
     * 申请对该 Uri 的**持久化读授权**（provider 不支持时返回 false，调用方优雅降级）。
     * 仅在偏好开启时调用（最小权限原则：不记忆就不扩大持久授权面）。
     */
    suspend fun persistReadPermission(uri: String): Boolean

    /** 该 Uri 当前是否仍持有持久化读授权（决定记忆的 Uri 能否在下次冷启动恢复） */
    suspend fun hasPersistedReadPermission(uri: String): Boolean

    /** 读取**该库**已记忆的密钥文件元数据（ISSUE-P2-460 AC①；未登记返回 null） */
    suspend fun loadRemembered(databaseId: String): RememberedKeyFile?

    /**
     * 读取旧版全局单槽的密钥文件元数据（ISSUE-P2-460 AC① 降级通道，**只读**）。
     * 仅作「本库未登记记忆」时的一句话提示来源，**不参与任何解锁裁决**——
     * 旧版记录无法归属到具体库，自动套用即跨库串因子。
     */
    suspend fun loadLegacyGlobalHint(): RememberedKeyFile?

    /** 按库记忆密钥文件元数据（仅 Uri + 显示名，非密钥材料；ISSUE-P2-460 AC①） */
    suspend fun remember(databaseId: String, uri: String, displayName: String)

    /**
     * 清除记忆（ISSUE-P2-460 AC①）：[databaseId] 非空时同时清除该库的按库记录；
     * 旧版全局槽在任何清除裁决下都一并清掉（陈旧元数据不残留）。
     */
    suspend fun forget(databaseId: String?)
}

/**
 * 密钥文件记忆裁决（纯函数，无 Android 依赖，供 JVM 单测直接覆盖）。
 *
 * 把「偏好开关 + 记录完整性 + 持久化授权有效性」的判定从 Android IO 实现中析出，
 * 使 [UnlockViewModel] 的记忆/恢复行为可在无 SAF 交互的前提下被真实断言。
 */
internal object KeyFileRememberPolicy {

    /**
     * 选取成功后是否申请持久化授权并登记来源：
     * 偏好关闭（用户明确不要记忆）或来源为空时不申请，避免无谓扩大持久授权面。
     */
    fun shouldTrackSource(rememberEnabled: Boolean, sourceUri: String?): Boolean =
        rememberEnabled && !sourceUri.isNullOrBlank()

    /**
     * 冷启动恢复裁决：偏好开启 + 记录完整 + 该 Uri 仍持有持久化读授权。
     * 授权失效（用户撤销 / 系统清理 / 文件被删）→ false，调用方静默降级为「未记住」。
     */
    fun canRestore(
        rememberEnabled: Boolean,
        remembered: RememberedKeyFile?,
        permissionValid: Boolean
    ): Boolean = rememberEnabled && remembered != null &&
        remembered.uri.isNotBlank() && permissionValid
}
