package com.keepasskey.app.security

/**
 * 剪贴板写入的**单点成败裁决**（ISSUE-P3-553）。
 *
 * ## 为什么需要它
 *
 * `EntryDetailCopyCoordinator` 与 `VaultListClipboardCopy` 各写了一份
 * **逐字相同**的 `private fun writeToClipboard(write: ...): Boolean`
 * （通道 null → false；`try { channel.write(); true } catch { false }`）。
 * 这两份是 `ISSUE-P2-353 AC①`「只在**真正写入成功**后才报成功」的**唯一判据**——
 * 判据重复意味着将来任何一侧的收紧（例如区分「通道缺失」与「写入异常」）都会漏改另一侧，
 * 而漏改的表现正是该 AC 要消灭的「写入失败却报已复制」。
 *
 * ## 契约
 *
 * - 通道为 null（未注入 / 会话已锁）⇒ 失败；
 * - 写入抛异常 ⇒ 失败。**异常消息不落日志**：通道异常不携带敏感载荷，且本仓日志严禁敏感明文；
 * - 只返回**成败**，不返回异常本身（调用方不需要，也不应据此拼用户文案）。
 */
internal fun ClipboardSecurityChannel?.tryWrite(write: ClipboardSecurityChannel.() -> Unit): Boolean {
    val channel = this ?: return false
    return try {
        channel.write()
        true
    } catch (_: Exception) {
        false
    }
}
