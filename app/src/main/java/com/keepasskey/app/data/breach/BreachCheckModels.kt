package com.keepasskey.app.data.breach

/**
 * 已泄露密码检测状态机（TASK-47：`FINDINGS_TRACKER.md` P2-28 健康度「已泄露密码」假指标整改）。
 *
 * 状态设计遵循本项目「诚实化」先例（TASK-32 强度恒 112 / TASK-33 TOTP 假码 / TASK-36 空 onClick）：
 * 未检测、检测失败与「已检测且安全」是三种语义，绝不允许以 0 冒充「安全」。
 */
enum class BreachCheckStatus {
    /** 开关未开启：不发起任何网络请求，指标无值（UI 如实展示「未启用」而非 0） */
    DISABLED,

    /** 已开启且正在与泄露库比对 */
    CHECKING,

    /** 已比对完成，未命中任何公开泄露记录 */
    CLEAN,

    /** 已比对完成，命中公开泄露记录 */
    BREACHED,

    /** 已发起比对但失败（网络不可达 / 服务端错误 / 响应解析失败），如实上浮 */
    FAILED
}

/**
 * 泄露库查询失败。
 *
 * 抛出即上浮，禁止任何静默回落（回落为 0 等价于谎报「未泄露」）。
 */
class BreachCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 一次泄露比对的结果。
 *
 * @property status 检测状态，见 [BreachCheckStatus]
 * @property breachedEntryIds 命中泄露库的条目 ID（KDBX UUID 十六进制）；仅 [BreachCheckStatus.BREACHED] 下非空
 * @property errorMessage 失败原因；仅 [BreachCheckStatus.FAILED] 下非空
 */
data class BreachCheckOutcome(
    val status: BreachCheckStatus,
    val breachedEntryIds: Set<String> = emptySet(),
    val errorMessage: String? = null
) {
    val breachedCount: Int get() = breachedEntryIds.size
}

/**
 * 泄露库「范围查询」客户端（k-anonymity）。
 *
 * 契约：调用方仅提供密码 SHA-1 的**前 5 位十六进制前缀**，服务端返回该前缀下全部哈希后缀；
 * 密码明文与完整哈希均不出端。实现方必须保证不做任何形式的完整哈希上传。
 */
interface BreachRangeClient {

    /**
     * 查询某一前缀下的全部泄露哈希后缀。
     *
     * @param prefix 密码 SHA-1 十六进制的前 [com.keepasskey.app.data.breach.BreachHasher.PREFIX_LENGTH] 位（大小写不敏感，内部归一为大写）
     * @return 该前缀下命中泄露库的后缀集合（大写十六进制）
     * @throws BreachCheckException 前缀非法、网络失败、服务端非 2xx 或响应无法解析
     */
    suspend fun queryRange(prefix: String): Set<String>
}
