package com.keepasskey.app.ui.screens.database

import androidx.annotation.StringRes
import com.keepasskey.app.R
import com.keepasskey.app.ui.model.VaultDatabaseInfo
import com.keepasskey.app.ui.model.VaultRemovalKind

/**
 * 「移除密码库」确认弹窗的**文案与动作**投影（`ISSUE-P1-241`）。
 *
 * 一份投影同时承载四件事，使「存储类型 → 用哪套措辞、按钮是不是危险动作」不可分离地绑定：
 * 标题 / 正文（含格式化参数）/ 确认按钮文案 / **是否为破坏性动作**。
 *
 * 本类型存在的理由（缺陷形态留痕）：整改前两种存储类型共用**同一套**确认措辞（「不会删除物理文件」）
 * 与同一个普通红色「删除」按钮，外部库的真实行为与之一致，应用私有库却**真的删文件且不可恢复**
 * ——文案与行为相反，用户按文案操作即永久丢库（§243 §7 真机取证）。
 */
data class VaultRemovalConfirmation(
    /** 动作的真实对象（同时决定详情页下行给数据层的删除开关） */
    val kind: VaultRemovalKind,
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    /**
     * 正文的格式化参数。应用私有库时即**被删除的文件名**（AC②：破坏性动作必须指名被删对象），
     * 外部库无参数（不涉及任何文件）。
     */
    val messageArgs: List<String>,
    @StringRes val confirmRes: Int,
    /** 破坏性动作 ⇒ 红底危险按钮（AC②）；外部库的「仅移除关联」用普通按钮，两者措辞亦不同 */
    val destructive: Boolean
) {

    companion object {

        /**
         * 由条目投影 + 应用私有目录派生确认弹窗的全部呈现（**纯函数**，不做文件系统 I/O）。
         *
         * @param database 界面卡片对应的条目（`path` 即库文件真实位置）
         * @param filesDirPath 应用私有目录绝对路径；`null`（上下文不可用）时按「不删文件」兜底
         */
        fun of(
            database: VaultDatabaseInfo,
            filesDirPath: String?
        ): VaultRemovalConfirmation = when (VaultRemovalKind.of(database.path, filesDirPath)) {
            VaultRemovalKind.PRIVATE_FILE -> VaultRemovalConfirmation(
                kind = VaultRemovalKind.PRIVATE_FILE,
                titleRes = R.string.db_picker_remove_private_title,
                messageRes = R.string.db_picker_remove_private_desc,
                // 指名被删文件：界面展示的是本机私有目录里那个**具体文件**
                messageArgs = listOf(database.name),
                confirmRes = R.string.db_picker_remove_private_confirm,
                destructive = true
            )

            VaultRemovalKind.EXTERNAL_LINK -> VaultRemovalConfirmation(
                kind = VaultRemovalKind.EXTERNAL_LINK,
                titleRes = R.string.db_picker_remove_external_title,
                messageRes = R.string.db_picker_remove_external_desc,
                messageArgs = emptyList(),
                confirmRes = R.string.db_picker_remove_external_confirm,
                destructive = false
            )
        }
    }
}
