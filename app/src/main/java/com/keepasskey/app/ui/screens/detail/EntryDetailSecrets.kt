package com.keepasskey.app.ui.screens.detail

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 详情页「按需解密明文 + 用户显式遮掩意图」的状态持有者。
 *
 * ISSUE-P3-31 批次 C：由 `EntryDetailViewModel`（原 708 行）按**纯结构性拆分**搬出。
 * 收敛动机不仅是行数——把**全部明文驻留点**与**唯一清零入口** [clearAll] 放在同一处，
 * 使「切换条目 / 离开页面必须擦除上一条目明文」这条铁律可被单点审查。
 *
 * M1 整改语义（原样保留）：明文仅允许在用户显式查看期间驻留；
 * ISSUE-P3-17 语义（原样保留）：遮掩态存的是**用户意图**（null = 未操作，遵从偏好默认值），
 * 因此偏好快照刷新不会覆盖用户已做出的展开/收起。
 */
internal class EntryDetailSecrets {

    /** 按需解密出的当前密码明文（仅在查看期间驻留，隐藏即清空）。 */
    val revealedPassword = MutableStateFlow<String?>(null)

    /** 按需解密出的历史修订密码（对比弹窗打开期间驻留），键为修订 id。 */
    val revealedRevisionPasswords = MutableStateFlow<Map<String, String>>(emptyMap())

    /** F2 整改：受保护自定义字段按需解密出的明文（收起即清空），键为字段 id。 */
    val revealedProtectedFields = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 受保护自定义字段的展开/收起状态，键为字段 id。 */
    val protectedVisibility = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * 密码的**用户显式遮掩意图**：
     * null = 本次会话尚未操作（遵从 `maskPasswordsDefault`），true = 显式收起，false = 显式展开。
     */
    val passwordMaskOverride = MutableStateFlow<Boolean?>(null)

    /** TOTP 验证码的同构显式遮掩意图（默认值来自 `maskTotpDefault`）。 */
    val totpMaskOverride = MutableStateFlow<Boolean?>(null)

    /**
     * 唯一清零入口：擦除全部按需解密明文，并把遮掩态复位为「未操作」。
     *
     * 复位为 null（而非直接置为遮掩）是有意的：切换条目后应回到**偏好声明的默认态**，
     * 而不是把上一条目的用户意图带到新条目。
     */
    fun clearAll() {
        revealedPassword.value = null
        revealedRevisionPasswords.value = emptyMap()
        revealedProtectedFields.value = emptyMap()
        passwordMaskOverride.value = null
        totpMaskOverride.value = null
        protectedVisibility.value = emptyMap()
    }
}

/**
 * ISSUE-P2-15：把仓库返回的 CharArray 独占副本转成 UI 展示 String。
 * String 一旦物化不可擦除（Compose Text 显示边界），但借用的 CharArray 副本用毕立即清零。
 *
 * ISSUE-P3-31 批次 C：原为 `EntryDetailViewModel` 的 private 扩展，
 * 因揭示控制器与 ViewModel 两处都需要该边界语义，提为同包 `internal` 顶层扩展（**实现逐字不变**）。
 */
internal fun CharArray?.toDisplayString(): String? {
    if (this == null) return null
    return try {
        String(this)
    } finally {
        fill('0')
    }
}
