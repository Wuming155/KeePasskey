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

    /** KDBX 文件签名（0x9AA2D903 / 0xB54BFB67 的小端字节序） */
    val KDBX_SIGNATURE = byteArrayOf(
        0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
        0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte()
    )
}
