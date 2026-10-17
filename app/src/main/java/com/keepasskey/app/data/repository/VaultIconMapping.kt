package com.keepasskey.app.data.repository

/**
 * UI 图标名 ↔ KDBX 标准 iconId 的双向映射（`ISSUE-P2-645`：自 `VaultEntryMapper` 拆分）。
 *
 * 纯查表、无状态、无 IO；两个方向互为反演（未知名回退见各自 KDoc）。
 */
internal object VaultIconMapping {

    /** KDBX 标准 iconId → UI 图标名（未知 id 回退 `key`）。 */
    fun nameOf(iconId: Int): String {
        return when (iconId) {
            0, 58 -> "key"
            1, 8, 16 -> "public"
            3 -> "wifi"
            5 -> "forum"
            4, 27, 47, 48, 49, 50 -> "folder"
            7, 22, 41, 44 -> "description"
            13 -> "vpn_key"
            19, 25, 40 -> "email"
            20, 34 -> "dns"
            26, 36 -> "database"
            29, 30, 33 -> "terminal"
            32 -> "code"
            35 -> "cloud"
            37, 66 -> "credit_card"
            43 -> "delete"
            51 -> "lock"
            52 -> "security"
            67 -> "work"
            68 -> "phone"
            else -> "key"
        }
    }

    /** UI 图标名 → KDBX 标准 iconId（与 [nameOf] 互为反演；未知名回退 [fallbackId]）。 */
    fun idOf(iconName: String, fallbackId: Int): Int {
        return when (iconName) {
            "key" -> 0
            "public" -> 1
            "wifi" -> 3
            "forum" -> 5
            "folder" -> 48
            "description" -> 44
            "vpn_key" -> 13
            "email" -> 19
            "dns" -> 20
            "database" -> 26
            "terminal" -> 29
            "code" -> 32
            "cloud" -> 35
            "credit_card" -> 37
            "lock" -> 51
            "security" -> 52
            "work" -> 67
            "phone" -> 68
            "account_balance" -> 66
            "delete" -> RealVaultRepository.ICON_TRASH_BIN
            else -> fallbackId
        }
    }
}
