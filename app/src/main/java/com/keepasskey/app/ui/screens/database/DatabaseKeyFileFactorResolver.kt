package com.keepasskey.app.ui.screens.database

import com.keepasskey.app.R
import com.keepasskey.app.data.repository.CreateKeyFileFactor
import com.keepasskey.app.ui.model.UiMessage
import com.keepasskey.app.ui.screens.unlock.KeyFileAccess
import com.keepasskey.app.ui.screens.unlock.KeyFileReadResult

/**
 * 建库密钥文件因子的解析（ISSUE-P2-399 批次自 `DatabasePickerViewModel` 下沉：
 * 该 ViewModel 因云端打开补齐凭据字段而逼近 tier2 行数门禁，此处为**纯结构性搬移**，
 * 解析语义与失败分支零变化）。
 *
 * 建库密钥文件因子的解析结果（ISSUE-P3-21）：
 * - [KeyFileFactorResolution.Resolved.generated] 标记是否为「生成型」因子（决定是否需要
 *   一次性交付提示）；[borrowedKeyFileBytes] 为借用给数据层的字节副本，调用方用毕必须清零；
 * - [KeyFileFactorResolution.Failed] 表示因子无法成立（既有密钥文件读取失败等）：
 *   显式失败，不得降级为其它因子。
 */
/** 建库密钥文件因子的解析结果（ISSUE-P3-21；顶层声明以保持调用点原名可用） */
internal sealed interface KeyFileFactorResolution {

    /**
     * [generated] 标记是否为「生成型」因子（决定是否需要一次性交付提示）；
     * [borrowedKeyFileBytes] 为借用给数据层的字节副本，调用方用毕必须清零；
     * [sourceUri] / [sourceDisplayName] 为既有文件的来源元数据（ISSUE-P2-460 AC②：
     * 建库成功即按库登记记忆用；生成型因子二者皆 null）。
     */
    class Resolved(
        val factor: CreateKeyFileFactor,
        val generated: Boolean,
        val borrowedKeyFileBytes: ByteArray? = null,
        val sourceUri: String? = null,
        val sourceDisplayName: String? = null
    ) : KeyFileFactorResolution

    /** 因子无法成立（既有密钥文件读取失败等）：显式失败，不得降级为其它因子 */
    class Failed(val message: UiMessage) : KeyFileFactorResolution
}

internal object DatabaseKeyFileFactorResolver {

    /**
     * 解析建库密钥文件因子：无密钥文件 / 生成型 / 用户选定既有文件。
     *
     * 既有文件路径**没有任何降级分支**：读不到就失败，绝不改用生成型（那会产出一个
     * 用户手上没有对应密钥文件的库）。
     */
    suspend fun resolve(
        keyFile: Boolean,
        sourceUri: String?,
        keyFileAccess: KeyFileAccess?
    ): KeyFileFactorResolution {
        if (!keyFile) {
            return KeyFileFactorResolution.Resolved(CreateKeyFileFactor.None, generated = false)
        }
        if (sourceUri.isNullOrBlank()) {
            return KeyFileFactorResolution.Resolved(CreateKeyFileFactor.Generate, generated = true)
        }
        val access = keyFileAccess
            ?: return KeyFileFactorResolution.Failed(UiMessage(R.string.unlock_keyfile_read_failed))
        return when (val outcome = access.read(sourceUri)) {
            is KeyFileReadResult.Success -> KeyFileFactorResolution.Resolved(
                factor = CreateKeyFileFactor.Existing(outcome.bytes),
                generated = false,
                // 字节所有权已移交因子；清零责任随借用契约留给 createDatabase 的调用收尾
                borrowedKeyFileBytes = outcome.bytes,
                sourceUri = sourceUri,
                sourceDisplayName = outcome.displayName
            )
            // 「读不到」分型（空文件 / 流异常）一律显式反馈，绝不静默忽略
            KeyFileReadResult.Empty,
            KeyFileReadResult.Unreadable -> KeyFileFactorResolution.Failed(
                UiMessage(R.string.unlock_keyfile_read_failed)
            )
        }
    }
}
