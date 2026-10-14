package com.keepasskey.app.autofill

/**
 * ISSUE-P2-73 AC③：设备侧实测用例的**播种契约**（instrumented 用例专用）。
 *
 * 播种器 [AutofillTestVaultSeeder] 与认证链路用例 [AutofillAuthChainDeviceTest] 必须逐字一致地
 * 使用同一份凭据/文件名，故收敛到此处作为唯一来源。
 *
 * 全部值为**虚构测试数据**（敏感纪律：不得使用任何真实凭据）。
 */
internal object AutofillSeedContract {

    /** 播种的密码库文件名（落于应用 `filesDir`，本进程首次触达仓库时被扫描为活动库） */
    const val VAULT_FILE_NAME = "ac3-autofill-device.kdbx"

    /** 播种条目标题（用例在系统填充 UI / 选择器列表中按此文本定位） */
    const val ENTRY_TITLE = "真机自动填充用例"

    /** 虚构账号（用于断言填充结果） */
    const val USERNAME = "ac3-device-user"

    /** 虚构条目口令（用于断言填充结果） */
    const val PASSWORD = "Ac3Fill#2026"

    /** 虚构主密码：仅字母数字，便于设备侧 `input text` 注入 */
    const val MASTER_PASSWORD_TEXT = "DeviceFill2026"

    // ---- ISSUE-P3-610：活动库前提用的**不同主密码**合成干扰库（全部为虚构测试值） ----

    /** 干扰库文件名（与主测试库不同名，模拟设备上已有的旧库） */
    const val DECOY_FILE_NAME = "ac3-decoy-device.kdbx"

    /** 干扰库主密码（与主测试库**不同**，用于验证「已有不同密码旧活动库」矩阵） */
    const val DECOY_MASTER_PASSWORD_TEXT = "DecoyPass2026"

    /** 干扰库条目标题 */
    const val DECOY_ENTRY_TITLE = "旧活动库干扰项"

    /** 干扰库虚构账号 */
    const val DECOY_USERNAME = "ac3-decoy-user"

    /** 干扰库虚构口令 */
    const val DECOY_PASSWORD = "DecoyFill#2026"

    /** KDBX 文件签名（0x9AA2D903 / 0xB54BFB67 的小端字节序） */
    val KDBX_SIGNATURE = byteArrayOf(
        0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
        0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
    )
}
