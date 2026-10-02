package com.keepasskey.app.ui.screens.unlock

import androidx.annotation.StringRes
import com.keepasskey.app.R

/**
 * ISSUE-P3-437 AC①：解锁链的阶段型过程文案（状态驱动，随 [UnlockUiState.loadStage] 下发）。
 *
 * 只承载「用户可感知的过程描述」，绝不携带任何敏感信息：不含密钥文件名、不含派生中间量
 * （AC③ 的不泄露红线由枚举零载荷保证——每个阶段只有一个静态文案资源）。
 * Argon2 原生侧进度百分比为本条目的进阶项，不在本枚举承载（确定段仍走 [UnlockUiState.loadProgress]）。
 */
enum class UnlockStage(@StringRes val labelRes: Int) {
    /** 正在读取密钥文件…（SAF 现读：冷启动记忆恢复 / 生物识别解封后记忆现读） */
    READING_KEY_FILE(R.string.unlock_stage_reading_keyfile),

    /** 正在派生密钥…（KDF 派生 + 库解密，unlockActiveDatabase 管线期间） */
    DERIVING_KEYS(R.string.unlock_stage_deriving_keys),

    /** 正在解封验证…（硬件 Keystore 解封封印凭据，StrongBox 大载荷可达数十秒） */
    UNSEALING(R.string.unlock_stage_unsealing)
}
